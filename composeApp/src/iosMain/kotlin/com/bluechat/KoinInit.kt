package com.bluechat

import com.bluechat.di.iosModule
import com.bluechat.di.sharedModule
import com.bluechat.domain.messaging.IncomingMessageCoordinator
import org.koin.core.context.startKoin

fun initKoin() {
    val koin = startKoin {
        modules(iosModule, sharedModule)
    }.koin

    // Must run for the whole process lifetime, not just while a chat screen is open,
    // otherwise inbound messages are dropped before anything can store them.
    koin.get<IncomingMessageCoordinator>().start()
}
