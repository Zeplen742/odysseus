package dev.odysseus.app

import java.io.IOException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TurnRunnerTest {
    private fun delta(id: Long, text: String) = SseEvent(id, """{"delta":"$text"}""")
    private val done = { id: Long -> SseEvent(id, "[DONE]") }

    private fun runner(
        attempts: MutableList<Pair<Boolean, Long>>,
        maxAttempts: Int = 12,
        script: suspend (n: Int, first: Boolean, lastId: Long, emit: (SseEvent) -> Unit) -> Unit,
    ) = TurnRunner(
        open = { first, lastId, emit ->
            attempts += first to lastId
            script(attempts.size, first, lastId, emit)
        },
        maxAttempts = maxAttempts,
        sleep = {},
    )

    @Test fun completesOnFirstConnection() = runBlocking {
        val calls = mutableListOf<Pair<Boolean, Long>>()
        val text = StringBuilder()
        val r = runner(calls) { _, _, _, emit -> emit(delta(0, "he")); emit(delta(1, "llo")); emit(done(2)) }
        assertEquals(TurnOutcome.Done, r.run(onDelta = { text.append(it) }, onStatus = {}))
        assertEquals("hello", text.toString())
        assertEquals(1, calls.size)
    }

    @Test fun resumesFromLastIdAfterDropWithoutDuplicates() = runBlocking {
        val calls = mutableListOf<Pair<Boolean, Long>>()
        val text = StringBuilder()
        val r = runner(calls) { n, _, lastId, emit ->
            if (n == 1) { emit(delta(0, "a")); emit(delta(1, "b")); throw IOException("socket closed") }
            // server honours `after`, but also replay an old event to prove client-side de-dup
            emit(delta(1, "b")); assertEquals(1L, lastId); emit(delta(2, "c")); emit(done(3))
        }
        assertEquals(TurnOutcome.Done, r.run(onDelta = { text.append(it) }, onStatus = {}))
        assertEquals("abc", text.toString())
        assertEquals(listOf(true to -1L, false to 1L), calls)   // second attempt is a resume, never a re-POST
    }

    @Test fun cleanEofWithoutDoneIsTreatedAsDrop() = runBlocking {
        val calls = mutableListOf<Pair<Boolean, Long>>()
        val r = runner(calls) { n, _, _, emit -> if (n == 1) emit(delta(0, "x")) else emit(done(1)) }
        assertEquals(TurnOutcome.Done, r.run(onDelta = {}, onStatus = {}))
        assertEquals(2, calls.size)
    }

    @Test fun resumeNotFoundMeansRunGone() = runBlocking {
        val r = runner(mutableListOf()) { n, _, _, emit ->
            if (n == 1) { emit(delta(0, "x")); throw IOException("drop") } else throw HttpException(404, "none")
        }
        assertEquals(TurnOutcome.RunGone, r.run(onDelta = {}, onStatus = {}))
    }

    @Test fun serverErrorEventFailsWithoutRetry() = runBlocking {
        val calls = mutableListOf<Pair<Boolean, Long>>()
        val r = runner(calls) { _, _, _, emit -> emit(SseEvent(0, """{"error":"model offline"}""")) }
        assertEquals(TurnOutcome.Failed("model offline"), r.run(onDelta = {}, onStatus = {}))
        assertEquals(1, calls.size)
    }

    @Test fun authFailureDoesNotRetry() = runBlocking {
        val calls = mutableListOf<Pair<Boolean, Long>>()
        val r = runner(calls) { _, _, _, _ -> throw HttpException(401, "no") }
        assertTrue(r.run(onDelta = {}, onStatus = {}) is TurnOutcome.Failed)
        assertEquals(1, calls.size)
    }

    @Test fun givesUpAfterMaxAttempts() = runBlocking {
        val calls = mutableListOf<Pair<Boolean, Long>>()
        val statuses = mutableListOf<String?>()
        val r = runner(calls, maxAttempts = 3) { _, _, _, _ -> throw IOException("offline") }
        assertTrue(r.run(onDelta = {}, onStatus = { statuses += it }) is TurnOutcome.Failed)
        assertEquals(4, calls.size)
        assertTrue(statuses.contains("Reconnecting…"))
    }

    @Test fun resumeOnlyNeverPosts() = runBlocking {
        val calls = mutableListOf<Pair<Boolean, Long>>()
        val r = runner(calls) { _, _, _, emit -> emit(done(0)) }
        r.run(resumeOnly = true, onDelta = {}, onStatus = {})
        assertEquals(listOf(false to -1L), calls)
    }
}
