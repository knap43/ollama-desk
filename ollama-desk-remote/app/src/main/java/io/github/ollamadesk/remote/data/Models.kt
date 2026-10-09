package io.github.ollamadesk.remote.data

import android.net.Uri
import org.json.JSONArray
import org.json.JSONObject

/** A paired computer: where to reach it, the certificate it must present, and our access token. */
data class Server(
    val hosts: List<String>,
    val port: Int,
    val fingerprint: String,
    val token: String,
    val name: String,
) {
    fun toJson(): String = JSONObject()
        .put("hosts", JSONArray(hosts))
        .put("port", port)
        .put("fingerprint", fingerprint)
        .put("token", token)
        .put("name", name)
        .toString()

    companion object {
        fun fromJson(text: String): Server? = runCatching {
            val o = JSONObject(text)
            Server(
                hosts = o.getJSONArray("hosts").strings(),
                port = o.getInt("port"),
                fingerprint = o.getString("fingerprint"),
                token = o.getString("token"),
                name = o.optString("name", "Computer"),
            )
        }.getOrNull()
    }
}

/** What the computer's QR code contains: ollamadesk://pair?h=…&p=…&f=…&s=…&n=… */
data class PairingLink(
    val hosts: List<String>,
    val port: Int,
    val fingerprint: String,
    val secret: String,
    val name: String,
) {
    companion object {
        fun parse(text: String): PairingLink? {
            val uri = runCatching { Uri.parse(text.trim()) }.getOrNull() ?: return null
            if (uri.scheme != "ollamadesk" || uri.host != "pair") return null
            val hosts = uri.getQueryParameter("h").orEmpty().split(",").map { it.trim() }.filter { it.isNotEmpty() }
            val port = uri.getQueryParameter("p")?.toIntOrNull() ?: return null
            val fingerprint = uri.getQueryParameter("f")?.lowercase() ?: return null
            val secret = uri.getQueryParameter("s") ?: return null
            if (hosts.isEmpty() || !Regex("[0-9a-f]{64}").matches(fingerprint)) return null
            return PairingLink(hosts, port, fingerprint, secret, uri.getQueryParameter("n") ?: "Computer")
        }
    }
}

data class ChatSummary(
    val id: String,
    val title: String,
    val updated: Double,
    val pinned: Boolean,
    val scheduled: Boolean,
    val running: Boolean,
)

sealed interface Item

data class UserItem(val text: String, val files: List<String>, val scheduled: String?) : Item

data class AssistantItem(val text: String, val thinking: String) : Item

data class ToolItem(val label: String, val summary: String, val status: String, val output: String) : Item

data class ChatDetail(
    val id: String,
    val title: String,
    val model: String?,
    val running: Boolean,
    val items: List<Item>,
)

data class Approval(
    val id: String,
    val chat: String,
    val label: String,
    val body: String,
    val detail: String,
    val destructive: Boolean,
)

data class InstalledModel(val name: String, val size: Long, val params: String, val quant: String)

data class LoadedModel(val name: String, val size: Long, val vram: Long)

data class Pull(val name: String, val status: String, val fraction: Float?)

data class ModelsState(
    val installed: List<InstalledModel> = emptyList(),
    val loaded: List<LoadedModel> = emptyList(),
    val default: String? = null,
)

data class Task(
    val id: String,
    val name: String,
    val prompt: String,
    val schedule: String,
    val enabled: Boolean,
    val next: String,
    val lastRun: Double?,
    val lastStatus: String?,
    val lastSummary: String?,
    val chat: String?,
)

// ── JSON helpers ──

fun JSONArray.objects(): List<JSONObject> = (0 until length()).map { getJSONObject(it) }

fun JSONArray.strings(): List<String> = (0 until length()).map { getString(it) }

/** optString turns JSON null into the text "null"; this returns a real null instead. */
fun JSONObject.str(key: String): String? = if (isNull(key)) null else optString(key)

fun JSONObject.dbl(key: String): Double? = if (isNull(key)) null else optDouble(key)

fun parseChats(text: String): List<ChatSummary> = JSONArray(text).objects().map {
    ChatSummary(
        id = it.getString("id"),
        title = it.optString("title", "Untitled"),
        updated = it.optDouble("updated", 0.0),
        pinned = it.optBoolean("pinned"),
        scheduled = it.optBoolean("scheduled"),
        running = it.optBoolean("running"),
    )
}

fun parseItem(o: JSONObject): Item? = when (o.optString("role")) {
    "user" -> UserItem(
        text = o.optString("text"),
        files = o.optJSONArray("files")?.strings() ?: emptyList(),
        scheduled = o.str("scheduled"),
    )
    "assistant" -> AssistantItem(o.optString("text"), o.optString("thinking"))
    "tool" -> ToolItem(o.optString("label"), o.optString("summary"), o.optString("status"), o.optString("output"))
    else -> null
}

fun parseChat(text: String): ChatDetail {
    val o = JSONObject(text)
    return ChatDetail(
        id = o.getString("id"),
        title = o.str("title") ?: "Untitled",
        model = o.str("model"),
        running = o.optBoolean("running"),
        items = o.getJSONArray("items").objects().mapNotNull { parseItem(it) },
    )
}

fun parseApproval(o: JSONObject) = Approval(
    id = o.getString("id"),
    chat = o.optString("chat"),
    label = o.optString("label"),
    body = o.optString("body"),
    detail = o.optString("detail"),
    destructive = o.optBoolean("destructive"),
)

fun parseModels(text: String): Pair<ModelsState, List<Pull>> {
    val o = JSONObject(text)
    val state = ModelsState(
        installed = o.getJSONArray("installed").objects().map {
            InstalledModel(it.getString("name"), it.optLong("size"), it.optString("params"), it.optString("quant"))
        },
        loaded = o.getJSONArray("loaded").objects().map {
            LoadedModel(it.optString("name"), it.optLong("size"), it.optLong("vram"))
        },
        default = o.str("default"),
    )
    val pulls = o.optJSONArray("pulling")?.objects()?.map { parsePull(it) } ?: emptyList()
    return state to pulls
}

fun parsePull(o: JSONObject) = Pull(
    name = o.optString("name"),
    status = o.optString("status"),
    fraction = o.dbl("fraction")?.toFloat(),
)

fun parseTasks(text: String): List<Task> = JSONArray(text).objects().map {
    Task(
        id = it.getString("id"),
        name = it.optString("name"),
        prompt = it.optString("prompt"),
        schedule = it.optString("schedule"),
        enabled = it.optBoolean("enabled", true),
        next = it.optString("next"),
        lastRun = it.dbl("last_run"),
        lastStatus = it.str("last_status"),
        lastSummary = it.str("last_summary"),
        chat = it.str("chat"),
    )
}
