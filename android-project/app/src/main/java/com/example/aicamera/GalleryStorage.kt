package com.example.aicamera

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.provider.MediaStore
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.UUID
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest

/** App-private gallery is the source of truth; MediaStore copies remain visible in the phone gallery. */
object GalleryStorage {
    private fun accountKey(): String {
        val account = Api.user.trim().lowercase().ifBlank { "unlinked-account" }
        val bytes = MessageDigest.getInstance("SHA-256").digest(account.toByteArray())
        return bytes.joinToString("") { "%02x".format(it) }.take(24)
    }

    private fun accountMediaPath(): String = "Pictures/AICamera/${accountKey()}/"

    fun directory(context: Context): File {
        val root = File(context.filesDir, "gallery_accounts")
        val dir = File(root, accountKey())
        if (!dir.exists() && !dir.mkdirs()) throw IOException("Unable to create the app gallery")
        // One-time migration: v1.7 had a single local gallery before account switching existed.
        val prefs = context.getSharedPreferences("cfg", Context.MODE_PRIVATE)
        if (!prefs.getBoolean("legacy_private_gallery_imported", false)) {
            val legacy = File(context.filesDir, "gallery")
            legacy.listFiles()?.filter { it.isFile && it.extension.lowercase() in setOf("jpg", "jpeg", "png", "webp") }
                ?.forEach { old ->
                    val target = File(dir, old.name)
                    if (!target.exists()) runCatching { copyAtomically(old, target) }
                }
            prefs.edit().putBoolean("legacy_private_gallery_imported", true).apply()
        }
        return dir
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

    /** Keep a single hidden recovery copy outside the visible gallery. */
    private fun backupDirectory(context: Context): File =
        File(File(context.filesDir, "photo_originals"), accountKey()).apply {
            if (!exists() && !mkdirs()) throw IOException("Unable to create the original-photo backup folder")
        }

    @Synchronized
    fun ensureOriginalBackup(context: Context, primary: File) {
        if (!primary.isFile) throw IOException("The primary photo is missing")
        val backup = File(backupDirectory(context), primary.name)
        if (backup.isFile) return
        copyAtomically(primary, backup)
    }

    fun hasOriginalBackup(context: Context, primary: File): Boolean =
        File(backupDirectory(context), primary.name).isFile

    /** Replace the visible gallery item in-place. Its first original remains hidden for Revert. */
    @Synchronized
    fun replacePrimary(context: Context, primary: File, replacement: File) {
        if (!primary.isFile || !replacement.isFile) throw IOException("The photo to replace is missing")
        ensureOriginalBackup(context, primary)
        copyImageAtomically(replacement, primary)
        publishToMediaStore(context, primary)
    }

    @Synchronized
    fun revertOriginal(context: Context, primary: File) {
        val backup = File(backupDirectory(context), primary.name)
        if (!backup.isFile) throw IOException("No saved original is available for this photo")
        copyAtomically(backup, primary)
        publishToMediaStore(context, primary)
    }

    /** Enhancement results are PNG; convert to the primary file's extension before replacing. */
    private fun copyImageAtomically(source: File, target: File) {
        val bitmap = BitmapFactory.decodeFile(source.path)
            ?: throw IOException("The enhanced image could not be decoded")
        val format = when (target.extension.lowercase()) {
            "png" -> Bitmap.CompressFormat.PNG
            "webp" -> Bitmap.CompressFormat.WEBP_LOSSY
            else -> Bitmap.CompressFormat.JPEG
        }
        val temp = File(target.parentFile, ".${target.name}.${UUID.randomUUID()}.tmp")
        try {
            FileOutputStream(temp).use { output ->
                if (!bitmap.compress(format, 95, output)) throw IOException("Unable to encode enhanced image")
                output.flush()
                output.fd.sync()
            }
            Files.move(temp.toPath(), target.toPath(),
                StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        } catch (e: Exception) {
            temp.delete()
            if (e is IOException) throw e
            throw IOException("Unable to safely replace the photo", e)
        } finally {
            bitmap.recycle()
        }
    }

    private fun copyAtomically(source: File, target: File) {
        val temp = File(target.parentFile, ".${target.name}.${UUID.randomUUID()}.tmp")
        try {
            source.inputStream().use { input ->
                FileOutputStream(temp).use { output ->
                    input.copyTo(output)
                    output.fd.sync()
                }
            }
            Files.move(temp.toPath(), target.toPath(),
                StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        } catch (e: Exception) {
            temp.delete()
            if (e is IOException) throw e
            throw IOException("Unable to safely replace the photo", e)
        }
    }

    fun list(context: Context): List<File> {
        val prefs = context.getSharedPreferences("cfg", Context.MODE_PRIVATE)
        if (!prefs.getBoolean("legacy_external_gallery_imported", false)) {
            importLegacyMediaStorePhotos(context)
            prefs.edit().putBoolean("legacy_external_gallery_imported", true).apply()
        }
        return directory(context).listFiles { f ->
            f.isFile && f.extension.lowercase() in setOf("jpg", "jpeg", "png", "webp")
        }?.sortedByDescending { it.lastModified() } ?: emptyList()
    }

    private fun publishToMediaStore(context: Context, source: File) {
        val resolver = context.contentResolver
        val projection = arrayOf(MediaStore.Images.Media._ID)
        var uri: android.net.Uri? = null
        var created = false
        try {
            resolver.query(
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                projection,
                "${MediaStore.Images.Media.DISPLAY_NAME}=? AND ${MediaStore.Images.Media.RELATIVE_PATH}=?",
                arrayOf(source.name, accountMediaPath()),
                null
            )?.use { cursor ->
                if (cursor.moveToFirst()) {
                    uri = ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                        cursor.getLong(cursor.getColumnIndexOrThrow(MediaStore.Images.Media._ID)))
                }
            }
            if (uri == null) {
                val values = ContentValues().apply {
                    put(MediaStore.Images.Media.DISPLAY_NAME, source.name)
                    put(MediaStore.Images.Media.MIME_TYPE, mimeFor(source))
                    put(MediaStore.Images.Media.RELATIVE_PATH, accountMediaPath().trimEnd('/'))
                    put(MediaStore.Images.Media.IS_PENDING, 1)
                }
                uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values) ?: return
                created = true
            } else {
                resolver.update(uri!!, ContentValues().apply {
                    put(MediaStore.Images.Media.IS_PENDING, 1)
                    put(MediaStore.Images.Media.MIME_TYPE, mimeFor(source))
                }, null, null)
            }
            resolver.openOutputStream(uri!!, "w")?.use { output ->
                source.inputStream().use { input -> input.copyTo(output) }
            } ?: throw IOException("Could not open the phone gallery")
            resolver.update(uri!!, ContentValues().apply {
                put(MediaStore.Images.Media.IS_PENDING, 0)
            }, null, null)
        } catch (_: Exception) {
            try {
                uri?.let {
                    if (created) resolver.delete(it, null, null)
                    else resolver.update(it, ContentValues().apply {
                        put(MediaStore.Images.Media.IS_PENDING, 0)
                    }, null, null)
                }
            } catch (_: Exception) { }
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