package com.bluechat.service

import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import com.bluechat.domain.session.BleSessionMonitor
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach

/**
 * Starts [BluechatService] as soon as a BLE session goes live, from anywhere in the app.
 *
 * Keeping this out of the ViewModels means neither Server Mode nor the device list has to know
 * that a service exists -- they start a session, and the session becomes a foreground service
 * on its own. The service stops itself when the session ends, so there is no matching stop
 * here; this only ever has to push the start.
 */
class SessionServiceLauncher(
    private val context: Context,
    private val monitor: BleSessionMonitor,
) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var started = false

    fun start() {
        if (started) return
        started = true

        monitor.session
            .map { it.isActive }
            .distinctUntilChanged()
            .onEach { active -> if (active) launchService() }
            .launchIn(scope)
    }

    private fun launchService() {
        val intent = Intent(context, BluechatService::class.java)
        // Every transition into an active session is the direct result of a tap -- starting the
        // server, or picking a device to connect to -- so the app is in the foreground and the
        // API 31+ background-start restriction does not apply. Guarded anyway, because losing
        // the service is far better than crashing the app if that ever stops being true.
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }.onFailure { Log.w(TAG, "Could not start the session service", it) }
    }

    private companion object {
        const val TAG = "SessionServiceLauncher"
    }
}
