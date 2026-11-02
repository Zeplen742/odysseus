package dev.odysseus.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NotificationTextTest {
    @Test fun doneUsesSessionNameAndFallsBackToAppName() {
        assertEquals("Trip plan" to "Response ready", NotificationText.forOutcome(TurnOutcome.Done, "Trip plan"))
        assertEquals("Odysseus", NotificationText.forOutcome(TurnOutcome.Done, " ")!!.first)
    }

    @Test fun failureIncludesReason() {
        val (_, text) = NotificationText.forOutcome(TurnOutcome.Failed("offline"), "x")!!
        assertTrue(text.contains("offline"))
    }

    @Test fun staysSilentOnlyWhenLookingAtThatChat() {
        assertFalse(NotificationText.shouldNotify(appVisible = true, chatScreenOpen = true, viewingThisSession = true))
        assertTrue(NotificationText.shouldNotify(appVisible = false, chatScreenOpen = true, viewingThisSession = true))
        assertTrue(NotificationText.shouldNotify(appVisible = true, chatScreenOpen = false, viewingThisSession = true))
        assertTrue(NotificationText.shouldNotify(appVisible = true, chatScreenOpen = true, viewingThisSession = false))
    }
}
