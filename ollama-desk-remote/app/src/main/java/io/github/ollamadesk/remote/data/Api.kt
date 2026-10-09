package io.github.ollamadesk.remote.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.IOException
import java.security.MessageDigest
import java.security.SecureRandom
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager

/** The server answered, but said no. [message] is written for people and can be shown as is. */
class ApiError(val code: Int, override val message: String) : Exception(message)

private val JSON_TYPE = "application/json; charset=utf-8".toMediaType()
private val WAV_TYPE = "audio/wav".toMediaType()

/**
 * An HTTPS client that trusts exactly one certificate: the one whose SHA-256 fingerprint came from the
 * computer's QR code. No certificate authority is involved, so nobody else on the network can pose as the
 * computer, and host names don't matter (the computer is reached by IP address).
 */
fun pinnedClient(fingerprint: String, readTimeoutSeconds: Long = 30): OkHttpClient {
    val expected = fingerprint.lowercase().toByteArray()
    val trustManager = object : X509TrustManager {
        override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) {
            throw CertificateException("Client certificates aren't used")
        }

        override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {
            val leaf = chain?.firstOrNull() ?: throw CertificateException("The computer sent no certificate")
            val actual = MessageDigest.getInstance("SHA-256").digest(leaf.encoded)
                .joinToString("") { "%02x".format(it) }.toByteArray()
            if (!MessageDigest.isEqual(actual, expected)) {
                throw CertificateException("This isn't the computer you paired with (its certificate changed)")
            }
        }

        override fun getAcceptedIssuers(): Array<X509Certificate> = arrayOf()
    }
    val context = SSLContext.getInstance("TLS")
    context.init(null, arrayOf<TrustManager>(trustManager), SecureRandom())
    return OkHttpClient.Builder()
        .sslSocketFactory(context.socketFactory, trustManager)
        .hostnameVerifier { _, _ -> true } // safe: the certificate itself is pinned above
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(readTimeoutSeconds, TimeUnit.SECONDS)
        .build()
}

/** Turns low-level failures into something a person can act on. */
fun friendly(e: Throwable): String = when {
    e is ApiError -> e.message
    e.message?.contains("certificate changed") == true -> "This isn't the computer you paired with. Pair again."
    e is java.net.SocketTimeoutException || e is java.net.ConnectException || e is java.net.NoRouteToHostException ->
        "Can't reach the computer. Is it on, with phone access turned on, and is the phone on the same network?"
    e is java.net.UnknownHostException -> "Can't find the computer on this network."
    e is javax.net.ssl.SSLException -> "Secure connection failed: ${e.message ?: "unknown reason"}"
    else -> e.message ?: e.javaClass.simpleName
}

class Api(private val server: Server) {
    private val client = pinnedClient(server.fingerprint)
    private val streamClient = client.newBuilder().readTimeout(45, TimeUnit.SECONDS).build() // server pings every 15 s
    private val voiceClient = client.newBuilder().readTimeout(180, TimeUnit.SECONDS).build() // slow CPUs, long clips

    @Volatile
    private var activeHost: String = server.hosts.first()

    private fun url(host: String, path: String) = "https://$host:${server.port}$path"

    private fun execute(client: OkHttpClient, host: String, method: String, path: String, body: RequestBody?): ByteArray {
        val requestBody = body ?: if (method == "POST") "{}".toRequestBody(JSON_TYPE) else null
        val request = Request.Builder()
            .url(url(host, path))
            .header("Authorization", "Bearer ${server.token}")
            .method(method, requestBody)
            .build()
        client.newCall(request).execute().use { response ->
            val bytes = response.body?.bytes() ?: ByteArray(0)
            if (!response.isSuccessful) {
                val message = runCatching { JSONObject(bytes.decodeToString()).getString("error") }.getOrNull()
                    ?: "Error ${response.code}"
                throw ApiError(response.code, message)
            }
            return bytes
        }
    }

    /** Tries the address that worked last, then the computer's other addresses. */
    private suspend fun transfer(
        method: String, path: String, body: RequestBody?, client: OkHttpClient = this.client,
    ): ByteArray = withContext(Dispatchers.IO) {
        var last: IOException? = null
        val hosts = listOf(activeHost) + server.hosts.filter { it != activeHost }
        for (host in hosts) {
            try {
                val result = execute(client, host, method, path, body)
                activeHost = host
                return@withContext result
            } catch (e: IOException) {
                last = e
            }
        }
        throw last ?: IOException("No address for the computer")
    }

    suspend fun request(method: String, path: String, body: JSONObject? = null): String =
        transfer(method, path, body?.toString()?.toRequestBody(JSON_TYPE)).decodeToString()

