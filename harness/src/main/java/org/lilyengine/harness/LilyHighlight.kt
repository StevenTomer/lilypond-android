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
    private class Palette(comment: Long, string: Long, command: Long, scheme: Long, note: Long, duration: Long, voices: Long) {
        val styles = mapOf(
            "blockcomment" to SpanStyle(color = Color(comment), fontStyle = FontStyle.Italic),
            "comment" to SpanStyle(color = Color(comment), fontStyle = FontStyle.Italic),
            "string" to SpanStyle(color = Color(string)),
            "voices" to SpanStyle(color = Color(voices), fontWeight = FontWeight.Bold),
            "command" to SpanStyle(color = Color(command)),
            "scheme" to SpanStyle(color = Color(scheme)),
            "structure" to SpanStyle(fontWeight = FontWeight.Bold),
            "note" to SpanStyle(color = Color(note)),
            "duration" to SpanStyle(color = Color(duration)),
        )
    }
    private val LIGHT = Palette(0xFF8A8A8A, 0xFF2E7D32, 0xFF1565C0, 0xFFB45F06, 0xFF6A1B9A, 0xFF00838F, 0xFFC62828)
    private val DARK = Palette(0xFF8F9096, 0xFF8BC79A, 0xFF8DB4F0, 0xFFE0A866, 0xFFCDA2E8, 0xFF6FCFD6, 0xFFF08A80)

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

    fun highlight(text: String, dark: Boolean = false): AnnotatedString {
        if (text.length > MAX_LENGTH) return AnnotatedString(text)
        return buildAnnotatedString {
            append(text)
            for (t in tokens(text)) addStyle((if (dark) DARK else LIGHT).styles.getValue(t.kind), t.start, t.end)
        }
    }

    /** For a text field: draws the text highlighted, offsets unchanged. */
    class Transformation(private val dark: Boolean = false) : VisualTransformation {
        private var lastText: String? = null
        private var lastResult: AnnotatedString? = null

        override fun filter(text: AnnotatedString): TransformedText {
            val s = text.text
            val result = if (s == lastText) lastResult!! else highlight(s, dark).also { lastText = s; lastResult = it }
            return TransformedText(result, OffsetMapping.Identity)
        }
    }
}
