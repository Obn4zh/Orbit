package app.orbit.tv.core

import android.content.ContentResolver
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.StatFs
import android.provider.MediaStore
import android.webkit.MimeTypeMap
import androidx.documentfile.provider.DocumentFile
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStream

/** One file being written. Created before the first byte; committed (made visible) after the last. */
interface StorageTarget {
    val uri: Uri
    fun open(append: Boolean): OutputStream
    /** Makes the file visible and returns its final display name (MediaStore may have renamed it). */
    fun commit(): String
    fun delete()
}

/**
 * Where received files go. Android TV has no user-browsable file system and many TVs ship a stub instead of the
 * system folder picker, so the default is the shared Download collection via MediaStore (no permission needed on 10+).
 */
class Storage(private val context: Context, private val settings: Settings) {
    private val resolver: ContentResolver = context.contentResolver

    /** True when this device has a real Storage Access Framework folder picker (not the TV framework stub). */
    fun isFolderPickerAvailable(): Boolean {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT_TREE)
        val info = context.packageManager.resolveActivity(intent, PackageManager.MATCH_DEFAULT_ONLY) ?: return false
        val pkg = info.activityInfo?.packageName ?: return false
        return !pkg.contains("frameworkpackagestubs") && !info.activityInfo.name.contains("Stub")
    }

    fun describe(state: SettingsState = settings.current): String = when (state.storageMode) {
        StorageMode.DOWNLOADS -> "Downloads › $FOLDER"
        StorageMode.BY_TYPE -> "Pictures, Movies, Music and Downloads › $FOLDER"
        StorageMode.CUSTOM -> state.treeLabel ?: "Custom folder"
    }

    fun freeBytes(): Long = try {
        StatFs(Environment.getExternalStorageDirectory().path).availableBytes
    } catch (e: Exception) { Long.MAX_VALUE }

    fun create(safeName: String, declaredType: String?): StorageTarget {
        val mime = mimeFor(safeName, declaredType)
        val state = settings.current
        if (state.storageMode == StorageMode.CUSTOM && state.treeUri != null) {
            runCatching { return createInTree(Uri.parse(state.treeUri), safeName, mime) }
            // Folder permission revoked or provider gone: fall back to Downloads instead of failing the transfer.
        }
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) createInMediaStore(safeName, mime, state.storageMode)
        else createLegacy(safeName, mime)
    }

    fun delete(uri: Uri): Boolean = try {
        if (uri.scheme == "file") File(uri.path!!).delete()
        else if (uri.authority == MediaStore.AUTHORITY) resolver.delete(uri, null, null) > 0
        else DocumentFile.fromSingleUri(context, uri)?.delete() == true
    } catch (e: Exception) { false }

    fun exists(uri: Uri): Boolean = try {
        if (uri.scheme == "file") File(uri.path!!).exists()
        else resolver.query(uri, arrayOf(MediaStore.MediaColumns.DISPLAY_NAME), null, null, null)?.use { it.moveToFirst() } == true
    } catch (e: Exception) { false }

    // ---------------------------------------------------------------- MediaStore (Android 10+)

    private fun createInMediaStore(name: String, mime: String, mode: StorageMode): StorageTarget {
        val (collection, dir) = when {
            mode == StorageMode.BY_TYPE && mime.startsWith("image/") ->
                MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY) to Environment.DIRECTORY_PICTURES
            mode == StorageMode.BY_TYPE && mime.startsWith("video/") ->
                MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY) to Environment.DIRECTORY_MOVIES
            mode == StorageMode.BY_TYPE && mime.startsWith("audio/") ->
                MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY) to Environment.DIRECTORY_MUSIC
            else -> MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY) to Environment.DIRECTORY_DOWNLOADS
        }
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(MediaStore.MediaColumns.MIME_TYPE, mime)
            put(MediaStore.MediaColumns.RELATIVE_PATH, "$dir/$FOLDER/")
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val uri = runCatching { resolver.insert(collection, values) }.getOrNull()
            // A collection can reject a MIME type it does not know (e.g. exotic image formats): use Downloads.
            ?: resolver.insert(MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY), values.apply {
                put(MediaStore.MediaColumns.RELATIVE_PATH, "${Environment.DIRECTORY_DOWNLOADS}/$FOLDER/")
            })
            ?: throw TransferError(500, ErrorCodes.WRITE_FAILED, "Could not create the file.")

        return object : StorageTarget {
            override val uri = uri
            override fun open(append: Boolean): OutputStream =
                resolver.openOutputStream(uri, if (append) "wa" else "wt") ?: throw TransferError(500, ErrorCodes.WRITE_FAILED, "Cannot open output.")
            override fun commit(): String {
                resolver.update(uri, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
                return resolver.query(uri, arrayOf(MediaStore.MediaColumns.DISPLAY_NAME), null, null, null)?.use {
                    if (it.moveToFirst()) it.getString(0) else null
                } ?: name
            }
            override fun delete() { runCatching { resolver.delete(uri, null, null) } }
        }
    }

    // ---------------------------------------------------------------- Android 8/9

    @Suppress("DEPRECATION")
    private fun createLegacy(name: String, mime: String): StorageTarget {
        val dir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), FOLDER)
        if (!dir.exists() && !dir.mkdirs()) throw TransferError(500, ErrorCodes.WRITE_FAILED, "Storage permission is required.")
        val unique = FileNameSanitizer.makeUnique(name) { File(dir, it).exists() || File(dir, "$it.part").exists() }
        val part = File(dir, "$unique.part")
        return object : StorageTarget {
            override val uri: Uri = Uri.fromFile(File(dir, unique))
            override fun open(append: Boolean): OutputStream = FileOutputStream(part, append)
            override fun commit(): String {
                val final = File(dir, FileNameSanitizer.makeUnique(unique) { File(dir, it).exists() })
                if (!part.renameTo(final)) throw TransferError(500, ErrorCodes.WRITE_FAILED, "Rename failed.")
                MediaScannerConnection.scanFile(context, arrayOf(final.path), arrayOf(mime), null)
                return final.name
            }
            override fun delete() { part.delete() }
        }
    }

    // ---------------------------------------------------------------- Storage Access Framework tree

    private fun createInTree(tree: Uri, name: String, mime: String): StorageTarget {
        val dir = DocumentFile.fromTreeUri(context, tree) ?: error("tree unavailable")
        val doc = dir.createFile(mime, name) ?: error("createFile failed")
        return object : StorageTarget {
            override val uri: Uri = doc.uri
            override fun open(append: Boolean): OutputStream =
                resolver.openOutputStream(doc.uri, if (append) "wa" else "wt") ?: throw TransferError(500, ErrorCodes.WRITE_FAILED, "Cannot open output.")
            override fun commit(): String = doc.name ?: name
            override fun delete() { runCatching { doc.delete() } }
        }
    }

    companion object {
        const val FOLDER = "Orbit"

        fun mimeFor(name: String, declared: String?): String {
            val ext = name.substringAfterLast('.', "").lowercase()
            val fromExt = when (ext) {
                "heic" -> "image/heic"; "heif" -> "image/heif"; "mov" -> "video/quicktime"; "dng" -> "image/x-adobe-dng"
                else -> MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext)
            }
            return fromExt ?: declared?.takeIf { it.contains('/') && !it.contains(';') } ?: "application/octet-stream"
        }
    }
}
