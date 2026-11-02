package dev.odysseus.app

import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder

/**
 * Does no work itself. Its only job is to mark the process as a foreground
 * service while a response is streaming, so Android does not freeze or kill the
 * app the moment the screen turns off or the user switches away. The actual
 * network work runs in [TurnHost.scope].
 */
class StreamService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Notifier.ensureChannels(this)
        val n = Notifier.ongoing(this)
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(Notifier.ONGOING_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            startForeground(Notifier.ONGOING_ID, n)
        }
        return START_NOT_STICKY
    }
}
