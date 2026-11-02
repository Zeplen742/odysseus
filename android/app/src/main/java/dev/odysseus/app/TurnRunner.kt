package dev.odysseus.app

import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import org.json.JSONObject

class HttpException(val code: Int, message: String) : IOException(message)

sealed class TurnOutcome {
    /** The server finished the run and sent [DONE]. */
    object Done : TurnOutcome()
    /** The run is no longer buffered on the server; reload history for the result. */
    object RunGone : TurnOutcome()
    data class Failed(val message: String) : TurnOutcome()
}

/**
 * Drives one chat turn to completion across connection drops.
 *
 * [open] performs one connection attempt and streams events to the callback until
 * the connection ends. `first` is true only for the initial POST; every later
 * attempt must be a resume (never re-POST: that would send the message twice).
 * `lastId` is the last event id seen, so resume asks for only what was missed.
 */
class TurnRunner(
    private val open: suspend (first: Boolean, lastId: Long, onEvent: (SseEvent) -> Unit) -> Unit,
    private val maxAttempts: Int = 12,
    private val sleep: suspend (Long) -> Unit = { delay(it) },
) {
    var lastId: Long = -1L
        private set

    suspend fun run(
        resumeOnly: Boolean = false,
        onDelta: (String) -> Unit,
        onStatus: (String?) -> Unit,
    ): TurnOutcome {
        var first = !resumeOnly
        var attempt = 0
        var done = false
        var error: String? = null

        val handler: (SseEvent) -> Unit = handler@{ ev ->
            val id = ev.id
            if (id != null) {
                if (id <= lastId) return@handler          // already applied
                lastId = id
            }
            attempt = 0
            onStatus(null)
            if (ev.data == "[DONE]") { done = true; return@handler }
            val obj = try { JSONObject(ev.data) } catch (_: Exception) { return@handler }
            val err = obj.optString("error", "")
            if (err.isNotEmpty()) { error = err; return@handler }
            if (obj.has("delta") && !obj.isNull("delta")) {
                val text = obj.optString("delta", "")
                if (text.isNotEmpty()) onDelta(text)
            }
        }

        while (true) {
            try {
                open(first, lastId, handler)
            } catch (e: CancellationException) {
                throw e
            } catch (e: HttpException) {
                if (!first && e.code == 404) return TurnOutcome.RunGone
                val retryable = e.code >= 500 || e.code == 408 || e.code == 429
                if (!retryable) return TurnOutcome.Failed("Server returned HTTP ${e.code}")
            } catch (_: IOException) {
                // dropped / timed out / no network: fall through and resume
            }
            error?.let { return TurnOutcome.Failed(it) }
            if (done) return TurnOutcome.Done
            first = false
            attempt++
            if (attempt > maxAttempts) return TurnOutcome.Failed("Could not reconnect to the server")
            onStatus("Reconnecting…")
            sleep(minOf(1000L shl (attempt - 1).coerceAtMost(4), 15_000L))
        }
    }
}
