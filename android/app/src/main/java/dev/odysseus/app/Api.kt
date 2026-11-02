package dev.odysseus.app

import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.job
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.FormBody
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject

data class SessionInfo(val id: String, val name: String, val model: String, val updatedAt: String)
data class ChatMessage(val role: String, val content: String)

class OdysseusApi(baseUrl: String, private val token: String) {
    private val base = baseUrl.trim().trimEnd('/')

    // Server sends a heartbeat comment every ~10s while a run is live, so a 45s
    // read timeout reliably detects a dead link (phone slept, tunnel dropped)
    // instead of hanging on a half-open socket.
    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(45, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()

    private fun req(path: String) =
        Request.Builder().url(base + path).header("Authorization", "Bearer $token")

    private suspend fun <T> call(r: Request, parse: (String) -> T): T = withContext(Dispatchers.IO) {
        val c = client.newCall(r)
        coroutineContext.job.invokeOnCompletion { c.cancel() }
        c.execute().use { resp ->
            val body = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) throw HttpException(resp.code, body.take(200))
            parse(body)
        }
    }

    suspend fun ping() = call(req("/api/companion/ping").build()) { JSONObject(it).optBoolean("ok") }

    suspend fun listSessions(): List<SessionInfo> =
        call(req("/api/sessions").build()) { body ->
            val arr = JSONArray(body)
            (0 until arr.length()).map {
                val o = arr.getJSONObject(it)
                SessionInfo(o.getString("id"), o.optString("name"), o.optString("model"), o.optString("updated_at"))
            }
        }

    suspend fun history(id: String): List<ChatMessage> =
        call(req("/api/history/$id").build()) { body ->
            val arr = JSONObject(body).getJSONArray("history")
            (0 until arr.length()).mapNotNull {
                val o = arr.getJSONObject(it)
                val role = o.optString("role")
                if (role == "user" || role == "assistant") ChatMessage(role, o.optString("content")) else null
            }
        }

    /** True when the server still has a live (or just-finished, still buffered) run. */
    suspend fun hasActiveRun(id: String): Boolean = try {
        call(req("/api/chat/stream_status/$id").build()) { true }
    } catch (e: HttpException) {
        if (e.code == 404) false else throw e
    }

    /** Creates a session on the first enabled model the server offers this token. */
    suspend fun newSession(): String {
        val (endpointId, model) = call(req("/api/companion/models").build()) { body ->
            val eps = JSONObject(body).getJSONArray("endpoints")
            for (i in 0 until eps.length()) {
                val ep = eps.getJSONObject(i)
                val models = ep.optJSONArray("models")
                if (models != null && models.length() > 0) return@call ep.getString("endpoint_id") to models.getString(0)
            }
            throw IOException("No models available on the server")
        }
        val form = FormBody.Builder().add("endpoint_id", endpointId).add("model", model).build()
        return call(req("/api/session").post(form).build()) { JSONObject(it).getString("id") }
    }

    private fun chatRequest(session: String, message: String): Request {
        val json = JSONObject().put("message", message).put("session", session).toString()
        return req("/api/chat/stream?ids=1")
            .post(json.toRequestBody("application/json".toMediaType())).build()
    }

    private fun resumeRequest(session: String, after: Long): Request =
        req("/api/chat/resume/$session?ids=1" + if (after >= 0) "&after=$after" else "").build()

    /** One connection attempt, shaped for [TurnRunner]. Blocks until the stream ends. */
    suspend fun openTurn(
        session: String, message: String?, first: Boolean, lastId: Long, onEvent: (SseEvent) -> Unit,
    ) = withContext(Dispatchers.IO) {
        val r = if (first && message != null) chatRequest(session, message) else resumeRequest(session, lastId)
        val c: Call = client.newCall(r)
        coroutineContext.job.invokeOnCompletion { c.cancel() }
        c.execute().use { resp ->
            if (!resp.isSuccessful) throw HttpException(resp.code, resp.body?.string().orEmpty().take(200))
            val src = resp.body!!.source()
            val parser = SseParser()
            while (!src.exhausted()) {
                val line = src.readUtf8Line() ?: break
                parser.feed(line)?.let(onEvent)
            }
        }
    }
}
