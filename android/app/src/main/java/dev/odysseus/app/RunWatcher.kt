package dev.odysseus.app

import android.content.Context
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Follows a reply the web page started, natively, so it is still tracked when the
 * page is frozen (screen off, app in the background) and a "Response ready"
 * notification can fire when it finishes.
 *
 * The run itself lives on the server (detached); this only subscribes to it via
 * /api/chat/resume with offset ids, alongside the page's own connection.
 */
object RunWatcher {
    private val following: MutableSet<String> = ConcurrentHashMap.newKeySet()

    /** The chat the page shows right now (its URL hash), or null. */
    @Volatile var viewingSession: String? = null

    fun follow(ctx: Context, api: OdysseusApi, session: String) {
        if (!Urls.isSessionId(session)) return
        if (!following.add(session)) return
        val app = ctx.applicationContext
        TurnHost.begin(app)
        TurnHost.scope.launch {
            try {
                // The page tells us just before its request reaches the server, so
                // give the run a few seconds to appear before deciding there is none.
                var live = false
                for (i in 0 until 20) {
                    live = try { api.hasActiveRun(session) } catch (_: Exception) { false }
                    if (live) break
                    delay(500)
                }
                if (!live) return@launch

                val runner = TurnRunner(
                    open = { _, lastId, onEvent -> api.openResume(session, lastId, onEvent) },
                    maxAttempts = 30,
                    sleep = { TurnHost.sleepUntilNetworkOr(it) },
                )
                val outcome = runner.run(resumeOnly = true, onDelta = {}, onStatus = {})
                if (NotificationText.shouldNotify(TurnHost.appVisible, true, viewingSession == session)) {
                    NotificationText.forOutcome(outcome, api.sessionName(session))?.let { (title, text) ->
                        Notifier.finished(app, session, title, text)
                    }
                }
            } finally {
                following.remove(session)
                TurnHost.end(app)
            }
        }
    }
}
