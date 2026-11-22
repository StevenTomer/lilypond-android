package org.lilyengine.harness

import android.media.MediaPlayer
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.material3.OutlinedButton
import androidx.compose.ui.platform.LocalContext
import org.lilyengine.lilytools.LilyTools
import org.lilyengine.lilytools.TextEdit
import kotlin.math.roundToInt
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import org.lilyengine.engine.Diagnostic
import org.lilyengine.engine.LilyPondEngine
import org.lilyengine.engine.ScoreMap.Grob
import org.lilyengine.scoreview.ScoreDocument
import org.lilyengine.scoreview.ScoreLookup
import org.lilyengine.scoreview.ScoreView
import org.lilyengine.scoreview.SourcePositions
import org.lilyengine.scoreview.rememberScoreViewState
import org.lilyengine.scoreview.voiceColor

/**
 * Point-and-click spike (roadmap M2): edit LilyPond source and see the score
 * re-render; tap a note to select its source, or move the text cursor to
 * highlight the note. Both directions keep the voices of a `\\` passage
 * apart.
 */
@OptIn(FlowPreview::class)
@Composable
fun ScoreScreen(engine: LilyPondEngine) {
    var text by remember { mutableStateOf(TextFieldValue(SCORE_SAMPLE)) }
    var document by remember { mutableStateOf<ScoreDocument?>(null) }
    var lookup by remember { mutableStateOf<ScoreLookup?>(null) }
    /** The source the current score was rendered from; the map is stale once the text differs. */
    var renderedText by remember { mutableStateOf("") }
    var midi by remember { mutableStateOf<java.io.File?>(null) }
    var status by remember { mutableStateOf("Rendering…") }
    var error by remember { mutableStateOf<Diagnostic?>(null) }
    var selection by remember { mutableStateOf<List<Grob>>(emptyList()) }
    var viewHeight by remember { mutableStateOf(0) }
    val viewState = rememberScoreViewState()
    val scope = rememberCoroutineScope()
    val focus = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    val player = remember { mutableStateOf<MediaPlayer?>(null) }
    val context = LocalContext.current
    val tools = remember { LilyTools(context) }
    /** Source offset of the selected note or rest; the selection follows it across re-renders. */
    var selectedOffset by remember { mutableStateOf<Int?>(null) }
    val undo = remember { ArrayDeque<String>() }
    var busy by remember { mutableStateOf(false) }
    // Python starts in the background, so the first edit doesn't wait for it.
    LaunchedEffect(Unit) { runCatching { tools.start() } }
    DisposableEffect(Unit) {
        onDispose {
            player.value?.release()
            document?.close()
        }
    }

    /** How long the last full render took: long scores get a fast preview after an edit. */
    var lastFullMillis by remember { mutableStateOf(0L) }
    var longSample by remember { mutableStateOf(false) }
    /** Set by an edit: the text it produced and the moment of the edited note. */
    val pendingEdit = remember { mutableStateOf<Pair<String, String>?>(null) }

    /** Shows a render's PDF and score map; returns false if it failed. */
    suspend fun show(r: LilyPondEngine.Result, source: String): Boolean {
        error = r.diagnostics.firstOrNull { it.severity == Diagnostic.Severity.ERROR }
        val pdf = r.outputs.firstOrNull()
        if (!r.success || pdf == null) {
            status = "LilyPond failed" + (error?.let { ": line ${it.line}: ${it.message}" } ?: "")
            return false
        }
        val newDoc = ScoreDocument.open(pdf)
        document?.close()
        document = newDoc
        lookup = r.scoreMap?.let { ScoreLookup(it) }
        renderedText = source
        selection = selectedOffset?.let { off ->
            val (line, char) = SourcePositions.locationOf(source, off)
            lookup?.objectsAtSource(line, char, "score.ly")
        }.orEmpty()
        // Keep the edited note in view: the preview and the whole score put it on different pages.
        val l = lookup
        selection.firstOrNull { it.type == "NoteHead" || it.type == "Rest" }?.let { g ->
            val h = newDoc.pageSizes.getOrNull(g.page - 1)?.second ?: return@let
            if (l != null) viewState.reveal(g.page, l.pageRect(g)[1] / h, viewHeight)
        }
        return true
    }

    // Re-render after typing pauses (at once after an edit button); a newer
    // edit cancels a render in progress. After an edit to a long score, the
    // bars around the edit are engraved first, then the whole score.
    LaunchedEffect(Unit) {
        snapshotFlow { text.text }
            .distinctUntilChanged()
            .debounce { if (pendingEdit.value?.first == it) 0L else 600L }
            .collectLatest { source ->
                val edit = pendingEdit.value?.takeIf { it.first == source }
                pendingEdit.value = null
                val l = lookup
                if (edit != null && l != null && lastFullMillis > PREVIEW_THRESHOLD_MS) {
                    status = "Engraving the bars around the edit…"
                    val window = l.windowAround(edit.second)
                    val p = engine.render(source, LilyPondEngine.Options(midi = false, scoreMap = true, window = window))
                    if (show(p, source)) {
                        status = "Preview: bars around the edit (${p.elapsedMillis} ms); whole score in progress…"
                    }
                } else {
                    status = "Rendering…"
                }
                val r = engine.render(source, LilyPondEngine.Options(midi = true, scoreMap = true))
                if (show(r, source)) {
                    lastFullMillis = r.elapsedMillis
                    midi = r.midi.firstOrNull()
                    status = "${document?.pageCount} page(s), ${r.elapsedMillis} ms" +
                        (error?.let { " — error on line ${it.line}: ${it.message}" } ?: "")
                }
            }
    }

    fun selectFromCursor(value: TextFieldValue) {
        val l = lookup ?: return
        if (value.text != renderedText) return // positions have moved since the render
        val (line, char) = SourcePositions.locationOf(value.text, value.selection.start)
        selection = l.objectsAtSource(line, char, "score.ly")
        selectedOffset = selection.firstNotNullOfOrNull { it.origin }
            ?.let { SourcePositions.offsetOf(value.text, it.line, it.char) }
        selection.firstOrNull { it.type == "NoteHead" || it.type == "Rest" }?.let { g ->
            val (_, top) = l.pageRect(g).let { it[0] to it[1] }
            val h = document?.pageSizes?.getOrNull(g.page - 1)?.second ?: return@let
            scope.launch { viewState.reveal(g.page, top / h, viewHeight) }
        }
    }

    fun onTap(g: Grob?) {
        val l = lookup ?: return
        if (g == null) { selection = emptyList(); selectedOffset = null; return }
        selection = l.sameOrigin(g)
        val origin = g.origin ?: return
        if (text.text != renderedText) return
        val start = SourcePositions.offsetOf(text.text, origin.line, origin.char)
        selectedOffset = start
        text = text.copy(selection = TextRange(start, SourcePositions.tokenEnd(text.text, start)))
        focus.requestFocus()
        keyboard?.hide() // a pen tap shouldn't bring up the keyboard
    }

    /** Runs a structural edit on the selected item and applies the result as one text change. */
    fun edit(at: Int? = null, action: suspend (source: String, offset: Int) -> TextEdit) {
        val offset = at ?: selectedOffset ?: return
        if (busy) return
        busy = true
        scope.launch {
            try {
                val before = text.text
                val e = action(before, offset)
                if (!e.isEmpty) {
                    undo.addLast(before)
                    val after = e.applyTo(before)
                    val moment = (lookup?.objectsAtSource(
                        SourcePositions.locationOf(before, offset).first,
                        SourcePositions.locationOf(before, offset).second, "score.ly",
                    ).orEmpty() + selection).firstNotNullOfOrNull { it.moment }
                    if (moment != null) pendingEdit.value = after to moment
                    // The item still starts at the same offset unless the edit removed it.
                    val keep = offset.coerceAtMost(after.length)
                    text = TextFieldValue(after, TextRange(keep, SourcePositions.tokenEnd(after, keep)))
                }
            } catch (ex: LilyTools.EditException) {
                status = "Can't do that here: ${ex.message}"
            } finally {
                busy = false
            }
        }
    }

    fun undoLast() {
        val previous = undo.removeLastOrNull() ?: return
        text = TextFieldValue(previous, TextRange(selectedOffset?.coerceAtMost(previous.length) ?: 0))
    }

    fun jumpTo(d: Diagnostic) {
        val start = SourcePositions.offsetOf(text.text, d.line, (d.column - 1).coerceAtLeast(0))
        text = text.copy(selection = TextRange(start))
        focus.requestFocus()
    }

    BoxWithConstraints(Modifier.fillMaxSize()) {
        val landscape = maxWidth > maxHeight
        val score: @Composable (Modifier) -> Unit = { m ->
            Column(m) {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(status, fontSize = 13.sp, modifier = Modifier.weight(1f).padding(top = 10.dp))
                    error?.let { e -> TextButton(onClick = { jumpTo(e) }) { Text("Go to error") } }
                    TextButton(onClick = {
                        undo.clear(); selectedOffset = null; selection = emptyList()
                        longSample = !longSample
                        text = TextFieldValue(if (longSample) LONG_SAMPLE else SCORE_SAMPLE)
                    }) { Text(if (longSample) "Short sample" else "Long sample") }
                    Button(enabled = midi != null, onClick = {
                        val current = player.value
                        if (current != null) {
                            current.release(); player.value = null
                        } else midi?.let { f ->
                            player.value = MediaPlayer().apply {
                                setDataSource(f.path)
                                setOnCompletionListener { it.release(); player.value = null }
                                prepare(); start()
                            }
                        }
                    }) { Text(if (player.value != null) "Stop" else "Play") }
                }
                SelectionInfo(selection, lookup)
                EditBar(
                    selected = selection.firstOrNull { it.type == "NoteHead" || it.type == "Rest" }
                        ?.takeIf { text.text == renderedText },
                    canUndo = undo.isNotEmpty(),
                    onPitch = { p -> edit { src, off -> tools.setPitch(src, off, p) } },
                    onStep = { by -> edit { src, off -> tools.step(src, off, by) } },
                    onDuration = { d -> edit { src, off -> tools.setDuration(src, off, d) } },
                    onDelete = { edit { src, off -> tools.delete(src, off) } },
                    onUndo = ::undoLast,
                )
                ScoreView(
                    document, lookup, selection, ::onTap,
                    modifier = Modifier.fillMaxWidth().weight(1f).onSizeChanged { viewHeight = it.height },
                    state = viewState,
                    onDragSteps = { g, steps ->
                        val o = g.origin
                        if (o != null && text.text == renderedText) {
                            val at = SourcePositions.offsetOf(text.text, o.line, o.char)
                            selectedOffset = at
                            edit(at) { src, off -> tools.step(src, off, steps) }
                        }
                    },
                )
            }
        }
        val editor: @Composable (Modifier) -> Unit = { m ->
            Box(m.background(Color(0xFFFAFAFA)).verticalScroll(rememberScrollState()).padding(8.dp)) {
                BasicTextField(
                    value = text,
                    onValueChange = { v ->
                        val moved = v.selection != text.selection
                        text = v
                        if (moved) selectFromCursor(v)
                    },
                    textStyle = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 14.sp),
                    // Password mode turns off word suggestions and learning (the text isn't hidden).
                    keyboardOptions = KeyboardOptions(autoCorrectEnabled = false, keyboardType = KeyboardType.Password),
                    modifier = Modifier.fillMaxWidth().focusRequester(focus),
                )
            }
        }
        if (landscape) {
            Row(Modifier.fillMaxSize()) {
                editor(Modifier.weight(0.4f).fillMaxHeight())
                score(Modifier.weight(0.6f).fillMaxHeight())
            }
        } else {
            Column(Modifier.fillMaxSize()) {
                score(Modifier.weight(0.6f).fillMaxWidth())
                editor(Modifier.weight(0.4f).fillMaxWidth())
            }
        }
    }
}

