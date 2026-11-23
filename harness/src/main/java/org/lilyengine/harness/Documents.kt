package org.lilyengine.harness

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.edit
import java.io.File

/**
 * An open document (a tab): its text is kept in its own draft file, which
 * file it came from in its own preferences, and its undo history here, so
 * switching tabs keeps all three. [name] and [modified] are for the tab.
 */
class OpenDoc(val id: String) {
    internal val undo = UndoHistory()
    var name by mutableStateOf<String?>(null)
    var modified by mutableStateOf(false)

    fun draft(context: Context) = File(context.filesDir, "draft-$id.ly")
}

/** The open tabs and which is in front, kept between sessions. */
object Documents {
    private const val PREFS = "documents"

    /** The tabs as they were left, the one in front; at least one. */
    fun load(context: Context): Pair<List<OpenDoc>, String> {
        val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val ids = p.getString("ids", null)?.split(',')?.filter { it.isNotBlank() }
        if (ids.isNullOrEmpty()) {
            // Before tabs: one draft and one remembered file become the first tab.
            val doc = OpenDoc("1")
            File(context.filesDir, LEGACY_DRAFT).takeIf { it.exists() }?.renameTo(doc.draft(context))
            ScoreFiles.store(context, doc.id, ScoreFiles.load(context))
            save(context, listOf(doc), doc.id)
            return listOf(doc) to doc.id
        }
        val docs = ids.map(::OpenDoc)
        for (d in docs) {
            // Until a tab is shown, its name and state come from its file record and draft.
            val file = ScoreFiles.load(context, d.id)
            val text = runCatching { d.draft(context).readText() }.getOrNull()
            d.name = file.name ?: text?.let { titleOf(it) }
            d.modified = text != null && file.savedHash != text.hashCode()
        }
        return docs to (p.getString("active", null)?.takeIf { it in ids } ?: ids.first())
    }

    fun save(context: Context, docs: List<OpenDoc>, active: String) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit {
            putString("ids", docs.joinToString(",") { it.id })
            putString("active", active)
        }

    /** A new tab holding [text] (from [file], if any). */
    fun create(context: Context, text: String, file: ScoreFiles.Current = ScoreFiles.Current(null, null, null)): OpenDoc {
        val doc = OpenDoc(System.currentTimeMillis().toString(36) + "-" + (++created))
        doc.draft(context).writeText(text)
        ScoreFiles.store(context, doc.id, file)
        doc.name = file.name ?: titleOf(text)
        doc.modified = file.savedHash != text.hashCode()
        return doc
    }

    /** Forgets a closed tab's draft and file. */
    fun discard(context: Context, doc: OpenDoc) {
        doc.draft(context).delete()
        ScoreFiles.forget(context, doc.id)
    }

    private const val LEGACY_DRAFT = "score-draft.ly"

    /** Tabs made this session: ids stay unique within a millisecond. */
    private var created = 0

    private val HEADER_TITLE = Regex("""\\header\s*\{[^}]*?\btitle\s*=\s*"([^"]*)"""")

    /** The title in a score's \header, if it has one. */
    fun titleOf(text: String): String? = HEADER_TITLE.find(text)?.groupValues?.get(1)?.takeIf { it.isNotBlank() }
}

/** A new tab's score: one staff of empty bars. */
const val EMPTY_SCORE = """\version "2.26.0"

\header {
  title = "Untitled"
}

\relative c' {
  \clef treble \key c \major \time 4/4
  r1 | r1 | r1 | r1 \bar "|."
}
"""
