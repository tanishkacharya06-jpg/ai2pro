package com.example.aicamera

import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.provider.MediaStore
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.UUID

/** App-private gallery is the source of truth; MediaStore copies remain visible in the phone gallery. */
object GalleryStorage {
    fun directory(context: Context): File = File(context.filesDir, "gallery").apply {
        if (!exists() && !mkdirs()) throw IOException("Unable to create the app gallery")
    }

    @Synchronized
    fun save(context: Context, source: File, kind: String): File {
        if (!source.isFile || source.length() <= 0L) throw IOException("The photo is missing or empty")
        val dir = directory(context)
        val required = source.length() + 1024L * 1024L
        if (dir.usableSpace < required) throw IOException("Not enough storage space to save this photo")

        val ext = source.extension.lowercase().ifBlank { "jpg" }
        val safeKind = kind.filter { it.isLetterOrDigit() }.ifBlank { "photo" }
        val name = "AICamera_${safeKind}_${System.currentTimeMillis()}_${UUID.randomUUID().toString().take(8)}.$ext"
        val target = File(dir, name)
        val temp = File(dir, ".$name.tmp")
        try {
            source.inputStream().use { input ->
                FileOutputStream(temp).use { output ->
                    input.copyTo(output)
                    output.fd.sync()
                }
            }
            if (!temp.renameTo(target)) throw IOException("Could not safely finish saving the photo")
        } catch (e: Exception) {
            temp.delete()
            target.delete()
            if (e is IOException) throw e
            throw IOException("Unable to save the photo", e)
        }

        // A failure to publish externally must not destroy the safe app-private copy.
        publishToMediaStore(context, target)
        return target
    }

    fun list(context: Context): List<File> {
        importLegacyMediaStorePhotos(context)
        return directory(context).listFiles { f ->
            f.isFile && f.extension.lowercase() in setOf("jpg", "jpeg", "png", "webp")
        }?.sortedByDescending { it.lastModified() } ?: emptyList()
    }

    private fun publishToMediaStore(context: Context, source: File) {
        var uri: android.net.Uri? = null
        try {
            val values = ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, source.name)
                put(MediaStore.Images.Media.MIME_TYPE, mimeFor(source))
                put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/AICamera")
                put(MediaStore.Images.Media.IS_PENDING, 1)
            }
            uri = context.contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
                ?: return
            context.contentResolver.openOutputStream(uri!!, "w")?.use { output ->
                source.inputStream().use { input -> input.copyTo(output) }
            } ?: throw IOException("Could not open the phone gallery")
            val ready = ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING, 0) }
            context.contentResolver.update(uri!!, ready, null, null)
        } catch (_: Exception) {
            try { uri?.let { context.contentResolver.delete(it, null, null) } } catch (_: Exception) { }
        }
    }

    /** Imports photos saved by v1.6, which only wrote to MediaStore. */
    private fun importLegacyMediaStorePhotos(context: Context) {
        val resolver = context.contentResolver
        val projection = arrayOf(
            MediaStore.Images.Media._ID,
            MediaStore.Images.Media.DISPLAY_NAME,
            MediaStore.Images.Media.DATE_ADDED
        )
        try {
            resolver.query(
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                projection,
                "${MediaStore.Images.Media.RELATIVE_PATH}=?",
                arrayOf("Pictures/AICamera/"),
                "${MediaStore.Images.Media.DATE_ADDED} DESC"
            )?.use { cursor ->
                val idCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media._ID)
                val nameCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DISPLAY_NAME)
                val dateCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DATE_ADDED)
                val dir = directory(context)
                while (cursor.moveToNext()) {
                    val name = File(cursor.getString(nameCol) ?: "").name
                    if (name.isBlank()) continue
                    val target = File(dir, name)
                    if (target.exists()) continue
                    val id = cursor.getLong(idCol)
                    val uri = ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, id)
                    val temp = File(dir, ".$name.importing")
                    try {
                        resolver.openInputStream(uri)?.use { input ->
                            FileOutputStream(temp).use { output ->
                                input.copyTo(output)
                                output.fd.sync()
                            }
                        } ?: continue
                        if (!temp.renameTo(target)) temp.delete()
                        else target.setLastModified(cursor.getLong(dateCol) * 1000L)
                    } catch (_: Exception) {
                        temp.delete()
                    }
                }
            }
        } catch (_: Exception) {
            // The app-private gallery still works if MediaStore is unavailable.
        }
    }

    private fun mimeFor(file: File): String = when (file.extension.lowercase()) {
        "png" -> "image/png"
        "webp" -> "image/webp"
        else -> "image/jpeg"
    }
}