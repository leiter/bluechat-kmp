package com.bluechat.di

import com.bluechat.data.ble.IosBlePeripheralServer
import com.bluechat.data.db.getDatabaseBuilder
import com.bluechat.data.preferences.IosAppPreferences
import com.bluechat.domain.notification.IosMessageNotifier
import com.bluechat.domain.notification.MessageNotifier
import com.bluechat.domain.repository.AppPreferences
import com.bluechat.domain.repository.BlePeripheralServer
import org.koin.core.module.dsl.singleOf
import org.koin.dsl.bind
import org.koin.dsl.module

val iosModule = module {
    single { getDatabaseBuilder() }
    singleOf(::IosAppPreferences) bind AppPreferences::class
    singleOf(::IosBlePeripheralServer) bind BlePeripheralServer::class
    singleOf(::IosMessageNotifier) bind MessageNotifier::class
}
