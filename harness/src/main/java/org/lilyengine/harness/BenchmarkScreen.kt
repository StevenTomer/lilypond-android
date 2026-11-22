package org.lilyengine.harness

import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import org.lilyengine.engine.LilyPondEngine
import java.io.File

/**
 * Render-latency benchmark for realistic score sizes (roadmap M1). Splits
 * LilyPond's fixed startup cost (Guile boot, init files) from engraving
 * time, and measures what the score map and a "fast edit view" (engrave
 * only the last few bars) change.
 */
@Composable
fun BenchmarkScreen(engine: LilyPondEngine) {
    var report by remember { mutableStateOf("Renders each score twice and keeps the faster time. Takes a few minutes.") }
    var busy by remember { mutableStateOf(false) }
    var done by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current

    Column(Modifier.fillMaxSize().padding(8.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(enabled = !busy, onClick = {
                busy = true
                done = false
                scope.launch {
                    report = runBenchmark(engine) { partial -> report = partial + "\nRunning…" }
                    busy = false
                    done = true
                }
            }) { Text(if (busy) "Running…" else "Run benchmark") }
            if (done) Button(onClick = { shareText(context, "LilyPond benchmark", report) }) { Text("Share") }
        }
        Text(
            report,
            fontFamily = FontFamily.Monospace,
            fontSize = 12.sp,
            lineHeight = 15.sp,
            modifier = Modifier.fillMaxWidth().padding(top = 8.dp).verticalScroll(rememberScrollState()),
        )
    }
}

private class Case(val name: String, val source: String)

private suspend fun runBenchmark(engine: LilyPondEngine, progress: (String) -> Unit): String {
    engine.prepare()
    val lines = mutableListOf<String>()
    fun emit(line: String) { lines += line; progress(header() + lines.joinToString("\n")) }

    /** Best of two renders, and the page count (read before the next render deletes the job). */
    suspend fun best(source: String, options: LilyPondEngine.Options): Pair<Long, Int> {
        var bestMs = Long.MAX_VALUE
        var pages = 0
        repeat(2) {
            val r = engine.render(source, options)
            check(r.success) { r.log.takeLast(2000) }
            if (r.elapsedMillis < bestMs) bestMs = r.elapsedMillis
            pages = pageCount(r.outputs.first())
            if (options.scoreMap) checkNotNull(r.scoreMap) { "no score map" }
        }
        return bestMs to pages
    }

    val plain = LilyPondEngine.Options(midi = false, scoreMap = false)
    val full = LilyPondEngine.Options(midi = true, scoreMap = true)

    val (startup, _) = best(TRIVIAL, plain)
    emit("Startup (trivial score): $startup ms\n")
    emit(String.format("%-22s %5s %8s %9s %9s %8s", "score", "pages", "plain", "+map+midi", "engraving", "edit view"))
    for (case in CASES) {
        try {
            val (tPlain, pages) = best(case.source, plain)
            val (tFull, _) = best(case.source, full)
            val (tEdit, _) = best(editView(case.source), full)
            emit(String.format("%-22s %5d %6d ms %6d ms %6d ms %5d ms", case.name, pages, tPlain, tFull, tPlain - startup, tEdit))
        } catch (e: Exception) {
            emit("${case.name}: FAILED ${e.message?.take(500)}")
        }
    }
    emit("\n\"plain\" = PDF only; \"+map+midi\" = PDF + score map + MIDI;")
    emit("\"engraving\" = plain minus startup; \"edit view\" = only the last 4 bars")
    emit("engraved (showLastLength), with score map and MIDI.")
    return header() + lines.joinToString("\n")
}

private fun header() = SelfTest.deviceInfo() + "\n"

private fun pageCount(pdf: File): Int =
    ParcelFileDescriptor.open(pdf, ParcelFileDescriptor.MODE_READ_ONLY).use { fd ->
        PdfRenderer(fd).use { it.pageCount }
    }

/** LilyPond's showLastLength engraves only the end of the music: a cheap edit preview. */
private fun editView(source: String) =
    source.replaceFirst("\\version \"2.26.0\"", "\\version \"2.26.0\"\nshowLastLength = R1*4")

private const val TRIVIAL = "\\version \"2.26.0\"\n{ c'1 }\n"

private fun chorale(bars: Int): String {
    val s = "c''4 d'' e'' d'' | c''2 b'4 c'' | d''4 e'' f'' e'' | d''1 |"
    val a = "e'4 g' g' g' | e'2 g'4 g' | b'4 c'' c'' c'' | b'1 |"
    val t = "g4 b c' b | g2 d'4 c' | g4 g a c' | g1 |"
    val b = "c4 g, c g, | c2 g,4 e | g,4 c f c | g,1 |"
    val words = "Al -- le Men -- schen wer -- den Brü -- der, wo dein sanf -- ter Flü -- gel weilt. "
    val n = bars / 4
    return """
\version "2.26.0"
\header { title = "Chorale" }
global = { \key c \major \time 4/4 }
\score {
  \new ChoirStaff <<
    \new Staff << \global \new Voice = "s" { \voiceOne \repeat unfold $n { $s } } \new Voice { \voiceTwo \repeat unfold $n { $a } } >>
    \new Lyrics \lyricsto "s" { \repeat unfold $n { $words } }
    \new Staff << \global \clef bass \new Voice { \voiceOne \repeat unfold $n { $t } } \new Voice { \voiceTwo \repeat unfold $n { $b } } >>
  >>
  \layout { }
}
"""
}

private fun piano(bars: Int): String {
    val rh = "<< { c''8 d'' e'' f'' g''4 e''8( c'') } \\\\ { e'2 g'4 a' } >> | <e' g' c''>4-. r8 b'16 c'' d''4\\f( c'') |"
    val lh = "c4 <e g c'> g, <d g b> | <c e g>2 g,4\\p c |"
    val n = bars / 2
    return """
\version "2.26.0"
\header { title = "Piano piece" composer = "Benchmark" }
\score {
  \new PianoStaff <<
    \new Staff { \key c \major \time 4/4 \repeat unfold $n { $rh } }
    \new Staff { \clef bass \key c \major \repeat unfold $n { $lh } }
  >>
  \layout { }
}
"""
}

private fun orchestra(bars: Int): String {
    val names = listOf(
        "Flute", "Oboe", "Clarinet", "Bassoon", "Horn", "Trumpet", "Trombone", "Timpani",
        "Violin I", "Violin II", "Viola", "Cello", "Bass", "Harp", "Percussion", "Piano",
    )
    val upper = "c''8-. d''-. e''4-> g''2\\f | f''4( e'') d''8 c'' b'4\\p |"
    val lower = "c4-- g, c g, | f,2\\< g,\\! |"
    val n = bars / 2
    val staves = names.mapIndexed { i, name ->
        val low = i in listOf(3, 6, 7, 11, 12)
        val clef = if (low) "\\clef bass" else ""
        "\\new Staff \\with { instrumentName = \"$name\" } { $clef \\repeat unfold $n { ${if (low) lower else upper} } }"
    }.joinToString("\n    ")
    return """
\version "2.26.0"
\header { title = "Orchestra" }
\paper { #(set-paper-size "a4" 'landscape) }
\score {
  <<
    $staves
  >>
  \layout { }
}
"""
}

private val CASES = listOf(
    Case("Chorale, 32 bars", chorale(32)),
    Case("Piano, 400 bars", piano(400)),
    Case("Orchestra 16 staves", orchestra(24)),
    Case("Piano, 40 bars", piano(40)),
)
