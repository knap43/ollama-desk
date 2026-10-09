package io.github.ollamadesk.remote

import android.app.Application
import android.os.Build
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ProcessLifecycleOwner
import androidx.lifecycle.viewModelScope
import io.github.ollamadesk.remote.data.Api
import io.github.ollamadesk.remote.data.ApiError
import io.github.ollamadesk.remote.data.Approval
import io.github.ollamadesk.remote.data.AssistantItem
import io.github.ollamadesk.remote.data.ChatDetail
import io.github.ollamadesk.remote.data.ChatSummary
import io.github.ollamadesk.remote.data.Item
import io.github.ollamadesk.remote.data.ModelsState
import io.github.ollamadesk.remote.data.PairingLink
import io.github.ollamadesk.remote.data.Pull
import io.github.ollamadesk.remote.data.Server
import io.github.ollamadesk.remote.data.Store
import io.github.ollamadesk.remote.data.Task
import io.github.ollamadesk.remote.data.ToolItem
import io.github.ollamadesk.remote.data.UserItem
import io.github.ollamadesk.remote.data.friendly
import io.github.ollamadesk.remote.data.parseApproval
import io.github.ollamadesk.remote.data.parsePull
import io.github.ollamadesk.remote.data.str
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.json.JSONObject

enum class Connection { CONNECTING, ONLINE, OFFLINE }

enum class MicState { IDLE, RECORDING, TRANSCRIBING }

/** The chat on screen. [id] is null for a new chat that hasn't been sent yet. */
data class OpenChat(
    val id: String?,
    val title: String = "New chat",
    val model: String? = null,
    val items: List<Item> = emptyList(),
    val running: Boolean = false,
    val loading: Boolean = false,
)

class AppViewModel(app: Application) : AndroidViewModel(app) {
    private val store = Store(app)
    private val notifier = Notifier(app)
    private var api: Api? = null
    private var eventsJob: Job? = null
    private var chatsRefresh: Job? = null

    private val _server = MutableStateFlow(store.server)
    val server = _server.asStateFlow()

    private val _connection = MutableStateFlow(Connection.CONNECTING)
    val connection = _connection.asStateFlow()
    private val _connectionError = MutableStateFlow<String?>(null)
    val connectionError = _connectionError.asStateFlow()

    private val _chats = MutableStateFlow<List<ChatSummary>>(emptyList())
    val chats = _chats.asStateFlow()

    private val _open = MutableStateFlow<OpenChat?>(null)
    val open = _open.asStateFlow()

    private val _approvals = MutableStateFlow<List<Approval>>(emptyList())
    val approvals = _approvals.asStateFlow()

    private val _models = MutableStateFlow(ModelsState())
    val models = _models.asStateFlow()
    private val _pulls = MutableStateFlow<Map<String, Pull>>(emptyMap())
    val pulls = _pulls.asStateFlow()

    private val _tasks = MutableStateFlow<List<Task>>(emptyList())
    val tasks = _tasks.asStateFlow()

    private val _model = MutableStateFlow(store.model)
    val model = _model.asStateFlow()
    private val _agent = MutableStateFlow(store.agent)
    val agent = _agent.asStateFlow()

    private val _messages = MutableSharedFlow<String>(extraBufferCapacity = 8)
    val messages = _messages.asSharedFlow()

    private val _pairing = MutableStateFlow(false)
    val pairing = _pairing.asStateFlow()
    private val _pairError = MutableStateFlow<String?>(null)
    val pairError = _pairError.asStateFlow()

    // ── voice ──
    private val recorder = Recorder()
    private val player = Player(app)
    private var recordingLimit: Job? = null
    private val _voiceReady = MutableStateFlow(false)
    val voiceReady = _voiceReady.asStateFlow()
    private val _mic = MutableStateFlow(MicState.IDLE)
    val mic = _mic.asStateFlow()
    private val _transcripts = MutableSharedFlow<String>(extraBufferCapacity = 4)
    val transcripts = _transcripts.asSharedFlow()
    /** The text being read aloud (or prepared), so its speaker button can show a stop icon. */
    private val _speaking = MutableStateFlow<String?>(null)
    val speaking = _speaking.asStateFlow()

