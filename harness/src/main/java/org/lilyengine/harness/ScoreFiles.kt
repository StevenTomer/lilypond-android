package org.lilyengine.harness

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import androidx.core.content.FileProvider
import androidx.core.content.edit
import java.io.File

/**
 * The score's file: opening and saving LilyPond source through Android's
 * document picker (so it can live in Downloads, on Drive, on an SD card…),
 * remembering which file is open, and sharing the source, PDF and MIDI.
 */
object ScoreFiles {
    /** Which file is open, kept between sessions (the text itself is in the draft). */
    data class Current(val uri: Uri?, val name: String?, val savedHash: Int?)

    private const val PREFS = "score-file"

    private fun prefs(context: Context, doc: String?) =
        context.getSharedPreferences(if (doc == null) PREFS else "$PREFS-$doc", Context.MODE_PRIVATE)

    /** [doc]'s file (null: the single file remembered before tabs). */
    fun load(context: Context, doc: String? = null): Current {
        val p = prefs(context, doc)
        return Current(
            p.getString("uri", null)?.let(Uri::parse),
            p.getString("name", null),
            if (p.contains("savedHash")) p.getInt("savedHash", 0) else null,
        )
    }

    fun store(context: Context, doc: String, current: Current) {
        prefs(context, doc).edit {
            if (current.uri != null) putString("uri", current.uri.toString()) else remove("uri")
            if (current.name != null) putString("name", current.name) else remove("name")
            if (current.savedHash != null) putInt("savedHash", current.savedHash) else remove("savedHash")
        }
    }

    fun forget(context: Context, doc: String) = prefs(context, doc).edit { clear() }

    /** The file's name as the picker shows it, e.g. "minuet.ly". */
    fun displayName(context: Context, uri: Uri): String? = runCatching {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        }
    }.getOrNull()

    /** Keeps access to a picked file across restarts, for writing too if the provider allows it. */
    fun keepAccess(context: Context, uri: Uri) {
        val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
        runCatching { context.contentResolver.takePersistableUriPermission(uri, flags) }
            .onFailure { runCatching { context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) } }
    }

    fun read(context: Context, uri: Uri): String =
        context.contentResolver.openInputStream(uri)!!.use { it.readBytes().toString(Charsets.UTF_8) }

    /** Replaces the file's contents ("wt": truncate, so a shorter text leaves no tail). */
    fun write(context: Context, uri: Uri, text: String) {
        context.contentResolver.openOutputStream(uri, "wt")!!.use { it.write(text.toByteArray(Charsets.UTF_8)) }
    }

    /** A file in the shared export folder (see res/xml/export_paths.xml). */
    fun exportFile(context: Context, name: String): File =
        File(context.cacheDir, "export").apply { mkdirs() }.resolve(name)

    /** Offers [file] to other apps (mail, Drive, a PDF viewer…). */
    fun share(context: Context, file: File, mimeType: String, subject: String) {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", file)
        val send = Intent(Intent.ACTION_SEND)
            .setType(mimeType)
            .putExtra(Intent.EXTRA_STREAM, uri)
            .putExtra(Intent.EXTRA_SUBJECT, subject)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        context.startActivity(Intent.createChooser(send, "Share $subject"))
    }

    /** The file name without its extension, for naming exports: "minuet.ly" -> "minuet". */
    fun baseName(name: String?): String = name?.substringBeforeLast('.')?.takeIf { it.isNotBlank() } ?: "score"

}