/** Pitch, duration and delete buttons for the selected note (or rest). */
@Composable
private fun EditBar(
    selected: Grob?,
    canUndo: Boolean,
    onPitch: (LilyTools.Pitch) -> Unit,
    onStep: (Int) -> Unit,
    onDuration: (String) -> Unit,
    onDelete: () -> Unit,
    onUndo: () -> Unit,
) {
    val pitch = selected?.pitch?.let {
        LilyTools.Pitch(it.notename, (ScoreMapRational.parse(it.alteration) * 2).roundToInt(), it.octave)
    }
    Row(
        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        @Composable
        fun B(label: String, enabled: Boolean = true, onClick: () -> Unit) =
            OutlinedButton(onClick, enabled = enabled, contentPadding = PaddingValues(horizontal = 10.dp)) { Text(label) }
        B("▲", pitch != null) { onStep(+1) }
        B("▼", pitch != null) { onStep(-1) }
        B("♯", pitch != null) { pitch?.let { onPitch(it.copy(semitones = 1)) } }
        B("♭", pitch != null) { pitch?.let { onPitch(it.copy(semitones = -1)) } }
        B("♮", pitch != null) { pitch?.let { onPitch(it.copy(semitones = 0)) } }
        for (d in listOf("1", "2", "4", "8", "16")) B(d, selected != null) { onDuration(d) }
        B("Dot", selected?.duration != null) {
            val d = selected?.duration ?: return@B
            onDuration(if (d.endsWith(".")) d.trimEnd('.') else "$d.")
        }
        B("Delete", selected != null, onDelete)
        B("Undo", canUndo, onUndo)
    }
}

