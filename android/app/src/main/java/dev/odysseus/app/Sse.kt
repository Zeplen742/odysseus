package dev.odysseus.app

/** One dispatched server-sent event. [id] is the server's replay sequence number. */
data class SseEvent(val id: Long?, val data: String)

/**
 * Minimal line-oriented SSE parser. Feed it one line at a time (without the
 * trailing newline); it returns an event when a blank line terminates one.
 * Comment lines (": heartbeat") are ignored but still prove the link is alive,
 * because the caller's read timeout is reset by any bytes arriving.
 */
class SseParser {
    private var id: Long? = null
    private val data = StringBuilder()
    private var hasData = false

    fun feed(line: String): SseEvent? {
        if (line.isEmpty()) {
            if (!hasData) { id = null; return null }
            val ev = SseEvent(id, data.toString())
            id = null; data.setLength(0); hasData = false
            return ev
        }
        if (line.startsWith(":")) return null
        val colon = line.indexOf(':')
        val field = if (colon < 0) line else line.substring(0, colon)
        var value = if (colon < 0) "" else line.substring(colon + 1)
        if (value.startsWith(" ")) value = value.substring(1)
        when (field) {
            "id" -> id = value.trim().toLongOrNull()
            "data" -> { if (hasData) data.append('\n'); data.append(value); hasData = true }
        }
        return null
    }
}
