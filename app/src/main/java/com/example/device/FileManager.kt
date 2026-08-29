package com.example.device

import android.content.Context
import android.os.Environment
import android.util.Base64
import android.webkit.MimeTypeMap
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream

data class FileEntry(
    val name: String,
    val path: String,
    val isDirectory: Boolean,
    val size: Long,
    val lastModified: Long,
    val mimeType: String,
    val readable: Boolean,
    val writable: Boolean
)

class FileManager(private val context: Context) {

    fun getDefaultRoot(): String {
        return Environment.getExternalStorageDirectory().absolutePath
    }

    fun getQuickLocations(): List<Map<String, String>> {
        val root = Environment.getExternalStorageDirectory()
        val locations = mutableListOf<Map<String, String>>()
        
        locations.add(mapOf("name" to "Internal Storage", "path" to root.absolutePath, "icon" to "smartphone"))
        
        val downloads = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
        if (downloads != null && downloads.exists()) {
            locations.add(mapOf("name" to "Downloads", "path" to downloads.absolutePath, "icon" to "download"))
        }
        
        val documents = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS)
        if (documents != null && documents.exists()) {
            locations.add(mapOf("name" to "Documents", "path" to documents.absolutePath, "icon" to "description"))
        }

        val dcim = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DCIM)
        if (dcim != null && dcim.exists()) {
            locations.add(mapOf("name" to "Photos & DCIM", "path" to dcim.absolutePath, "icon" to "image"))
        }

        val pictures = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES)
        if (pictures != null && pictures.exists()) {
            locations.add(mapOf("name" to "Pictures", "path" to pictures.absolutePath, "icon" to "photo_library"))
        }

        val music = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MUSIC)
        if (music != null && music.exists()) {
            locations.add(mapOf("name" to "Music", "path" to music.absolutePath, "icon" to "music_note"))
        }

        val appDir = context.getExternalFilesDir(null)
        if (appDir != null) {
            locations.add(mapOf("name" to "App Data", "path" to appDir.absolutePath, "icon" to "folder_special"))
        }

        return locations
    }

    fun listDirectory(targetPath: String?): List<FileEntry> {
        val path = if (targetPath.isNullOrBlank()) getDefaultRoot() else targetPath
        val directory = File(path)

        if (!directory.exists() || !directory.isDirectory) {
            val fallback = context.getExternalFilesDir(null) ?: context.filesDir
            return listDirectoryFiles(fallback)
        }

        return listDirectoryFiles(directory)
    }

    private fun listDirectoryFiles(dir: File): List<FileEntry> {
        val files = dir.listFiles() ?: return emptyList()

        return files.map { file ->
            val ext = file.extension.lowercase()
            val mime = MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext) 
                ?: if (file.isDirectory) "inode/directory" else "application/octet-stream"

            FileEntry(
                name = file.name,
                path = file.absolutePath,
                isDirectory = file.isDirectory,
                size = if (file.isDirectory) file.list()?.size?.toLong() ?: 0L else file.length(),
                lastModified = file.lastModified(),
                mimeType = mime,
                readable = file.canRead(),
                writable = file.canWrite()
            )
        }.sortedWith(compareByDescending<FileEntry> { it.isDirectory }.thenBy { it.name.lowercase() })
    }

    fun readFileAsBase64(path: String, maxBytes: Long = 10 * 1024 * 1024): Pair<String, String> {
        val file = File(path)
        if (!file.exists() || file.isDirectory) {
            throw IllegalArgumentException("File not found or is directory: $path")
        }
        if (file.length() > maxBytes) {
            throw IllegalArgumentException("File is too large (${file.length()} bytes). Limit is ${maxBytes / (1024 * 1024)}MB.")
        }

        val ext = file.extension.lowercase()
        val mime = MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext) ?: "application/octet-stream"

        val bytes = FileInputStream(file).use { it.readBytes() }
        val base64 = Base64.encodeToString(bytes, Base64.NO_WRAP)
        return Pair(base64, mime)
    }

    fun writeFileFromBase64(path: String, base64Content: String): Long {
        val file = File(path)
        file.parentFile?.mkdirs()

        val bytes = Base64.decode(base64Content, Base64.DEFAULT)
        FileOutputStream(file).use { it.write(bytes) }
        return file.length()
    }

    fun deletePath(path: String): Boolean {
        val file = File(path)
        if (!file.exists()) return true
        return if (file.isDirectory) {
            file.deleteRecursively()
        } else {
            file.delete()
        }
    }

    fun createDirectory(path: String): Boolean {
        val dir = File(path)
        return dir.mkdirs()
    }
}
