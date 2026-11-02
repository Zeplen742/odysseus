package dev.odysseus.app

/** Pure notification wording, kept free of Android types so it is unit-testable. */
object NotificationText {
    fun forOutcome(outcome: TurnOutcome, sessionName: String): Pair<String, String>? {
        val title = sessionName.ifBlank { "Odysseus" }
        return when (outcome) {
            TurnOutcome.Done -> title to "Response ready"
            TurnOutcome.RunGone -> title to "Finished while you were away. Tap to read it."
            is TurnOutcome.Failed -> title to "Lost connection: ${outcome.message}. Tap to reconnect."
        }
    }

    /** Notify unless the user is looking at this very chat right now. */
    fun shouldNotify(appVisible: Boolean, chatScreenOpen: Boolean, viewingThisSession: Boolean): Boolean =
        !(appVisible && chatScreenOpen && viewingThisSession)
}
