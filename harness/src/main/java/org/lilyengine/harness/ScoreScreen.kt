package org.lilyengine.harness

import android.media.MediaPlayer
import androidx.compose.foundation.background
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.height
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.RadioButton
import androidx.compose.ui.Alignment
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
import androidx.compose.runtime.mutableIntStateOf
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
import org.lilyengine.midi.ChordCollector
import org.lilyengine.midi.MidiKeyboard
import org.lilyengine.midi.MidiFile
import org.lilyengine.midi.MidiPlayer
import kotlinx.coroutines.delay
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.first
import androidx.compose.runtime.collectAsState

/**
 * Point-and-click spike (roadmap M2): edit LilyPond source and see the score
 * re-render; tap a note to select its source, or move the text cursor to
 * highlight the note. Both directions keep the voices of a `\\` passage
 * apart.
 */
@OptIn(FlowPreview::class)
@Composable
fun ScoreScreen(engine: LilyPondEngine, keyboard: MidiKeyboard) {
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
    val softKeyboard = LocalSoftwareKeyboardController.current
    /** What is playing, if anything; the cursor shows the notes sounding in it. */
    var playback by remember { mutableStateOf<Playback?>(null) }
    var cursor by remember { mutableStateOf<List<Grob>>(emptyList()) }
    /** With a keyboard connected: play through it rather than the tablet's speaker. */
    var playOnKeyboard by remember { mutableStateOf(true) }
    val canSend by keyboard.canSend.collectAsState()
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
            playback?.stop()
            document?.close()
        }
    }

    /** How long the last full render took: long scores get a fast preview after an edit. */
    var lastFullMillis by remember { mutableStateOf(0L) }
    var longSample by remember { mutableStateOf(false) }
    /** A MIDI file picked for import, waiting for the options dialog. */
    var midiToImport by remember { mutableStateOf<java.io.File?>(null) }
    val pickMidi = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        val f = java.io.File(context.cacheDir, "import.mid")
        runCatching {
            context.contentResolver.openInputStream(uri)!!.use { i -> f.outputStream().use { i.copyTo(it) } }
            midiToImport = f
        }.onFailure { status = "Can't read that file: ${it.message}" }
    }
    /** Note entry: notes played or tapped go in after the selected note. */
    var entering by remember { mutableStateOf(false) }
    var entryDuration by remember { mutableStateOf("4") }
    var entryDot by remember { mutableStateOf(false) }
    /** Notes and chords waiting to be entered (MIDI keys; empty for a rest), in playing order. */
    val entries = remember { Channel<List<Int>>(Channel.UNLIMITED) }
    val connectedKeyboard by keyboard.connected.collectAsState()
    DisposableEffect(keyboard, entering) {
        if (!entering) return@DisposableEffect onDispose { }
        val chords = ChordCollector { keys -> entries.trySend(keys) }
        val listener: (MidiKeyboard.Event) -> Unit = { e ->
            when (e) {
                is MidiKeyboard.Event.NoteOn -> chords.noteOn(e.key)
                is MidiKeyboard.Event.NoteOff -> chords.noteOff(e.key)
                else -> {}
            }
        }
        keyboard.addListener(listener)
        onDispose { keyboard.removeListener(listener) }
    }
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
        softKeyboard?.hide() // a pen tap shouldn't bring up the keyboard
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

    // Enters queued notes one at a time, each after the one before.
    LaunchedEffect(Unit) {
        for (keys in entries) {
            snapshotFlow { busy }.first { !it }
            busy = true
            try {
                val before = text.text
                val selected = selectedOffset?.takeIf { it < before.length }
                val at = selected ?: text.selection.start
                val duration = entryDuration + if (entryDot) "." else ""
                val r = tools.insert(before, at, keys, duration, after = selected != null)
                undo.addLast(before)
                val after = r.edit.applyTo(before)
                selection.firstNotNullOfOrNull { it.moment }?.let { pendingEdit.value = after to it }
                selectedOffset = r.start
                text = TextFieldValue(after, TextRange(r.start, SourcePositions.tokenEnd(after, r.start)))
            } catch (ex: LilyTools.EditException) {
                status = "Can't enter a note here: ${ex.message}"
            } finally {
                busy = false
            }
        }
    }

    // The playback cursor: the notes sounding now, in every voice, kept in view.
    LaunchedEffect(playback) {
        val p = playback ?: return@LaunchedEffect
        while (p.active) {
            val m = p.file.wholeNotes(p.file.tick(p.positionMicros))
            val l = lookup
            val now = l?.soundingAt(m).orEmpty()
            cursor = now
            val top = now.minWithOrNull(compareBy<Grob>({ it.page }, { -it.bbox[3] }))
            val h = top?.let { document?.pageSizes?.getOrNull(it.page - 1)?.second }
            if (top != null && h != null && l != null) viewState.reveal(top.page, l.pageRect(top)[1] / h, viewHeight)
            delay(33)
        }
        if (playback === p) { playback = null; cursor = emptyList() }
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

    midiToImport?.let { file ->
        MidiImportDialog(
            onDismiss = { midiToImport = null },
            onImport = { options ->
                midiToImport = null
                status = "Importing MIDI…"
                scope.launch {
                    try {
                        val imported = tools.importMidi(file, options)
                        undo.addLast(text.text)
                        selectedOffset = null
                        selection = emptyList()
                        text = TextFieldValue(imported.text)
                    } catch (e: LilyTools.EditException) {
                        status = "MIDI import failed: ${e.message}"
                    }
                }
            },
        )
    }

    BoxWithConstraints(Modifier.fillMaxSize()) {
        val landscape = maxWidth > maxHeight
        val piano: @Composable () -> Unit = {
            PianoBar(onKey = { k -> entries.trySend(listOf(k)); keyboard.play(listOf(k), 300) })
        }
        val score: @Composable (Modifier) -> Unit = { m ->
            Column(m) {
                Row(
                    Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 8.dp, vertical = 2.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
                ) {
                    error?.let { e -> TextButton(onClick = { jumpTo(e) }) { Text("Go to error") } }
                    if (entering) Button(onClick = { entering = false }) { Text("✓ Enter notes") }
                    else TextButton(onClick = { entering = true }) { Text("Enter notes") }
                    TextButton(onClick = {
                        pickMidi.launch(arrayOf("audio/midi", "audio/x-midi", "audio/sp-midi", "application/octet-stream"))
                    }) { Text("Import MIDI") }
                    TextButton(onClick = {
                        undo.clear(); selectedOffset = null; selection = emptyList()
                        longSample = !longSample
                        text = TextFieldValue(if (longSample) LONG_SAMPLE else SCORE_SAMPLE)
                    }) { Text(if (longSample) "Short sample" else "Long sample") }
                    if (canSend) TextButton(onClick = { playOnKeyboard = !playOnKeyboard }) {
                        Text(if (playOnKeyboard) "Sound: keyboard" else "Sound: tablet")
                    }
                    Button(enabled = midi != null || playback != null, onClick = {
                        val current = playback
                        if (current != null) {
                            current.stop(); playback = null; cursor = emptyList()
                        } else midi?.let { f ->
                            val start = selection.firstNotNullOfOrNull { it.momentValue }
                            playback = runCatching {
                                Playback.start(f, start, if (playOnKeyboard && canSend) keyboard else null)
                            }.onFailure { status = "Can't play: ${it.message}" }.getOrNull()
                        }
                    }) { Text(if (playback != null) "Stop" else if (selection.isNotEmpty()) "Play from here" else "Play") }
                }
                Text(status, fontSize = 13.sp, modifier = Modifier.padding(horizontal = 8.dp))
                SelectionInfo(selection, lookup)
                if (entering) EntryBar(
                    duration = entryDuration,
                    dot = entryDot,
                    keyboardName = connectedKeyboard?.let(MidiKeyboard::name),
                    hasPlace = selectedOffset != null,
                    canUndo = undo.isNotEmpty(),
                    onDuration = { entryDuration = it },
                    onDot = { entryDot = !entryDot },
                    onRest = { entries.trySend(emptyList()) },
                    onUndo = ::undoLast,
                ) else EditBar(
                    selected = selection.firstOrNull { it.type == "NoteHead" || it.type == "Rest" }
                        ?.takeIf { text.text == renderedText },
                    canUndo = undo.isNotEmpty(),
                    onPitch = { p -> edit { src, off -> tools.setPitch(src, off, p) } },
                    onStep = { by -> edit { src, off -> tools.step(src, off, by) } },
                    onDuration = { d -> edit { src, off -> tools.setDuration(src, off, d) } },
                    onDelete = { edit { src, off -> tools.delete(src, off) } },
                    onUndo = ::undoLast,
                )
                if (entering && !landscape) piano()
                ScoreView(
                    document, lookup, if (playback != null) cursor else selection, ::onTap,
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
          Column(m) {
            Box(Modifier.weight(1f).background(Color(0xFFFAFAFA)).verticalScroll(rememberScrollState()).padding(8.dp)) {
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
            if (entering && landscape) piano()
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

/**
 * A MIDI file playing, through a MIDI keyboard ([MidiPlayer]) or the
 * tablet's own synthesizer ([MediaPlayer]), from a point in the music.
 */
private class Playback private constructor(
    val file: MidiFile,
    private val sequencer: MidiPlayer?,
    private val media: MediaPlayer?,
) {
    @Volatile private var stopped = false

    val active: Boolean get() = !stopped && (sequencer?.playing ?: runCatching { media!!.isPlaying }.getOrDefault(false))

    val positionMicros: Long
        get() = sequencer?.positionMicros ?: runCatching { media!!.currentPosition * 1000L }.getOrDefault(0L)

    fun stop() {
        if (stopped) return
        stopped = true
        sequencer?.stop()
        media?.release()
    }

    companion object {
        /** Starts at [fromWholeNotes] into the music (null: the start), on [keyboard] if given. */
        fun start(midi: java.io.File, fromWholeNotes: Double?, keyboard: MidiKeyboard?): Playback {
            val file = MidiFile.read(midi)
            val from = fromWholeNotes?.let { file.micros((it * 4 * file.division).toLong()) } ?: 0L
            return if (keyboard != null) {
                val s = MidiPlayer(file, { keyboard.send(it) })
                s.start(from)
                Playback(file, s, null)
            } else {
                val mp = MediaPlayer()
                mp.setDataSource(midi.path)
                mp.prepare()
                if (from > 0) mp.seekTo((from / 1000).toInt())
                mp.start()
                Playback(file, null, mp)
            }
        }
    }
}

/** Options for midi2ly: how finely to quantise, and whether triplets are allowed. */
@Composable
private fun MidiImportDialog(onDismiss: () -> Unit, onImport: (LilyTools.MidiImportOptions) -> Unit) {
    var quant by remember { mutableStateOf("16") }
    var triplets by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Import MIDI") },
        text = {
            Column {
                Text("Round note starts and lengths to:")
                for ((q, label) in listOf("8" to "eighth notes", "16" to "sixteenth notes", "32" to "thirty-second notes")) {
                    Row(Modifier.fillMaxWidth().clickable { quant = q }, verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(selected = quant == q, onClick = { quant = q })
                        Text(label)
                    }
                }
                Row(Modifier.fillMaxWidth().clickable { triplets = !triplets }, verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = triplets, onCheckedChange = { triplets = it })
                    Text("Allow triplets")
                }
                Text(
                    "Uses LilyPond's midi2ly. Each track becomes a staff; notes that sound together become chords.",
                    fontSize = 12.sp, color = Color.Gray,
                )
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val tupletUnit = (quant.toInt() / 2).coerceAtLeast(4)
                onImport(
                    LilyTools.MidiImportOptions(
                        durationQuant = quant, startQuant = quant,
                        tuplets = if (triplets) listOf("$tupletUnit*2/3", "${tupletUnit * 2}*2/3") else emptyList(),
                    ),
                )
            }) { Text("Import") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/**
 * Note entry: the duration for new notes, a rest, and undo. Notes come
 * from a MIDI keyboard (a chord played on it comes in whole) or [PianoBar].
 */
@Composable
private fun EntryBar(
    duration: String,
    dot: Boolean,
    keyboardName: String?,
    hasPlace: Boolean,
    canUndo: Boolean,
    onDuration: (String) -> Unit,
    onDot: () -> Unit,
    onRest: () -> Unit,
    onUndo: () -> Unit,
) {
    Column(Modifier.fillMaxWidth().background(ENTRY_BACKGROUND).padding(horizontal = 4.dp, vertical = 2.dp)) {
        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(2.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("New notes:", fontSize = 13.sp, modifier = Modifier.padding(horizontal = 4.dp))
            for (d in listOf("1", "2", "4", "8", "16", "32")) {
                if (d == duration) Button(onClick = { onDuration(d) }, contentPadding = PaddingValues(horizontal = 10.dp)) { Text(d) }
                else OutlinedButton(onClick = { onDuration(d) }, contentPadding = PaddingValues(horizontal = 10.dp)) { Text(d) }
            }
            if (dot) Button(onClick = onDot, contentPadding = PaddingValues(horizontal = 10.dp)) { Text("Dot") }
            else OutlinedButton(onClick = onDot, contentPadding = PaddingValues(horizontal = 10.dp)) { Text("Dot") }
            OutlinedButton(onClick = onRest, contentPadding = PaddingValues(horizontal = 10.dp)) { Text("Rest") }
            OutlinedButton(onClick = onUndo, enabled = canUndo, contentPadding = PaddingValues(horizontal = 10.dp)) { Text("Undo") }
            Text(
                (if (hasPlace) "After the selected note. " else "At the cursor. ") +
                    (keyboardName?.let { "Play on $it, or tap the piano." } ?: "Tap the piano, or plug in a MIDI keyboard."),
                fontSize = 12.sp, color = Color.DarkGray, modifier = Modifier.padding(horizontal = 6.dp),
            )
        }
    }
}

private val ENTRY_BACKGROUND = Color(0xFFEEF3FA)

/** The on-screen piano with octave buttons. */
@Composable
private fun PianoBar(onKey: (Int) -> Unit) {
    var low by remember { mutableIntStateOf(60) }
    Row(Modifier.fillMaxWidth().background(ENTRY_BACKGROUND), verticalAlignment = Alignment.CenterVertically) {
        TextButton(onClick = { low = (low - 12).coerceAtLeast(24) }) { Text("◀") }
        Piano(low, octaves = 2, onKey = onKey, modifier = Modifier.weight(1f).height(72.dp))
        TextButton(onClick = { low = (low + 12).coerceAtMost(84) }) { Text("▶") }
    }
}

/** A piano keyboard from [low] (a C) over [octaves] octaves and one more C; taps give MIDI keys. */
@Composable
private fun Piano(low: Int, octaves: Int, onKey: (Int) -> Unit, modifier: Modifier = Modifier) {
    val whiteSteps = intArrayOf(0, 2, 4, 5, 7, 9, 11)
    val whites = octaves * 7 + 1
    fun whiteKey(i: Int) = low + 12 * (i / 7) + whiteSteps[i % 7]
    fun hasBlackAfter(i: Int) = i < whites - 1 && i % 7 !in intArrayOf(2, 6)
    Canvas(
        modifier.pointerInput(low) {
            detectTapGestures { p ->
                val w = size.width.toFloat() / whites
                val i = (p.x / w).toInt().coerceIn(0, whites - 1)
                val key = if (p.y < size.height * 0.6f) {
                    val frac = p.x / w - i
                    when {
                        frac > 0.7f && hasBlackAfter(i) -> whiteKey(i) + 1
                        frac < 0.3f && i > 0 && hasBlackAfter(i - 1) -> whiteKey(i - 1) + 1
                        else -> whiteKey(i)
                    }
                } else whiteKey(i)
                onKey(key)
            }
        },
    ) {
        val w = size.width / whites
        for (i in 0 until whites) {
            drawRect(if (whiteKey(i) == 60) Color(0xFFFFF4D6) else Color.White, Offset(i * w, 0f), Size(w, size.height))
            drawRect(Color.Gray, Offset(i * w, 0f), Size(w, size.height), style = Stroke(1f))
        }
        for (i in 0 until whites) if (hasBlackAfter(i)) {
            drawRect(Color.Black, Offset((i + 0.7f) * w, 0f), Size(0.6f * w, size.height * 0.6f))
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