private object ScoreMapRational {
    fun parse(s: String): Double {
        val slash = s.indexOf('/')
        return if (slash < 0) s.toDouble() else s.substring(0, slash).toDouble() / s.substring(slash + 1).toDouble()
    }
}

@Composable
private fun SelectionInfo(selection: List<Grob>, lookup: ScoreLookup?) {
    val g = selection.firstOrNull { it.type == "NoteHead" || it.type == "Rest" } ?: selection.firstOrNull()
    val voices = remember(lookup) { lookup?.map?.grobs?.mapNotNull { it.voice }?.distinct()?.sorted().orEmpty() }
    Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 2.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        if (g == null) {
            Text("Tap a note, or move the cursor in the source.", fontSize = 13.sp, color = Color.Gray)
            return@Row
        }
        Box(Modifier.padding(top = 3.dp).size(12.dp).background(voiceColor(g.voice, voices)))
        val parts = buildList {
            add(g.type)
            g.pitch?.let { add(it.name) }
            g.duration?.let { add("duration $it") }
            g.voice?.let { add(g.voiceId?.takeIf { id -> id.isNotEmpty() }?.let { id -> "voice \"$id\"" } ?: "unnamed voice") }
            g.moment?.let { add("at $it") }
            g.origin?.let { add("line ${it.line}, column ${it.char + 1}") }
        }
        Text(parts.joinToString(" · "), fontSize = 13.sp)
    }
}

/** Above this, an edit first engraves only the bars around it (see [ScoreLookup.windowAround]). */
private const val PREVIEW_THRESHOLD_MS = 1500L

/** 400 bars of piano music with two voices in the right hand: about 15 pages. */
private val LONG_SAMPLE = pianoPiece(400)

/** Piano music with two voices in the right hand, and a chord, over a few systems. */
private val SCORE_SAMPLE = """
\version "2.26.0"
\header { title = "Point and click" }
upper = \relative c'' {
  \key g \major \time 4/4
  << { b4 c d2 | e4 d c b | a2 b4 c | d1 } \\ { g,2 fis | g4 a fis g | e2 g4 a | <fis a>1 } >>
  g4 fis e d | c2 b | a4 b c a | g1 \bar "|."
}
lower = \relative c {
  \clef bass \key g \major
  g2 d' | c b | c g | d'1 |
  e4 d c b | a2 g | d' d, | g1
}
\score {
  \new PianoStaff << \new Staff \upper \new Staff \lower >>
  \layout { }
}
""".trimStart()
