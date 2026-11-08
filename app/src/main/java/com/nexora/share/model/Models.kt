package com.nexora.share.model

data class FileItem(
    val name: String,
    val path: String,
    val isDirectory: Boolean,
    val size: Long,
    val lastModified: Long,
    val mimeType: String,
    val extension: String,
    val duration: Long = 0L // For videos in seconds
)

data class StorageOverview(
    val totalFiles: Int = 0,
    val totalPhotos: Int = 0,
    val totalVideos: Int = 0,
    val totalDocuments: Int = 0,
    val totalSize: Long = 0L,
    val deviceModel: String = "Android Device"
)

data class ServerState(
    val isRunning: Boolean = false,
    val ipAddress: String = "",
    val port: Int = 8080,
    val isWifiConnected: Boolean = false,
    val networkName: String = "",
    val errorMessage: String? = null
) {
    val serverUrl: String
        get() = if (ipAddress.isNotEmpty()) "http://$ipAddress:$port" else ""
}
