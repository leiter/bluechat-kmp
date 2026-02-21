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
    ) { serverState, clients ->
        ServerUiState(
            serverState = serverState,
            connectedClients = clients,
            isAdvertising = serverState == ServerState.Advertising,
            errorMessage = if (serverState == ServerState.Error) "Failed to start server" else null,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), ServerUiState())

    fun startServer() {
        server.startAdvertising()
    }

    fun stopServer() {
        server.stopAdvertising()
    }

    override fun onCleared() {
        super.onCleared()
        server.release()
    }
}
