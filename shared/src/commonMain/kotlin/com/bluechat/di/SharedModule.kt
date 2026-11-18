package com.bluechat.di

import com.bluechat.data.ble.KableCentralController
import com.bluechat.data.db.ChatDatabase
import com.bluechat.data.db.createDatabase
import com.bluechat.data.repository.ChatRepositoryImpl
import com.bluechat.data.repository.ContactRepositoryImpl
import com.bluechat.data.repository.ConversationRepositoryImpl
import com.bluechat.data.repository.MessageQueueRepositoryImpl
import com.bluechat.data.repository.PeerDirectoryImpl
import com.bluechat.domain.messaging.IncomingMessageCoordinator
import com.bluechat.domain.notification.ConversationVisibility
import com.bluechat.domain.presence.PresenceTracker
import com.bluechat.domain.repository.BleController
import com.bluechat.domain.repository.ChatRepository
import com.bluechat.domain.repository.ContactRepository
import com.bluechat.domain.repository.ConversationRepository
import com.bluechat.domain.repository.MessageQueueRepository
import com.bluechat.domain.repository.PeerDirectory
import com.bluechat.domain.session.BleSessionMonitor
import com.bluechat.presentation.chat.ChatViewModel
import com.bluechat.presentation.contacts.ContactsViewModel
import com.bluechat.presentation.conversations.ConversationsViewModel
import com.bluechat.presentation.device.DeviceViewModel
import com.bluechat.presentation.navigation.ConversationDeepLinks
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
    single { get<ChatDatabase>().pendingMessageDao() }
    single { get<ChatDatabase>().contactDao() }
    single { get<ChatDatabase>().conversationDao() }
    singleOf(::ChatRepositoryImpl) bind ChatRepository::class
    singleOf(::ContactRepositoryImpl) bind ContactRepository::class
    singleOf(::ConversationRepositoryImpl) bind ConversationRepository::class
    singleOf(::MessageQueueRepositoryImpl) bind MessageQueueRepository::class
    singleOf(::PeerDirectoryImpl) bind PeerDirectory::class
    singleOf(::KableCentralController) bind BleController::class
    // Spelled out: singleOf would also try to inject the test-only `scope` parameter.
    single {
        IncomingMessageCoordinator(
            server = get(),
            bleController = get(),
            chatRepository = get(),
            peerDirectory = get(),
            messageNotifier = get(),
            conversationRepository = get(),
            conversationVisibility = get(),
        )
    }
    singleOf(::ConversationVisibility)
    // Spelled out: singleOf would also try to inject the clock and tick parameters.
    single { PresenceTracker(bleController = get(), server = get(), contactRepository = get()) }
    singleOf(::ConversationDeepLinks)
    singleOf(::BleSessionMonitor)

    viewModelOf(::ContactsViewModel)
    viewModelOf(::ConversationsViewModel)
    viewModelOf(::DeviceViewModel)
    viewModel { params -> ChatViewModel(params.get(), get(), get(), get(), get(), get()) }
    viewModelOf(::ServerViewModel)
    viewModel { params -> ServerChatViewModel(params.get(), get(), get(), get(), get()) }
    viewModelOf(::BroadcastChatViewModel)
    viewModelOf(::SettingsViewModel)
}