    /** Chats the phone started, so a notification can say when their reply is ready. */
    private val waiting = mutableSetOf<String>()

    init {
        store.server?.let { connect(it) }
    }

    private fun foreground() =
        ProcessLifecycleOwner.get().lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)

    private fun say(text: String) {
        _messages.tryEmit(text)
    }

    /** Runs a request, turning failures into a short message instead of a crash. */
    private fun call(block: suspend (Api) -> Unit) {
        val current = api ?: return
        viewModelScope.launch {
            try {
                block(current)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (e is ApiError && e.code == 401) unpaired(e.message) else say(friendly(e))
            }
        }
    }

    // ── pairing ──

    fun pair(text: String) {
        val link = PairingLink.parse(text)
        if (link == null) {
            _pairError.value = "That isn't a pairing code from Ollama Desk."
            return
        }
        _pairing.value = true
        _pairError.value = null
        viewModelScope.launch {
            try {
                val name = listOf(Build.MANUFACTURER.replaceFirstChar { it.uppercase() }, Build.MODEL)
                    .distinct().joinToString(" ").take(60)
                val server = Api.pair(link, name)
                store.server = server
                _server.value = server
                connect(server)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _pairError.value = friendly(e)
            } finally {
                _pairing.value = false
            }
        }
    }

    fun unpair() {
        eventsJob?.cancel()
        api = null
        store.server = null
        _server.value = null
        _chats.value = emptyList()
        _open.value = null
        _approvals.value = emptyList()
    }

    private fun unpaired(message: String) {
        unpair()
        _pairError.value = message
    }

    // ── connection and events ──

    private fun connect(server: Server) {
        val client = Api(server)
        api = client
        eventsJob?.cancel()
        eventsJob = viewModelScope.launch {
            var backoff = 1000L
            while (true) {
                _connection.value = Connection.CONNECTING
                try {
                    client.chats().also { _chats.value = sorted(it) } // also finds a working address
                    _connection.value = Connection.ONLINE
                    _connectionError.value = null
                    backoff = 1000L
                    refreshOpen()
                    _voiceReady.value = runCatching { client.voiceReady() }.getOrDefault(false)
                    client.events { handle(it) }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    if (e is ApiError && e.code == 401) {
                        unpaired(e.message)
                        return@launch
                    }
                    _connectionError.value = friendly(e)
                }
                _connection.value = Connection.OFFLINE
                delay(backoff)
                backoff = (backoff * 2).coerceAtMost(30_000L)
            }
        }
    }

    /** Called by the activity when it comes back on screen, so a stale connection retries at once. */
    fun resume() {
        val server = _server.value ?: return
        if (_connection.value == Connection.OFFLINE) connect(server)
        else call { _voiceReady.value = it.voiceReady() } // voice may have been set up on the computer since
    }

    private fun sorted(list: List<ChatSummary>) =
        list.sortedWith(compareByDescending<ChatSummary> { it.pinned }.thenByDescending { it.updated })

    private fun refreshChatsSoon() {
        chatsRefresh?.cancel()
        chatsRefresh = viewModelScope.launch {
            delay(300)
            api?.let { runCatching { _chats.value = sorted(it.chats()) } }
        }
    }

    private suspend fun handle(event: JSONObject) {
        val chat = event.optString("chat")
        val isOpen = _open.value?.id == chat && chat.isNotEmpty()
        when (event.optString("type")) {
            "run_started" -> {
                if (isOpen) _open.update { it?.copy(running = true) }
                refreshChatsSoon()
            }
            "step" -> if (isOpen) appendItem(AssistantItem("", ""))
            "delta" -> if (isOpen) {
                val text = event.optString("text")
                val thinking = event.optString("kind") == "thinking"
                _open.update { open ->
                    open ?: return@update null
                    val items = open.items.toMutableList()
                    val last = items.lastOrNull()
                    if (last is AssistantItem) {
                        items[items.lastIndex] = if (thinking) last.copy(thinking = last.thinking + text)
                        else last.copy(text = last.text + text)
                    } else {
                        items.add(if (thinking) AssistantItem("", text) else AssistantItem(text, ""))
                    }
                    open.copy(items = items)
                }
            }
            "tool" -> if (isOpen) appendItem(
                ToolItem(event.optString("label"), event.optString("summary"), "pending", "")
            )
            "tool_result" -> if (isOpen) _open.update { open ->
                open ?: return@update null
                val items = open.items.toMutableList()
                val index = items.indexOfLast { it is ToolItem && it.status == "pending" }
                if (index >= 0) {
                    items[index] = (items[index] as ToolItem).copy(
                        status = event.optString("status"), output = event.optString("output")
                    )
                }
                open.copy(items = items)
            }
            "approval" -> {
                val approval = parseApproval(event)
                _approvals.update { list -> list.filter { it.id != approval.id } + approval }
                if (!foreground()) notifier.approval(approval.id, approval.chat, approval.label, approval.detail)
            }
            "approval_closed" -> {
                val id = event.optString("id")
                _approvals.update { list -> list.filter { it.id != id } }
                notifier.cancel(id)
            }
            "done" -> {
                val error = event.str("error")
                if (isOpen) refreshOpen()
                if (error != null && (isOpen || chat in waiting)) say(error)
                if (chat in waiting) {
                    waiting.remove(chat)
                    if (!foreground()) {
                        val title = _chats.value.firstOrNull { it.id == chat }?.title ?: "Ollama Desk"
                        notifier.reply(chat, title, error ?: "The reply is ready.")
                    }
                }
                refreshChatsSoon()
                if (_tasks.value.any { it.chat == chat }) loadTasks()
            }
            "pull" -> {
                val pull = parsePull(event)
                _pulls.update { it + (pull.name to pull) }
            }
            "pull_done" -> {
                val name = event.optString("name")
                _pulls.update { it - name }
                val error = event.str("error")
                say(if (error != null) "Couldn't download $name: $error" else "$name is ready")
                loadModels()
            }
        }
    }

    private fun appendItem(item: Item) {
        _open.update { it?.copy(items = it.items + item) }
    }

    // ── chats ──

    fun openChat(id: String?) {
        if (id == null) {
            _open.value = OpenChat(id = null, model = _model.value)
            return
        }
        val title = _chats.value.firstOrNull { it.id == id }?.title ?: ""
        _open.value = OpenChat(id = id, title = title, loading = true)
        refreshOpen()
    }

    fun closeChat() {
        cancelRecording()
        stopSpeaking()
        _open.value = null
    }

    private fun refreshOpen() {
        val id = _open.value?.id ?: return
        call { api ->
            val detail: ChatDetail = api.chat(id)
            _open.update { open ->
                if (open?.id != id) open
                else open.copy(title = detail.title, model = detail.model, items = detail.items,
                               running = detail.running, loading = false)
            }
        }
    }

    fun send(text: String) {
        val open = _open.value ?: return
        val message = text.trim()
        if (message.isEmpty() || open.running) return
        val model = if (open.id == null) _model.value else null // existing chats keep their own model
        _open.value = open.copy(
            items = open.items + UserItem(message, emptyList(), null),
            running = true,
            title = if (open.id == null) message.lineSequence().first().take(60) else open.title,
        )
        call { api ->
            try {
                if (open.id == null) {
                    val id = api.newChat(message, model, _agent.value)
                    waiting.add(id)
                    _open.update { it?.copy(id = id) }
                    refreshOpen()
                } else {
                    waiting.add(open.id)
                    api.send(open.id, message, model, _agent.value)
                }
            } catch (e: Exception) {
                _open.update { it?.copy(running = false) }
                throw e
            }
        }
    }

    fun stop() {
        val id = _open.value?.id ?: return
        call { it.stop(id) }
    }

    fun deleteChat(id: String) {
        call { api ->
            api.deleteChat(id)
            _chats.update { list -> list.filter { it.id != id } }
            if (_open.value?.id == id) _open.value = null
        }
    }

    fun answer(approval: Approval, allow: Boolean) {
        _approvals.update { list -> list.filter { it.id != approval.id } }
        notifier.cancel(approval.id)
        call { it.answer(approval.id, allow) }
    }

    fun setAgent(on: Boolean) {
        store.agent = on
        _agent.value = on
    }

    fun refreshChats() = refreshChatsSoon()

    // ── voice ──

    /** Call once the microphone permission has been granted. */
    fun startRecording() {
        if (_mic.value != MicState.IDLE) return
        stopSpeaking()
        try {
            recorder.start()
        } catch (e: Exception) {
            say(e.message ?: "The microphone isn't available")
            return
        }
        _mic.value = MicState.RECORDING
        recordingLimit = viewModelScope.launch {
            delay(Recorder.MAX_SECONDS * 1000L)
            stopRecording()
        }
    }

    fun stopRecording() {
        if (_mic.value != MicState.RECORDING) return
        recordingLimit?.cancel()
        val wav = recorder.stop()
        if (wav.size < 44 + Recorder.RATE / 2) { // under a quarter of a second: a mis-tap
            _mic.value = MicState.IDLE
            return
        }
        _mic.value = MicState.TRANSCRIBING
        val current = api
        if (current == null) {
            _mic.value = MicState.IDLE
            return
        }
        viewModelScope.launch {
            try {
                val text = current.transcribe(wav).trim()
                if (text.isEmpty()) say("Didn't catch anything") else _transcripts.emit(text)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                say("Couldn't transcribe: ${friendly(e)}")
            } finally {
                _mic.value = MicState.IDLE
            }
        }
    }

    fun cancelRecording() {
        if (_mic.value != MicState.RECORDING) return
        recordingLimit?.cancel()
        recorder.cancel()
        _mic.value = MicState.IDLE
    }

    /** Reads [text] aloud with the computer's voice; tapping the same reply again stops it. */
    fun speak(text: String) {
        if (_speaking.value == text) {
            stopSpeaking()
            return
        }
        stopSpeaking()
        val current = api ?: return
        _speaking.value = text
        viewModelScope.launch {
            try {
                val wav = current.speak(text)
                if (_speaking.value == text) player.play(wav) { _speaking.value = null }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (_speaking.value == text) _speaking.value = null
                say("Couldn't read aloud: ${friendly(e)}")
            }
        }
    }

    fun stopSpeaking() {
        player.stop()
        _speaking.value = null
    }

    override fun onCleared() {
        if (recorder.isRecording) recorder.cancel()
        player.stop()
        super.onCleared()
    }

    // ── models ──

    fun loadModels() = call { api ->
        val (state, pulls) = api.models()
        _models.value = state
        _pulls.value = pulls.associateBy { it.name }
    }

    fun chooseModel(name: String?) {
        store.model = name
        _model.value = name
    }

    fun pull(name: String) {
        val clean = name.trim()
        if (clean.isEmpty()) return
        _pulls.update { it + (clean to Pull(clean, "Starting", null)) }
        call { it.modelAction("pull", clean) }
    }

    fun unload(name: String) = call { api ->
        api.modelAction("unload", name)
        delay(500)
        loadModels()
    }

    fun deleteModel(name: String) = call { api ->
        api.modelAction("delete", name)
        if (_model.value == name) chooseModel(null)
        loadModels()
    }

    // ── tasks ──

    fun loadTasks() = call { _tasks.value = it.tasks() }

    fun runTask(task: Task) = call { api ->
        api.runTask(task.id)
        say("“${task.name}” is running. You'll get a notification on the computer when it's done.")
    }

    fun setTaskEnabled(task: Task, enabled: Boolean) = call { api ->
        _tasks.update { list -> list.map { if (it.id == task.id) it.copy(enabled = enabled) else it } }
        api.setTaskEnabled(task.id, enabled)
        _tasks.value = api.tasks()
    }
}
