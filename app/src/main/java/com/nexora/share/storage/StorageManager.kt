package com.nexora.share.storage

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.os.Environment
import android.util.LruCache
import android.webkit.MimeTypeMap
import com.nexora.share.model.FileItem
import com.nexora.share.model.StorageOverview
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.InputStream
import java.util.Locale

class StorageManager(private val context: Context) {

    val rootDir: File by lazy {
        val emulated = File("/storage/emulated/0")
        if (emulated.exists() && emulated.canRead()) {
            emulated
        } else {
            Environment.getExternalStorageDirectory()
        }
    }

    private val thumbCache = object : LruCache<String, ByteArray>(64 * 1024 * 1024) { // 64 MB
        override fun sizeOf(key: String, value: ByteArray): Int = value.size
    }

    private val allowedCategoryFolders = listOf(
        "DCIM", "Pictures", "Movies", "Download", "Documents", "Music", "Recordings", "Android/media"
    )

    fun resolveSafeFile(relativePath: String): File? {
        val cleanPath = relativePath.trim().removePrefix("/").removePrefix("\\")
        val target = if (cleanPath.isEmpty()) rootDir else File(rootDir, cleanPath)
        return try {
            val canonicalTarget = target.canonicalFile
            val canonicalRoot = rootDir.canonicalFile
            if (canonicalTarget.path.startsWith(canonicalRoot.path)) {
                canonicalTarget
            } else {
                null
            }
        } catch (e: Exception) {
            null
        }
    }

    fun listFiles(relativePath: String, sortOrder: String = "date_desc"): List<FileItem> {
        val folder = resolveSafeFile(relativePath) ?: return emptyList()
        if (!folder.exists() || !folder.isDirectory) return emptyList()

        val files = folder.listFiles() ?: return emptyList()
        val items = files.mapNotNull { toFileItem(it) }

        return when (sortOrder) {
            "name_asc" -> items.sortedWith(compareBy({ !it.isDirectory }, { it.name.lowercase(Locale.ROOT) }))
            "name_desc" -> items.sortedWith(compareBy({ !it.isDirectory }, { -it.name.lowercase(Locale.ROOT).hashCode() }))
            "size_desc" -> items.sortedWith(compareBy({ !it.isDirectory }, { -it.size }))
            "size_asc" -> items.sortedWith(compareBy({ !it.isDirectory }, { it.size }))
            "date_asc" -> items.sortedWith(compareBy({ !it.isDirectory }, { it.lastModified }))
            else -> items.sortedWith(compareBy({ !it.isDirectory }, { -it.lastModified })) // date_desc
        }
    }

    fun listPhotos(folderFilter: String? = null, limit: Int = 100): List<FileItem> {
        val targetFolders = if (!folderFilter.isNullOrBlank() && folderFilter != "all") {
            listOf(File(rootDir, folderFilter))
        } else {
            listOf(
                File(rootDir, "DCIM/Camera"),
                File(rootDir, "DCIM"),
                File(rootDir, "Pictures"),
                File(rootDir, "Pictures/Screenshots"),
                File(rootDir, "Download")
            )
        }

        val photoExts = setOf("jpg", "jpeg", "png", "webp", "heic", "bmp", "gif")
        val photos = mutableListOf<FileItem>()

        for (dir in targetFolders) {
            if (!dir.exists() || !dir.isDirectory) continue
            scanFiles(dir, photoExts, maxDepth = 2, maxResults = limit - photos.size, targetList = photos)
            if (photos.size >= limit) break
        }

        return photos.sortedByDescending { it.lastModified }
    }

