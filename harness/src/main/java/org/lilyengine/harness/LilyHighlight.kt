package org.lilyengine.harness

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.OffsetMapping
import androidx.compose.ui.text.input.TransformedText
import androidx.compose.ui.text.input.VisualTransformation

/**
 * Colours for LilyPond source in the editor: commands, notes and rests,
 * durations, comments, strings, Scheme, and the braces and << >> that
 * structure the music (\\ between voices stands out). A quick tokenizer,
 * not a parser: it only has to look right while typing, also in long files.
 */
object LilyHighlight {
    private val COMMENT = SpanStyle(color = Color(0xFF8A8A8A), fontStyle = FontStyle.Italic)
    private val STRING = SpanStyle(color = Color(0xFF2E7D32))
    private val COMMAND = SpanStyle(color = Color(0xFF1565C0))
    private val SCHEME = SpanStyle(color = Color(0xFFB45F06))
    private val NOTE = SpanStyle(color = Color(0xFF6A1B9A))
    private val DURATION = SpanStyle(color = Color(0xFF00838F))
    private val STRUCTURE = SpanStyle(fontWeight = FontWeight.Bold)
    private val VOICES = SpanStyle(color = Color(0xFFC62828), fontWeight = FontWeight.Bold)

    /** Longer texts are shown plain, so typing stays quick. */
    const val MAX_LENGTH = 300_000

    private val TOKENS = Regex(
        listOf(
            """(?<blockcomment>%\{[\s\S]*?(?:%\}|$))""",
            """(?<comment>%[^\n]*)""",
            """(?<string>"(?:\\.|[^"\\])*"?)""",
            """(?<voices>\\\\)""",
            """(?<command>\\[a-zA-Z]+(?:[-.][a-zA-Z]+)*)""",
            """(?<scheme>#'?[^\s{}()"]*(?:\([^\n]*?\))?)""",
            """(?<structure><<|>>|[{}])""",
            // A note or rest, then its octave marks and duration: c'' cis,4. r8 s2..
            """(?<![A-Za-z\\])(?<note>(?:[a-g](?:isis|eses|is|es|s)?|[rsR])(?![A-Za-z])[',]*!?\??)(?<duration>(?:\d+|\\breve|\\longa)\.*(?:\*\d+(?:/\d+)?)?)?""",
            """(?<![A-Za-z\\'",])(?<dur>(?:1|2|4|8|16|32|64|128)\.*)(?![\d])""",
        ).joinToString("|"),
    )

    /** A coloured stretch of source: [kind] is one of the group names below ("note", "command"…). */
    data class Token(val kind: String, val start: Int, val end: Int)

    private val KINDS = listOf("blockcomment", "comment", "string", "voices", "command", "scheme", "structure", "note", "duration", "dur")

    /** The coloured stretches of [text], in order. */
    fun tokens(text: String): List<Token> = buildList {
        for (m in TOKENS.findAll(text)) {
            val groups = m.groups as MatchNamedGroupCollection
            for (k in KINDS) groups[k]?.let { add(Token(if (k == "dur") "duration" else k, it.range.first, it.range.last + 1)) }
        }
    }

    private val STYLES = mapOf(
        "blockcomment" to COMMENT, "comment" to COMMENT, "string" to STRING, "voices" to VOICES,
        "command" to COMMAND, "scheme" to SCHEME, "structure" to STRUCTURE, "note" to NOTE, "duration" to DURATION,
    )

    fun highlight(text: String): AnnotatedString {
        if (text.length > MAX_LENGTH) return AnnotatedString(text)
        return buildAnnotatedString {
            append(text)
            for (t in tokens(text)) addStyle(STYLES.getValue(t.kind), t.start, t.end)
        }
    }

    /** For a text field: draws the text highlighted, offsets unchanged. */
    class Transformation : VisualTransformation {
        private var lastText: String? = null
        private var lastResult: AnnotatedString? = null

        override fun filter(text: AnnotatedString): TransformedText {
            val s = text.text
            val result = if (s == lastText) lastResult!! else highlight(s).also { lastText = s; lastResult = it }
            return TransformedText(result, OffsetMapping.Identity)
        }
    }
}
