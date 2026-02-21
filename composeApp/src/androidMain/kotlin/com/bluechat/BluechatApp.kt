package com.bluechat

import android.app.Application
import com.bluechat.di.androidModule
import com.bluechat.di.sharedModule
import org.koin.android.ext.koin.androidContext
import org.koin.core.context.startKoin

class BluechatApp : Application() {
    override fun onCreate() {
        super.onCreate()
        startKoin {
            androidContext(this@BluechatApp)
            modules(androidModule, sharedModule)
        }
    }
}
