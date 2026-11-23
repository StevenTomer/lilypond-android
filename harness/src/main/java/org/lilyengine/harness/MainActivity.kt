package org.lilyengine.harness

import androidx.activity.compose.BackHandler
import androidx.compose.material3.Surface
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.TextButton
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.Alignment
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.os.Bundle
import android.os.ParcelFileDescriptor
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.material3.Tab
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.lilyengine.engine.LilyPondEngine
import org.lilyengine.lilytools.LilyTools
import org.lilyengine.midi.MidiKeyboard
import java.io.File

/** Throwaway test shell for the engine: edit, render, view page 1, read the log. */
class MainActivity : ComponentActivity() {
    /** One MIDI keyboard connection for all tabs, open while the app is in front. */
    private lateinit var keyboard: MidiKeyboard

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val engine = LilyPondEngine(this)
        keyboard = MidiKeyboard(this)
        setContent {
            var appearance by remember { mutableStateOf(Appearance.load(this)) }
            AndroLilyTheme(appearance) {
                Root(engine, keyboard, appearance) { appearance = it; Appearance.save(this, it) }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        keyboard.start()
    }

    override fun onStop() {
        keyboard.stop()
        super.onStop()
    }
}

@Composable
private fun Root(engine: LilyPondEngine, keyboard: MidiKeyboard, appearance: Appearance, onAppearance: (Appearance) -> Unit) {
    // The app is the score; the self-test, benchmark and MIDI device screens are
    // diagnostics, opened from Settings over it (the score stays as it was).
    var diagnostics by rememberSaveable { mutableStateOf(false) }
    var settings by remember { mutableStateOf(false) }
    /** Bumped to open the keypad's settings (they live with the keypad, in the score screen). */
    var keypadRequest by remember { mutableIntStateOf(0) }
    // The band's colour also fills the status bar's strip above it.
    Column(Modifier.fillMaxSize().background(appColors.band).safeDrawingPadding()) {
        TitleBand(onSettings = { settings = true })
        Surface(Modifier.weight(1f).fillMaxWidth(), color = MaterialTheme.colorScheme.background) {
            Box(Modifier.fillMaxSize()) {
                ScoreScreen(engine, keyboard, keypadSettingsRequest = keypadRequest)
                if (diagnostics) {
                    BackHandler { diagnostics = false }
                    Surface(Modifier.fillMaxSize()) { Diagnostics(engine, keyboard, onClose = { diagnostics = false }) }
                }
            }
        }
    }
    if (settings) {
        AppSettingsDialog(
            appearance = appearance,
            onAppearance = onAppearance,
            onKeypad = { settings = false; diagnostics = false; keypadRequest++ },
            onDiagnostics = { settings = false; diagnostics = true },
            onDismiss = { settings = false },
        )
    }
}

@Composable
private fun Diagnostics(engine: LilyPondEngine, keyboard: MidiKeyboard, onClose: () -> Unit) {
    var tab by rememberSaveable { mutableIntStateOf(0) }
    val tabs = listOf("Self-test", "Benchmark", "MIDI devices")
    Column(Modifier.fillMaxSize()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onClose) { Text("← Back to the score") }
            Text("Diagnostics", fontWeight = FontWeight.Bold)
        }
        PrimaryTabRow(selectedTabIndex = tab) {
            tabs.forEachIndexed { i, title ->
                Tab(selected = tab == i, onClick = { tab = i }, text = { Text(title) })
            }
        }
        Box(Modifier.weight(1f)) {
            when (tab) {
                0 -> Harness(engine)
                1 -> BenchmarkScreen(engine)
                else -> MidiScreen(keyboard)
            }
        }
    }
}

@Composable
private fun Harness(engine: LilyPondEngine) {
    var source by remember { mutableStateOf(SAMPLE) }
    var log by remember { mutableStateOf("") }
    var page by remember { mutableStateOf<Bitmap?>(null) }
    var busy by remember { mutableStateOf(false) }
    var report by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val tools = remember { LilyTools(context) }

    Column(Modifier.fillMaxSize().padding(8.dp)) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.horizontalScroll(rememberScrollState()),
        ) {
            Button(enabled = !busy, onClick = {
                busy = true
                scope.launch {
                    log = "Rendering…"
                    val r = runCatching { engine.render(source) }
                    r.onSuccess { res ->
                        log = "exit=${res.exitCode} in ${res.elapsedMillis} ms, " +
                            "${res.diagnostics.size} diagnostics\n\n${res.log}"
                        page = res.outputs.firstOrNull()?.let { renderFirstPage(it) }
                    }.onFailure { log = it.stackTraceToString() }
                    busy = false
                }
            }) { Text(if (busy) "Rendering…" else "Render") }
            Button(enabled = !busy, onClick = {
                scope.launch { log = runCatching { engine.version() }.getOrElse { it.toString() } }
            }) { Text("Version") }
            Button(enabled = !busy, onClick = {
                busy = true
                report = null
                page = null
                scope.launch {
                    log = "Running self-test…"
                    val checks = SelfTest(context, engine, tools).run { partial ->
                        log = SelfTest.report(partial) + "\nRunning…"
                    }
                    report = SelfTest.report(checks)
                    log = report!!
                    busy = false
                }
            }) { Text("Self-test") }
            report?.let { text ->
                Button(onClick = { shareText(context, "AndroLily self-test", text) }) { Text("Share") }
            }
        }
        BasicTextField(
            value = source,
            onValueChange = { source = it },
            textStyle = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 13.sp),
            modifier = Modifier.fillMaxWidth().weight(1f).padding(vertical = 8.dp),
        )
        page?.let {
            Image(
                it.asImageBitmap(),
                contentDescription = "Rendered page 1",
                modifier = Modifier.fillMaxWidth().weight(2f).background(androidx.compose.ui.graphics.Color.White),
            )
        }
        Text(
            log,
            fontFamily = FontFamily.Monospace,
            fontSize = 11.sp,
            lineHeight = 14.sp,
            modifier = Modifier.fillMaxWidth().weight(1f).verticalScroll(rememberScrollState()),
        )
    }
}

private suspend fun renderFirstPage(pdf: File): Bitmap = withContext(Dispatchers.IO) {
    ParcelFileDescriptor.open(pdf, ParcelFileDescriptor.MODE_READ_ONLY).use { fd ->
        PdfRenderer(fd).use { renderer ->
            renderer.openPage(0).use { p ->
                val bmp = Bitmap.createBitmap(p.width * 2, p.height * 2, Bitmap.Config.ARGB_8888)
                bmp.eraseColor(Color.WHITE)
                p.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                bmp
            }
        }
    }
}

internal val SAMPLE = """
\version "2.26.0"
\header { title = "Hello from Android" composer = "LilyPond" }
melody = \relative c'' { \clef treble \key g \major \time 3/4
  g4 b8( a) g4 | d'2 c8 b | a4 g fis | g2. \bar "|." }
text = \lyricmode { Twin -- kle, lit -- tle star, how I won -- der }
\score {
  <<
    \new ChordNames \chordmode { g2. g2 c4 d2. g2. }
    \new Voice = "m" \melody
    \new Lyrics \lyricsto "m" \text
  >>
  \layout { }
}
\markup \italic { Set in TeX Gyre Schola on a tablet. }
""".trimStart()
