package com.bluechat

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.bluechat.domain.repository.ThemePreference
import com.bluechat.presentation.chat.ChatScreen
import com.bluechat.presentation.chat.ChatViewModel
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

        NavHost(navController = navController, startDestination = "devices") {
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
                    onStartServer = {
                        navController.navigate("server")
                    },
                    onSettingsClicked = {
                        navController.navigate("settings")
                    },
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
