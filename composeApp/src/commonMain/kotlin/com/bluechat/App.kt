package com.bluechat

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.bluechat.domain.repository.ThemePreference
import com.bluechat.domain.model.Conversation
import com.bluechat.presentation.chat.ChatScreen
import com.bluechat.presentation.contacts.ContactsScreen
import com.bluechat.presentation.contacts.ContactsViewModel
import com.bluechat.presentation.chat.ChatViewModel
import com.bluechat.presentation.conversations.ConversationsScreen
import com.bluechat.presentation.conversations.ConversationsViewModel
import com.bluechat.presentation.device.DeviceScreen
import com.bluechat.presentation.device.DeviceViewModel
import com.bluechat.presentation.server.BroadcastChatViewModel
import com.bluechat.presentation.server.ServerChatViewModel
import com.bluechat.presentation.server.ServerScreen
import com.bluechat.presentation.server.ServerViewModel
import com.bluechat.presentation.settings.SettingsScreen
import com.bluechat.presentation.settings.SettingsViewModel
import com.bluechat.ui.theme.BluechatTheme
import org.koin.compose.viewmodel.koinViewModel
import androidx.savedstate.read
import org.koin.core.parameter.parametersOf

@Composable
fun App() {
    val settingsViewModel = koinViewModel<SettingsViewModel>()
    val settingsState by settingsViewModel.state.collectAsStateWithLifecycle()

    val darkTheme = when (settingsState.themePreference) {
        ThemePreference.System -> isSystemInDarkTheme()
        ThemePreference.Light -> false
        ThemePreference.Dark -> true
    }

    BluechatTheme(darkTheme = darkTheme) {
        val navController = rememberNavController()

        // C2: the app opens on conversations, not on a Bluetooth scanner. Finding a device is
        // now a deliberate step ("New chat") rather than the first thing the app demands.
        NavHost(navController = navController, startDestination = "conversations") {
            composable("conversations") {
                val viewModel = koinViewModel<ConversationsViewModel>()
                val state by viewModel.state.collectAsStateWithLifecycle()

                ConversationsScreen(
                    state = state,
                    onConversationClicked = { conversation ->
                        // A conversation is history, so it opens whether or not the peer is in
                        // range. The chat screen is what reports the live connection state, and
                        // the outbox is what holds anything sent while disconnected.
                        when {
                            conversation.isGroup ->
                                navController.navigate("server/broadcast")

                            // A peer that dialled *us* is reachable only through the
                            // peripheral role, so it has to open the server's chat screen --
                            // keyed on the transport address, which is what that screen
                            // sends on. Opening the client screen here would render the
                            // history correctly and then fail every reply, because the
                            // central-role connection does not exist.
                            state.isServerSide(conversation) ->
                                navController.navigate("server/chat/${conversation.peerTransportId}")

                            else ->
                                navController.navigate("chat/${conversation.conversationId}")
                        }
                    },
                    onNewChat = { navController.navigate("devices") },
                    onTogglePinned = viewModel::togglePinned,
                    onToggleMuted = viewModel::toggleMuted,
                    onSetArchived = viewModel::setArchived,
                    onToggleShowArchived = viewModel::toggleShowArchived,
                    onContacts = { navController.navigate("contacts") },
                    onServerMode = { navController.navigate("server") },
                    onSettingsClicked = { navController.navigate("settings") },
                )
            }

            composable("devices") {
                val viewModel = koinViewModel<DeviceViewModel>()
                val state by viewModel.state.collectAsStateWithLifecycle()

                DeviceScreen(
                    state = state,
                    onStartScan = viewModel::startScan,
                    onStopScan = viewModel::stopScan,
                    onDeviceClicked = { deviceId ->
                        viewModel.connectToDevice(deviceId)
                        navController.navigate("chat/$deviceId")
                    },
                    onToggleFavourite = viewModel::toggleFavourite,
                    onStartServer = {
                        navController.navigate("server")
                    },
                    onSettingsClicked = {
                        navController.navigate("settings")
                    },
                    onBack = { navController.popBackStack() },
                )
            }

            composable("chat/{deviceId}") { backStackEntry ->
                val deviceId = backStackEntry.arguments?.read { getStringOrNull("deviceId") } ?: return@composable
                val viewModel = koinViewModel<ChatViewModel>(parameters = { parametersOf(deviceId) })
                val state by viewModel.state.collectAsStateWithLifecycle()

                ChatScreen(
                    state = state,
                    onSendMessage = viewModel::sendMessage,
                    onDisconnect = {
                        viewModel.disconnect()
                        navController.popBackStack()
                    },
                    onTextChanged = viewModel::onTextChanged,
                )
            }

            composable("server") {
                val viewModel = koinViewModel<ServerViewModel>()
                val state by viewModel.state.collectAsStateWithLifecycle()

                ServerScreen(
                    state = state,
                    onStartServer = viewModel::startServer,
                    onStopServer = viewModel::stopServer,
                    onClientClicked = { clientId ->
                        navController.navigate("server/chat/$clientId")
                    },
                    onGroupChatClicked = {
                        navController.navigate("server/broadcast")
                    },
                    // Leaving the screen deliberately does NOT stop the server: the
                    // foreground service keeps it advertising in the background. Stopping is
                    // now an explicit act, via the button here or the notification action.
                    onBack = { navController.popBackStack() },
                )
            }

            composable("server/chat/{clientId}") { backStackEntry ->
                val clientId = backStackEntry.arguments?.read { getStringOrNull("clientId") } ?: return@composable
                val viewModel = koinViewModel<ServerChatViewModel>(parameters = { parametersOf(clientId) })
                val state by viewModel.state.collectAsStateWithLifecycle()

                ChatScreen(
                    state = state,
                    onSendMessage = viewModel::sendMessage,
                    onDisconnect = {
                        viewModel.disconnect()
                        navController.popBackStack()
                    },
                )
            }

            composable("server/broadcast") {
                val viewModel = koinViewModel<BroadcastChatViewModel>()
                val state by viewModel.state.collectAsStateWithLifecycle()

                ChatScreen(
                    state = state,
                    onSendMessage = viewModel::sendMessage,
                    onDisconnect = {
                        viewModel.disconnect()
                        navController.popBackStack()
                    },
                )
            }

            composable("contacts") {
                val viewModel = koinViewModel<ContactsViewModel>()
                val state by viewModel.state.collectAsStateWithLifecycle()

                ContactsScreen(
                    state = state,
                    onQueryChanged = viewModel::onQueryChanged,
                    onRename = viewModel::rename,
                    onSetBlocked = viewModel::setBlocked,
                    onSetFavourite = viewModel::setFavourite,
                    onDelete = viewModel::delete,
                    onBack = { navController.popBackStack() },
                )
            }

            composable("settings") {
                SettingsScreen(
                    state = settingsState,
                    onDisplayNameChanged = settingsViewModel::updateDisplayName,
                    onThemeChanged = settingsViewModel::updateThemePreference,
                    onBack = { navController.popBackStack() },
                )
            }
        }
    }
}
