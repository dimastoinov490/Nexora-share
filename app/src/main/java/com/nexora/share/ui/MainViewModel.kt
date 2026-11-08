package com.nexora.share.ui

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Environment
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nexora.share.model.ServerState
import com.nexora.share.service.ServerForegroundService
import com.nexora.share.service.ServerRepository
import com.nexora.share.utils.NetworkUtils
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class MainViewModel : ViewModel() {

    val serverState: StateFlow<ServerState> = ServerRepository.serverState

    private val _hasStoragePermission = MutableStateFlow(false)
    val hasStoragePermission: StateFlow<Boolean> = _hasStoragePermission.asStateFlow()

    fun updateInitialNetworkInfo(context: Context) {
        val ip = NetworkUtils.getLocalIpAddress(context)
        val isWifi = NetworkUtils.isWifiOrHotspotActive(context)
        ServerRepository.setNetworkStatus(isWifi, ip)
        checkStoragePermission(context)
    }

    fun checkStoragePermission(context: Context) {
        val granted = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Environment.isExternalStorageManager() ||
                    hasPermission(context, Manifest.permission.READ_MEDIA_IMAGES) ||
                    hasPermission(context, Manifest.permission.READ_EXTERNAL_STORAGE)
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            hasPermission(context, Manifest.permission.READ_MEDIA_IMAGES) ||
                    hasPermission(context, Manifest.permission.READ_MEDIA_VIDEO)
        } else {
            hasPermission(context, Manifest.permission.READ_EXTERNAL_STORAGE)
        }
        _hasStoragePermission.value = granted
    }

    private fun hasPermission(context: Context, permission: String): Boolean {
        return ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED
    }

    fun toggleServer(context: Context) {
        viewModelScope.launch {
            if (serverState.value.isRunning) {
                ServerForegroundService.stop(context)
            } else {
                ServerForegroundService.start(context)
            }
        }
    }
}
