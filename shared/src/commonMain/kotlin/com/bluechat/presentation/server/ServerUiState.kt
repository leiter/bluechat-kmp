package com.bluechat.presentation.server

import com.bluechat.domain.model.ConnectedClient
import com.bluechat.domain.model.ServerState

data class ServerUiState(
    val serverState: ServerState = ServerState.Idle,
    val connectedClients: List<ConnectedClient> = emptyList(),
    val isAdvertising: Boolean = false,
    val errorMessage: String? = null,
)
