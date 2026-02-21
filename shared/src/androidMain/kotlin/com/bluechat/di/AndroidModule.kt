package com.bluechat.di

import com.bluechat.data.ble.AndroidBlePeripheralServer
import com.bluechat.data.db.getDatabaseBuilder
import com.bluechat.data.preferences.AndroidAppPreferences
import com.bluechat.domain.repository.AppPreferences
import com.bluechat.domain.repository.BlePeripheralServer
import org.koin.core.module.dsl.singleOf
import org.koin.dsl.bind
import org.koin.dsl.module

val androidModule = module {
    single { getDatabaseBuilder(get()) }
    singleOf(::AndroidBlePeripheralServer) bind BlePeripheralServer::class
    singleOf(::AndroidAppPreferences) bind AppPreferences::class
}
