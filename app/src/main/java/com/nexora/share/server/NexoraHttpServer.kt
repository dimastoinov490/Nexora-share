package com.nexora.share.server

import android.content.Context
import com.nexora.share.model.FileItem
import com.nexora.share.storage.StorageManager
import fi.iki.elonen.NanoHTTPD
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.io.File
import java.io.FileInputStream
import java.io.InputStream

class NexoraHttpServer(
    private val context: Context,
    port: Int = 8080,
    private val storageManager: StorageManager = StorageManager(context)
) : NanoHTTPD(port) {

    override fun serve(session: IHTTPSession): Response {
        val uri = session.uri
        val method = session.method

        if (method == Method.OPTIONS) {
            val response = newFixedLengthResponse(Response.Status.OK, "text/plain", "")
            addCorsHeaders(response)
            return response
        }

        return try {
            val response = when {
                uri.startsWith("/api/status") -> handleStatus()
                uri.startsWith("/api/files") -> handleFiles(session)
                uri.startsWith("/api/photos") -> handlePhotos(session)
                uri.startsWith("/api/videos") -> handleVideos(session)
                uri.startsWith("/api/search") -> handleSearch(session)
                uri.startsWith("/api/thumb") -> handleThumbnail(session)
                uri.startsWith("/api/download") -> handleDownload(session)
                uri.startsWith("/api/stream") -> handleStream(session)
                else -> serveStaticAsset(uri)
            }
            addCorsHeaders(response)
            response
        } catch (e: Exception) {
            e.printStackTrace()
            val errResponse = newFixedLengthResponse(
                Response.Status.INTERNAL_ERROR,
                "application/json",
                JSONObject().put("error", e.message ?: "Internal Server Error").toString()
            )
            addCorsHeaders(errResponse)
            errResponse
        }
    }

    private fun handleStatus(): Response {
        val overview = storageManager.getStorageOverview()
        val json = JSONObject().apply {
            put("status", "ok")
            put("deviceModel", overview.deviceModel)
            put("totalFiles", overview.totalFiles)
            put("totalPhotos", overview.totalPhotos)
            put("totalVideos", overview.totalVideos)
            put("totalDocuments", overview.totalDocuments)
            put("totalSize", overview.totalSize)
        }
        return newFixedLengthResponse(Response.Status.OK, "application/json; charset=utf-8", json.toString())
    }

    private fun handleFiles(session: IHTTPSession): Response {
        val path = session.parameters["path"]?.firstOrNull() ?: ""
        val sort = session.parameters["sort"]?.firstOrNull() ?: "date_desc"
        val items = storageManager.listFiles(path, sort)

        val json = JSONObject().apply {
            put("path", path)
            put("items", toJsonArray(items))
        }
        return newFixedLengthResponse(Response.Status.OK, "application/json; charset=utf-8", json.toString())
    }

    private fun handlePhotos(session: IHTTPSession): Response {
        val folder = session.parameters["folder"]?.firstOrNull()
        val limit = session.parameters["limit"]?.firstOrNull()?.toIntOrNull() ?: 100
        val items = storageManager.listPhotos(folder, limit)

        val json = JSONObject().apply {
            put("items", toJsonArray(items))
        }
        return newFixedLengthResponse(Response.Status.OK, "application/json; charset=utf-8", json.toString())
    }

    private fun handleVideos(session: IHTTPSession): Response {
        val folder = session.parameters["folder"]?.firstOrNull()
        val limit = session.parameters["limit"]?.firstOrNull()?.toIntOrNull() ?: 100
        val items = storageManager.listVideos(folder, limit)

        val json = JSONObject().apply {
            put("items", toJsonArray(items))
        }
        return newFixedLengthResponse(Response.Status.OK, "application/json; charset=utf-8", json.toString())
    }

    private fun handleSearch(session: IHTTPSession): Response {
        val q = session.parameters["q"]?.firstOrNull() ?: ""
        val items = storageManager.searchFiles(q)

        val json = JSONObject().apply {
            put("query", q)
            put("items", toJsonArray(items))
        }
        return newFixedLengthResponse(Response.Status.OK, "application/json; charset=utf-8", json.toString())
    }

    private fun handleThumbnail(session: IHTTPSession): Response {
        val path = session.parameters["path"]?.firstOrNull() ?: return notFoundResponse()
        val isVideo = session.parameters["type"]?.firstOrNull() == "video"

        val bytes = storageManager.getThumbnailBytes(path, isVideo) ?: return notFoundResponse()
        val stream = ByteArrayInputStream(bytes)

        val response = newFixedLengthResponse(Response.Status.OK, "image/jpeg", stream, bytes.size.toLong())
        response.addHeader("Cache-Control", "public, max-age=86400")
        return response
    }

    private fun handleDownload(session: IHTTPSession): Response {
        val path = session.parameters["path"]?.firstOrNull() ?: return notFoundResponse()
        val file = storageManager.resolveSafeFile(path) ?: return forbiddenResponse()

        if (!file.exists() || !file.isFile) return notFoundResponse()

        val mime = storageManager.getMimeType(file)
        val fis = FileInputStream(file)
        val response = newFixedLengthResponse(Response.Status.OK, mime, fis, file.length())
        response.addHeader("Content-Disposition", "attachment; filename=\"${encodeFilename(file.name)}\"")
        return response
    }

    private fun handleStream(session: IHTTPSession): Response {
        val path = session.parameters["path"]?.firstOrNull() ?: return notFoundResponse()
        val file = storageManager.resolveSafeFile(path) ?: return forbiddenResponse()

        if (!file.exists() || !file.isFile) return notFoundResponse()

        val fileLength = file.length()
        val mime = storageManager.getMimeType(file)
        val rangeHeader = session.headers["range"]

        if (rangeHeader != null && rangeHeader.startsWith("bytes=")) {
            var rangeValue = rangeHeader.substring("bytes=".length).trim()
            val dashPos = rangeValue.indexOf('-')
            var startFrom: Long = 0
            var endAt: Long = fileLength - 1

            if (dashPos != -1) {
                val startStr = rangeValue.substring(0, dashPos).trim()
                val endStr = rangeValue.substring(dashPos + 1).trim()
                if (startStr.isNotEmpty()) {
                    startFrom = startStr.toLongOrNull() ?: 0L
                }
                if (endStr.isNotEmpty()) {
                    endAt = endStr.toLongOrNull() ?: (fileLength - 1)
                }
            }

            if (startFrom >= fileLength) {
                val err = newFixedLengthResponse(Response.Status.RANGE_NOT_SATISFIABLE, "text/plain", "")
                err.addHeader("Content-Range", "bytes */$fileLength")
                return err
            }

            if (endAt >= fileLength) {
                endAt = fileLength - 1
            }

            val rangeLength = endAt - startFrom + 1
            val fis = FileInputStream(file)
            fis.skip(startFrom)

            val boundedStream = object : InputStream() {
                private var bytesRemaining = rangeLength
                override fun read(): Int {
                    if (bytesRemaining <= 0) return -1
                    val b = fis.read()
                    if (b != -1) bytesRemaining--
                    return b
                }

                override fun read(b: ByteArray, off: Int, len: Int): Int {
                    if (bytesRemaining <= 0) return -1
                    val toRead = minOf(len.toLong(), bytesRemaining).toInt()
                    val count = fis.read(b, off, toRead)
                    if (count > 0) bytesRemaining -= count
                    return count
                }

                override fun close() {
                    fis.close()
                }
            }

            val res = newFixedLengthResponse(Response.Status.PARTIAL_CONTENT, mime, boundedStream, rangeLength)
            res.addHeader("Content-Range", "bytes $startFrom-$endAt/$fileLength")
            res.addHeader("Accept-Ranges", "bytes")
            res.addHeader("Content-Length", rangeLength.toString())
            return res
        } else {
            // Full stream
            val fis = FileInputStream(file)
            val res = newFixedLengthResponse(Response.Status.OK, mime, fis, fileLength)
            res.addHeader("Accept-Ranges", "bytes")
            res.addHeader("Content-Length", fileLength.toString())
            return res
        }
    }

    private fun serveStaticAsset(uri: String): Response {
        var cleanUri = uri.trim()
        if (cleanUri == "/" || cleanUri.isEmpty()) {
            cleanUri = "/index.html"
        }
        val assetPath = "web" + cleanUri.replace('\\', '/')

        return try {
            val assetManager = context.assets
            val inputStream = assetManager.open(assetPath)
            val mime = when {
                cleanUri.endsWith(".html") -> "text/html; charset=utf-8"
                cleanUri.endsWith(".css") -> "text/css; charset=utf-8"
                cleanUri.endsWith(".js") -> "application/javascript; charset=utf-8"
                cleanUri.endsWith(".svg") -> "image/svg+xml"
                cleanUri.endsWith(".png") -> "image/png"
                cleanUri.endsWith(".ico") -> "image/x-icon"
                cleanUri.endsWith(".json") -> "application/json"
                else -> "application/octet-stream"
            }
            val response = newChunkedResponse(Response.Status.OK, mime, inputStream)
            if (cleanUri.endsWith(".css") || cleanUri.endsWith(".js") || cleanUri.endsWith(".svg")) {
                response.addHeader("Cache-Control", "public, max-age=3600")
            }
            response
        } catch (e: Exception) {
            // Fallback: try index.html for SPA routing
            try {
                val inputStream = context.assets.open("web/index.html")
                newChunkedResponse(Response.Status.OK, "text/html; charset=utf-8", inputStream)
            } catch (_: Exception) {
                notFoundResponse()
            }
        }
    }

    private fun toJsonArray(items: List<FileItem>): JSONArray {
        val arr = JSONArray()
        for (item in items) {
            arr.put(JSONObject().apply {
                put("name", item.name)
                put("path", item.path)
                put("isDirectory", item.isDirectory)
                put("size", item.size)
                put("lastModified", item.lastModified)
                put("mimeType", item.mimeType)
                put("extension", item.extension)
                put("duration", item.duration)
            })
        }
        return arr
    }

    private fun encodeFilename(name: String): String {
        return name.replace("\"", "\\\"")
    }

    private fun addCorsHeaders(response: Response) {
        response.addHeader("Access-Control-Allow-Origin", "*")
        response.addHeader("Access-Control-Allow-Methods", "GET, HEAD, OPTIONS")
        response.addHeader("Access-Control-Allow-Headers", "Range, Content-Type, Authorization")
    }

    private fun notFoundResponse(): Response {
        return newFixedLengthResponse(Response.Status.NOT_FOUND, "application/json", "{\"error\":\"Not Found\"}")
    }

    private fun forbiddenResponse(): Response {
        return newFixedLengthResponse(Response.Status.FORBIDDEN, "application/json", "{\"error\":\"Access Denied\"}")
    }
}
