package com.bluechat

import android.app.Application
import com.bluechat.di.androidModule
import com.bluechat.di.sharedModule
import com.bluechat.domain.messaging.IncomingMessageCoordinator
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
    }
}
