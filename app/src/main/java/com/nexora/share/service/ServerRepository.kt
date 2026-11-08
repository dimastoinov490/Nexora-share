package com.nexora.share.service

import com.nexora.share.model.ServerState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

object ServerRepository {
    private val _serverState = MutableStateFlow(ServerState())
    val serverState: StateFlow<ServerState> = _serverState.asStateFlow()

    fun updateState(transform: (ServerState) -> ServerState) {
        _serverState.value = transform(_serverState.value)
    }

    fun setRunning(running: Boolean, ip: String = "", port: Int = 8080, error: String? = null) {
        _serverState.value = _serverState.value.copy(
            isRunning = running,
            ipAddress = ip,
            port = port,
            errorMessage = error
        )
    }

    fun setNetworkStatus(connected: Boolean, ip: String, networkName: String = "") {
        _serverState.value = _serverState.value.copy(
            isWifiConnected = connected,
            ipAddress = ip,
            networkName = networkName
        )
    }
}