    /** Speech → text on the computer. [wav] is 16 kHz mono 16-bit audio. */
    suspend fun transcribe(wav: ByteArray): String = JSONObject(
        transfer("POST", "/api/voice/transcribe", wav.toRequestBody(WAV_TYPE), voiceClient).decodeToString()
    ).getString("text")

    /** Text → speech on the computer; returns a WAV file. */
    suspend fun speak(text: String): ByteArray = transfer(
        "POST", "/api/voice/speak", JSONObject().put("text", text).toString().toRequestBody(JSON_TYPE), voiceClient,
    )

    suspend fun voiceReady(): Boolean = JSONObject(request("GET", "/api/info")).optBoolean("voice")

    suspend fun chats() = parseChats(request("GET", "/api/chats"))
    suspend fun chat(id: String) = parseChat(request("GET", "/api/chats/$id"))
    suspend fun deleteChat(id: String) = request("DELETE", "/api/chats/$id")
    suspend fun stop(id: String) = request("POST", "/api/chats/$id/stop")

    suspend fun newChat(text: String, model: String?, agent: Boolean): String =
        JSONObject(request("POST", "/api/chats", message(text, model, agent))).getString("id")

    suspend fun send(id: String, text: String, model: String?, agent: Boolean) {
        request("POST", "/api/chats/$id/messages", message(text, model, agent))
    }

    private fun message(text: String, model: String?, agent: Boolean) = JSONObject()
        .put("text", text)
        .put("model", model ?: JSONObject.NULL)
        .put("agent", agent)

    suspend fun answer(approval: String, allow: Boolean) {
        request("POST", "/api/approvals/$approval", JSONObject().put("allow", allow))
    }

    suspend fun models() = parseModels(request("GET", "/api/models"))
    suspend fun modelAction(action: String, name: String) {
        request("POST", "/api/models/$action", JSONObject().put("name", name))
    }

    suspend fun tasks() = parseTasks(request("GET", "/api/tasks"))
    suspend fun runTask(id: String) = request("POST", "/api/tasks/$id/run")
    suspend fun setTaskEnabled(id: String, enabled: Boolean) {
        request("POST", "/api/tasks/$id/enabled", JSONObject().put("enabled", enabled))
    }

    /** Reads the server's event stream until it ends, the connection drops, or the coroutine is cancelled. */
    suspend fun events(onEvent: suspend (JSONObject) -> Unit) = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url(url(activeHost, "/api/events"))
            .header("Authorization", "Bearer ${server.token}")
            .header("Accept", "text/event-stream")
            .build()
        val call = streamClient.newCall(request)
        val handle = coroutineContext[Job]?.invokeOnCompletion { call.cancel() }
        try {
            call.execute().use { response ->
                if (!response.isSuccessful) {
                    val text = response.body?.string().orEmpty()
                    val message = runCatching { JSONObject(text).getString("error") }.getOrNull() ?: "Error ${response.code}"
                    throw ApiError(response.code, message)
                }
                val source = response.body?.source() ?: return@use
                while (isActive) {
                    val line = source.readUtf8Line() ?: break
                    if (line.startsWith("data: ")) {
                        val event = runCatching { JSONObject(line.substring(6)) }.getOrNull()
                        if (event != null) onEvent(event)
                    }
                }
            }
        } finally {
            handle?.dispose()
        }
    }

    companion object {
        /** Redeems the one-time code from the QR code for a long-lived access token. */
        suspend fun pair(link: PairingLink, deviceName: String): Server = withContext(Dispatchers.IO) {
            val client = pinnedClient(link.fingerprint)
            var last: IOException? = null
            for (host in link.hosts) {
                try {
                    val body = JSONObject().put("secret", link.secret).put("name", deviceName).toString()
                        .toRequestBody(JSON_TYPE)
                    val request = Request.Builder().url("https://$host:${link.port}/pair").post(body).build()
                    client.newCall(request).execute().use { response ->
                        val text = response.body?.string().orEmpty()
                        val json = runCatching { JSONObject(text) }.getOrNull()
                        if (!response.isSuccessful || json == null) {
                            throw ApiError(response.code, json?.optString("error") ?: "Error ${response.code}")
                        }
                        return@withContext Server(
                            hosts = listOf(host) + link.hosts.filter { it != host },
                            port = link.port,
                            fingerprint = link.fingerprint,
                            token = json.getString("token"),
                            name = json.optString("host", link.name),
                        )
                    }
                } catch (e: IOException) {
                    last = e
                }
            }
            throw last ?: IOException("The code contains no address")
        }
    }
}
