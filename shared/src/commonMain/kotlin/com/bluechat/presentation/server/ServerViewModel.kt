package com.bluechat.presentation.server

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.bluechat.domain.model.ServerState
import com.bluechat.domain.repository.BlePeripheralServer
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn

class ServerViewModel(
    private val server: BlePeripheralServer,
) : ViewModel() {

    val state = combine(
        server.serverState,
        server.connectedClients,
        server.lastError,
    ) { serverState, clients, error ->
        ServerUiState(
            serverState = serverState,
            connectedClients = clients,
            isAdvertising = serverState == ServerState.Advertising,
            // Report the actual reason rather than a generic "Failed to start server".
            errorMessage = error ?: if (serverState == ServerState.Error) {
                "Failed to start server"
            } else {
                null
            },
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), ServerUiState())

    fun startServer() {
        server.startAdvertising()
    }

    fun stopServer() {
        server.stopAdvertising()
    }

    // NOTE: deliberately no release() here. BlePeripheralServer is an application-scoped
    // singleton; releasing it when this screen's ViewModel is cleared killed its coroutine
    // scope for the rest of the process.
}
