package org.lilyengine.harness

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import androidx.core.content.edit
import java.io.File

/**
 * `\include` for scores opened through Android's file picker, which gives
 * the file but not its folder: once the user grants the folder, the files a
 * score includes (and those they include) are copied from it into a working
 * folder before each render, and LilyPond is given that folder to search.
 */
object IncludeFolder {
    private const val PREFS = "include-folders"
    private val INCLUDE = Regex("""\\include\s+"([^"]+)"""")

    /** The relative paths a LilyPond text includes, in order. LilyPond's own files (no folder of the score) are left to it. */
    fun includes(text: String): List<String> = INCLUDE.findAll(text).map { it.groupValues[1] }
        .filter { !it.startsWith("/") && !it.contains(":") }.distinct().toList()

    /** The folder granted for the score at [file], if any. */
    fun folderFor(context: Context, file: Uri?): Uri? = file?.let {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(it.toString(), null)?.let(Uri::parse)
    }

    fun setFolderFor(context: Context, file: Uri, folder: Uri) {
        context.contentResolver.takePersistableUriPermission(folder, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit { putString(file.toString(), folder.toString()) }
    }

    /**
     * Copies what [text] includes, from [folder] (a tree the user granted)
     * into [dest], following includes in the included files too. Returns the
     * paths that couldn't be found there.
     */
    fun sync(context: Context, folder: Uri, text: String, dest: File): List<String> {
        dest.deleteRecursively()
        dest.mkdirs()
        val missing = mutableListOf<String>()
        val done = mutableSetOf<String>()
        // (path relative to the folder, as the including file wrote it from its own folder)
        val queue = ArrayDeque(includes(text).map { it to "" })
        while (queue.isNotEmpty()) {
            val (path, from) = queue.removeFirst()
            // LilyPond tries an include relative to the including file first, then the search path.
            val candidates = listOf(normalize(if (from.isEmpty()) path else "$from/$path"), normalize(path)).distinct()
            val found = candidates.firstNotNullOfOrNull { c -> c?.let { p -> find(context, folder, p)?.let { p to it } } }
            if (found == null) { missing += path; continue }
            val (relative, uri) = found
            if (!done.add(relative)) continue
            val content = context.contentResolver.openInputStream(uri)!!.use { it.readBytes() }
            File(dest, relative).apply { parentFile?.mkdirs() }.writeBytes(content)
            val dir = relative.substringBeforeLast('/', "")
            includes(content.toString(Charsets.UTF_8)).forEach { queue += it to dir }
        }
        return missing
    }

    /** "a/./b/../c.ly" -> "a/c.ly"; null if it leaves the folder. */
    private fun normalize(path: String): String? {
        val parts = ArrayDeque<String>()
        for (p in path.split('/')) when (p) {
            "", "." -> {}
            ".." -> if (parts.isEmpty()) return null else parts.removeLast()
            else -> parts += p
        }
        return parts.joinToString("/")
    }

    /** The document at a relative path inside a granted tree, or null. */
    private fun find(context: Context, tree: Uri, path: String): Uri? {
        var doc = DocumentsContract.getTreeDocumentId(tree)
        for (name in path.split('/')) {
            val children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, doc)
            val next = context.contentResolver.query(
                children,
                arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME),
                null, null, null,
            )?.use { c ->
                var id: String? = null
                while (c.moveToNext()) if (c.getString(1) == name) { id = c.getString(0); break }
                id
            } ?: return null
            doc = next
        }
        return DocumentsContract.buildDocumentUriUsingTree(tree, doc)
    }
}