    fun listVideos(folderFilter: String? = null, limit: Int = 100): List<FileItem> {
        val targetFolders = if (!folderFilter.isNullOrBlank() && folderFilter != "all") {
            listOf(File(rootDir, folderFilter))
        } else {
            listOf(
                File(rootDir, "DCIM/Camera"),
                File(rootDir, "Movies"),
                File(rootDir, "DCIM"),
                File(rootDir, "Download"),
                File(rootDir, "Pictures")
            )
        }

        val videoExts = setOf("mp4", "mkv", "webm", "mov", "avi", "3gp", "ts")
        val videos = mutableListOf<FileItem>()

        for (dir in targetFolders) {
            if (!dir.exists() || !dir.isDirectory) continue
            scanFiles(dir, videoExts, maxDepth = 2, maxResults = limit - videos.size, targetList = videos)
            if (videos.size >= limit) break
        }

        return videos.sortedByDescending { it.lastModified }
    }

    fun searchFiles(query: String, maxResults: Int = 100): List<FileItem> {
        val q = query.trim().lowercase(Locale.ROOT)
        if (q.isEmpty()) return emptyList()

        val results = mutableListOf<FileItem>()
        fun walk(dir: File, currentDepth: Int) {
            if (currentDepth > 4 || results.size >= maxResults) return
            val list = dir.listFiles() ?: return
            for (f in list) {
                if (f.name.startsWith(".")) continue
                if (f.name.lowercase(Locale.ROOT).contains(q)) {
                    toFileItem(f)?.let { results.add(it) }
                    if (results.size >= maxResults) return
                }
                if (f.isDirectory) {
                    walk(f, currentDepth + 1)
                }
            }
        }

        for (cat in allowedCategoryFolders) {
            val d = File(rootDir, cat)
            if (d.exists() && d.isDirectory) {
                walk(d, 0)
            }
        }

        return results
    }

    fun getStorageOverview(): StorageOverview {
        var totalFiles = 0
        var totalPhotos = 0
        var totalVideos = 0
        var totalDocuments = 0
        var totalSize = 0L

        val photoExts = setOf("jpg", "jpeg", "png", "webp", "heic", "bmp", "gif")
        val videoExts = setOf("mp4", "mkv", "webm", "mov", "avi", "3gp")
        val docExts = setOf("pdf", "doc", "docx", "txt", "xlsx", "xls", "ppt", "pptx")

        for (cat in allowedCategoryFolders) {
            val d = File(rootDir, cat)
            if (!d.exists() || !d.isDirectory) continue
            val files = d.listFiles() ?: continue
            for (f in files) {
                if (f.name.startsWith(".")) continue
                totalFiles++
                val ext = f.extension.lowercase(Locale.ROOT)
                if (f.isFile) {
                    totalSize += f.length()
                    when {
                        ext in photoExts -> totalPhotos++
                        ext in videoExts -> totalVideos++
                        ext in docExts -> totalDocuments++
                    }
                }
            }
        }

        return StorageOverview(
            totalFiles = totalFiles,
            totalPhotos = totalPhotos,
            totalVideos = totalVideos,
            totalDocuments = totalDocuments,
            totalSize = totalSize,
            deviceModel = com.nexora.share.utils.NetworkUtils.getDeviceModel()
        )
    }

