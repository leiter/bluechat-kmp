package com.bluechat.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import com.bluechat.MainActivity
import com.bluechat.domain.repository.BleController
import com.bluechat.domain.repository.BlePeripheralServer
import com.bluechat.domain.session.BleSession
import com.bluechat.domain.session.BleSessionMonitor
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import org.koin.android.ext.android.inject

/**
 * Keeps the BLE session alive while the app is not on screen.
 *
 * Everything that does the actual work -- the GATT server, the central controller, the inbound
 * message coordinator -- are application-scoped Koin singletons and were already process-wide.
 * What they lacked was a reason for Android to keep the process running and to keep granting
 * them the radio: an app with no foreground component is frozen by App Standby and loses the
 * ability to scan, advertise or hold a GATT connection. That is what this service provides.
 *
 * It owns no BLE state of its own. It watches [BleSessionMonitor], mirrors that into an ongoing
 * notification, and shuts itself down the moment the session goes idle, so the notification is
 * never showing when nothing is actually connected.
 */
class BluechatService : Service() {

    private val monitor: BleSessionMonitor by inject()
    private val server: BlePeripheralServer by inject()
    private val bleController: BleController by inject()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var observing = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP_SESSION) {
            stopSession()
            return START_NOT_STICKY
        }

        // Must happen before this call returns, or the system kills us with an ANR-style
        // "did not call startForeground()" crash.
        startForegroundCompat(notificationFor(monitor.session.value))

        if (!observing) {
            observing = true
            // No distinctUntilChanged needed: StateFlow already conflates equal values, and
            // BleSession is a data class, so only real changes get here.
            monitor.session
                .onEach(::onSessionChanged)
                .launchIn(scope)
        }

        // START_STICKY would have Android restart us with a null intent after a process kill,
        // but the BLE session itself does not survive that -- the GATT server and any
        // connection are gone. Restarting would only put up a notification for a session that
        // no longer exists, so we stay down and let the user reconnect.
        return START_NOT_STICKY
    }

    private fun onSessionChanged(session: BleSession) {
        if (!session.isActive) {
            // Nothing left to keep alive. Tear down rather than leave a stale notification.
            ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
            stopSelf()
            return
        }
        notificationManager().notify(NOTIFICATION_ID, notificationFor(session))
    }

    /** Stops both roles, which drives [BleSessionMonitor] to inactive and ends this service. */
    private fun stopSession() {
        server.stopAdvertising()
        scope.launch {
            runCatching { bleController.disconnect() }
            ServiceCompat.stopForeground(this@BluechatService, ServiceCompat.STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private fun startForegroundCompat(notification: Notification) {
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
        } else {
            0
        }
        ServiceCompat.startForeground(this, NOTIFICATION_ID, notification, type)
    }

    private fun notificationFor(session: BleSession): Notification {
        val contentIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val stopIntent = PendingIntent.getService(
            this,
            1,
            Intent(this, BluechatService::class.java).setAction(ACTION_STOP_SESSION),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
            .setContentTitle("Bluechat is active")
            .setContentText(describe(session))
            .setContentIntent(contentIntent)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Stop", stopIntent)
            .setOngoing(true)
            .setSilent(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setShowWhen(false)
            .build()
    }

    private fun describe(session: BleSession): String {
        val parts = buildList {
            if (session.isServerActive) {
                add(
                    when (val clients = session.connectedClientCount) {
                        0 -> "Discoverable, waiting for others"
                        1 -> "Discoverable, 1 connected"
                        else -> "Discoverable, $clients connected"
                    }
                )
            }
            if (session.isClientConnected) {
                val peer = session.clientPeerName ?: session.clientTransportId
                add(if (peer != null) "Connected to $peer" else "Connected")
            }
        }
        return parts.joinToString(" / ").ifEmpty { "Idle" }
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Active session",
            // Low: this notification is a status indicator the system obliges us to show, not
            // something worth interrupting the user for. Messages use their own channel.
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = "Shown while Bluechat is advertising or connected"
            setShowBadge(false)
        }
        notificationManager().createNotificationChannel(channel)
    }

    private fun notificationManager() =
        getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

    companion object {
        private const val CHANNEL_ID = "bluechat_session"
        private const val NOTIFICATION_ID = 1
        const val ACTION_STOP_SESSION = "com.bluechat.action.STOP_SESSION"
    }
}
