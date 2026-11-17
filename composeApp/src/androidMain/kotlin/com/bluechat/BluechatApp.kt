package com.bluechat

import android.app.Application
import com.bluechat.di.androidModule
import com.bluechat.di.sharedModule
import com.bluechat.domain.messaging.IncomingMessageCoordinator
import com.bluechat.domain.session.BleSessionMonitor
import com.bluechat.service.SessionServiceLauncher
import org.koin.android.ext.koin.androidContext
import org.koin.core.context.startKoin

class BluechatApp : Application() {
    override fun onCreate() {
        super.onCreate()
        val koin = startKoin {
            androidContext(this@BluechatApp)
            modules(androidModule, sharedModule)
        }.koin

        // Must run for the whole process lifetime, not just while a chat screen is open,
        // otherwise inbound messages are dropped before anything can store them.
        koin.get<IncomingMessageCoordinator>().start()

        // Promotes the process to a foreground service whenever a BLE session is live, so
        // messaging survives the app being backgrounded. Watches for the whole process
        // lifetime, because a session can be started from more than one screen.
        SessionServiceLauncher(this, koin.get<BleSessionMonitor>()).start()
    }
}