    fun getThumbnailBytes(filePath: String, isVideo: Boolean): ByteArray? {
        val cacheKey = "$isVideo:$filePath"
        thumbCache.get(cacheKey)?.let { return it }

        val file = resolveSafeFile(filePath) ?: return null
        if (!file.exists() || !file.isFile) return null

        try {
            val bitmap: Bitmap? = if (isVideo) {
                val retriever = MediaMetadataRetriever()
                try {
                    retriever.setDataSource(file.absolutePath)
                    retriever.getFrameAtTime(1000000, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                        ?: retriever.frameAtTime
                } catch (e: Exception) {
                    null
                } finally {
                    try { retriever.release() } catch (_: Exception) {}
                }
            } else {
                val options = BitmapFactory.Options().apply {
                    inJustDecodeBounds = true
                }
                BitmapFactory.decodeFile(file.absolutePath, options)
                options.inSampleSize = calculateInSampleSize(options, 360, 360)
                options.inJustDecodeBounds = false
                BitmapFactory.decodeFile(file.absolutePath, options)
            }

            if (bitmap != null) {
                val stream = ByteArrayOutputStream()
                bitmap.compress(Bitmap.CompressFormat.JPEG, 75, stream)
                bitmap.recycle()
                val bytes = stream.toByteArray()
                thumbCache.put(cacheKey, bytes)
                return bytes
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return null
    }

    fun getVideoDurationSeconds(file: File): Long {
        var retriever: MediaMetadataRetriever? = null
        return try {
            retriever = MediaMetadataRetriever()
            retriever.setDataSource(file.absolutePath)
            val durStr = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
            val durMs = durStr?.toLongOrNull() ?: 0L
            durMs / 1000L
        } catch (e: Exception) {
            0L
        } finally {
            try { retriever?.release() } catch (_: Exception) {}
        }
    }

    private fun scanFiles(
        dir: File,
        extensions: Set<String>,
        maxDepth: Int,
        maxResults: Int,
        targetList: MutableList<FileItem>,
        currentDepth: Int = 0
    ) {
        if (currentDepth > maxDepth || targetList.size >= maxResults) return
        val files = dir.listFiles() ?: return
        for (f in files) {
            if (f.name.startsWith(".")) continue
            if (f.isFile) {
                val ext = f.extension.lowercase(Locale.ROOT)
                if (ext in extensions) {
                    toFileItem(f)?.let { targetList.add(it) }
                    if (targetList.size >= maxResults) return
                }
            } else if (f.isDirectory && currentDepth < maxDepth) {
                scanFiles(f, extensions, maxDepth, maxResults, targetList, currentDepth + 1)
            }
        }
    }

    private fun toFileItem(file: File): FileItem? {
        val relPath = try {
            file.canonicalFile.relativeTo(rootDir.canonicalFile).path
        } catch (e: Exception) {
            file.name
        }

        val ext = file.extension.lowercase(Locale.ROOT)
        val mime = getMimeType(file)
        val isVideo = ext in setOf("mp4", "mkv", "webm", "mov", "avi", "3gp")
        val duration = if (isVideo && file.isFile) getVideoDurationSeconds(file) else 0L

        return FileItem(
            name = file.name,
            path = relPath,
            isDirectory = file.isDirectory,
            size = if (file.isDirectory) 0L else file.length(),
            lastModified = file.lastModified(),
            mimeType = mime,
            extension = ext,
            duration = duration
        )
    }

    fun getMimeType(file: File): String {
        if (file.isDirectory) return "inode/directory"
        val ext = file.extension.lowercase(Locale.ROOT)
        return when (ext) {
            "mp4" -> "video/mp4"
            "webm" -> "video/webm"
            "mkv" -> "video/x-matroska"
            "mov" -> "video/quicktime"
            "jpg", "jpeg" -> "image/jpeg"
            "png" -> "image/png"
            "webp" -> "image/webp"
            "gif" -> "image/gif"
            "pdf" -> "application/pdf"
            "mp3" -> "audio/mpeg"
            "wav" -> "audio/wav"
            "ogg" -> "audio/ogg"
            "txt" -> "text/plain"
            "html" -> "text/html"
            "css" -> "text/css"
            "js" -> "application/javascript"
            "json" -> "application/json"
            "zip" -> "application/zip"
            "apk" -> "application/vnd.android.package-archive"
            else -> MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext) ?: "application/octet-stream"
        }
    }

    private fun calculateInSampleSize(options: BitmapFactory.Options, reqWidth: Int, reqHeight: Int): Int {
        val height = options.outHeight
        val width = options.outWidth
        var inSampleSize = 1
        if (height > reqHeight || width > reqWidth) {
            val halfHeight = height / 2
            val halfWidth = width / 2
            while ((halfHeight / inSampleSize) >= reqHeight && (halfWidth / inSampleSize) >= reqWidth) {
                inSampleSize *= 2
            }
        }
        return inSampleSize
    }
}
