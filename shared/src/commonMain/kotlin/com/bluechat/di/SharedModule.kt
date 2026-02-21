package com.bluechat.di

import com.bluechat.data.ble.KableCentralController
import com.bluechat.data.db.ChatDatabase
import com.bluechat.data.db.createDatabase
import com.bluechat.data.repository.ChatRepositoryImpl
import com.bluechat.domain.repository.BleController
import com.bluechat.domain.repository.ChatRepository
import com.bluechat.presentation.chat.ChatViewModel
import com.bluechat.presentation.device.DeviceViewModel
import com.bluechat.presentation.server.BroadcastChatViewModel
import com.bluechat.presentation.server.ServerChatViewModel
import com.bluechat.presentation.server.ServerViewModel
import com.bluechat.presentation.settings.SettingsViewModel
import org.koin.core.module.dsl.singleOf
import org.koin.core.module.dsl.viewModel
import org.koin.core.module.dsl.viewModelOf
import org.koin.dsl.bind
import org.koin.dsl.module

val sharedModule = module {
    single { createDatabase(get()) }
    single { get<ChatDatabase>().chatMessageDao() }
    singleOf(::ChatRepositoryImpl) bind ChatRepository::class
    singleOf(::KableCentralController) bind BleController::class

    viewModelOf(::DeviceViewModel)
    viewModel { params -> ChatViewModel(params.get(), get(), get(), get()) }
    viewModelOf(::ServerViewModel)
    viewModel { params -> ServerChatViewModel(params.get(), get(), get(), get()) }
    viewModelOf(::BroadcastChatViewModel)
    viewModelOf(::SettingsViewModel)
}
