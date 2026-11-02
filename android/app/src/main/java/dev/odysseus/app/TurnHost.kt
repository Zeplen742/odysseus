package dev.odysseus.app

import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.Network
import androidx.core.content.ContextCompat
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Process-wide home for in-flight chat turns.
 *
 * Turns run in [scope], not in a ViewModel, so they survive the activity being
 * destroyed (swiped from recents) as long as the foreground service keeps the
 * process alive. While at least one turn is active the service is running and
 * a network callback wakes the reconnect loop as soon as connectivity returns.
 */
object TurnHost {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    /** Set by MainActivity onStart/onStop. */
    @Volatile var appVisible = false

    private val active = AtomicInteger(0)
    private val networkUp = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    private var callback: ConnectivityManager.NetworkCallback? = null

    fun begin(ctx: Context) {
        val app = ctx.applicationContext
        if (active.getAndIncrement() != 0) return
        Notifier.ensureChannels(app)
        // Only allowed while the app is visible, which is always true when a turn starts.
        try { ContextCompat.startForegroundService(app, Intent(app, StreamService::class.java)) }
        catch (_: Exception) { /* still works while visible; just no background protection */ }
        watchNetwork(app)
    }

    fun end(ctx: Context) {
        val app = ctx.applicationContext
        if (active.decrementAndGet() > 0) return
        active.set(0)
        app.stopService(Intent(app, StreamService::class.java))
        unwatchNetwork(app)
    }

    /** Reconnect backoff that ends early when the network comes back. */
    suspend fun sleepUntilNetworkOr(ms: Long) {
        withTimeoutOrNull(ms) { networkUp.first() }
    }

    @Synchronized private fun watchNetwork(app: Context) {
        if (callback != null) return
        val cm = app.getSystemService(ConnectivityManager::class.java) ?: return
        val cb = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) { networkUp.tryEmit(Unit) }
        }
        try { cm.registerDefaultNetworkCallback(cb); callback = cb } catch (_: Exception) {}
    }

    @Synchronized private fun unwatchNetwork(app: Context) {
        val cb = callback ?: return
        callback = null
        try { app.getSystemService(ConnectivityManager::class.java)?.unregisterNetworkCallback(cb) } catch (_: Exception) {}
    }
}
