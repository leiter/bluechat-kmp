package com.bluechat

import com.bluechat.di.iosModule
import com.bluechat.di.sharedModule
import org.koin.core.context.startKoin

fun initKoin() {
    startKoin {
        modules(iosModule, sharedModule)
    }
}
