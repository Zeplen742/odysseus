package dev.odysseus.app

import java.util.concurrent.TimeUnit
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.job
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray

/**
 * The few server calls the native side makes on its own, outside the web page.
 * It signs in with the web page's own session cookie (read from the WebView's
 * cookie store on every request), so there is no separate token or pairing.
 */
class OdysseusApi(baseUrl: String, private val cookies: () -> String?) {
    private val base = baseUrl.trim().trimEnd('/')

    // The server sends a heartbeat comment every ~10s while a run is live, so a
    // 45s read timeout reliably detects a dead link (phone slept, tunnel dropped)
    // instead of hanging on a half-open socket.
    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(45, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()

    private fun req(path: String): Request.Builder {
        val b = Request.Builder().url(base + path)
        cookies()?.takeIf { it.isNotBlank() }?.let { b.header("Cookie", it) }
        return b
    }

    private suspend fun <T> call(r: Request, parse: (String) -> T): T = withContext(Dispatchers.IO) {
        val c = client.newCall(r)
        coroutineContext.job.invokeOnCompletion { c.cancel() }
        c.execute().use { resp ->
            val body = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) throw HttpException(resp.code, body.take(200))
            parse(body)
        }
    }

    /** The chat's name for the notification title; blank when it can't be read. */
    suspend fun sessionName(id: String): String = try {
        call(req("/api/sessions").build()) { body ->
            val arr = JSONArray(body)
            (0 until arr.length()).map { arr.getJSONObject(it) }
                .firstOrNull { it.optString("id") == id }?.optString("name").orEmpty()
        }
    } catch (_: Exception) { "" }

    /** True when the server has a live (or just-finished, still buffered) run. */
    suspend fun hasActiveRun(id: String): Boolean = try {
        call(req("/api/chat/stream_status/$id").build()) { true }
    } catch (e: HttpException) {
        if (e.code == 404) false else throw e
    }

    /** One resume connection, shaped for [TurnRunner]. Blocks until the stream ends. */
    suspend fun openResume(session: String, lastId: Long, onEvent: (SseEvent) -> Unit) =
        withContext(Dispatchers.IO) {
            val r = req("/api/chat/resume/$session?ids=1" + if (lastId >= 0) "&after=$lastId" else "").build()
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
