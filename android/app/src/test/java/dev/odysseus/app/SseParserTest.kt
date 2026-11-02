package dev.odysseus.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SseParserTest {
    private fun parse(vararg lines: String): List<SseEvent> {
        val p = SseParser()
        return lines.mapNotNull { p.feed(it) }
    }

    @Test fun eventWithId() {
        assertEquals(listOf(SseEvent(3, """{"delta":"hi"}""")), parse("id: 3", """data: {"delta":"hi"}""", ""))
    }

    @Test fun eventWithoutIdHasNullId() {
        assertEquals(listOf(SseEvent(null, "[DONE]")), parse("data: [DONE]", ""))
    }

    @Test fun commentsAndHeartbeatsAreIgnored() {
        assertEquals(emptyList<SseEvent>(), parse(": heartbeat 1", ""))
    }

    @Test fun idDoesNotLeakIntoNextEvent() {
        val out = parse("id: 1", "data: a", "", "data: b", "")
        assertEquals(listOf(SseEvent(1, "a"), SseEvent(null, "b")), out)
    }

    @Test fun multiLineDataIsJoined() {
        assertEquals(listOf(SseEvent(null, "a\nb")), parse("data: a", "data: b", ""))
    }

    @Test fun blankLineWithoutDataDispatchesNothing() {
        assertNull(SseParser().feed(""))
    }
}
