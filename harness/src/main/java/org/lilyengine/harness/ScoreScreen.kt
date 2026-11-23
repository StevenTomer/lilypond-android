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
import androidx.compose.ui.text.style.TextOverflow
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
import org.lilyengine.engine.RenderProgress
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
import kotlinx.coroutines.coroutineScope
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
    /** The log of the last render that failed, for sharing. */
    var failedLog by remember { mutableStateOf<String?>(null) }
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
    /** Where playback sounds, as chosen; null: on the keyboard if one is connected, else the tablet. */
    var soundChoice by remember { mutableStateOf<Sound?>(null) }
    val canSend by keyboard.canSend.collectAsState()
    val appsListening by keyboard.appsListening.collectAsState()
    val sounds = listOfNotNull(Sound.TABLET, Sound.KEYBOARD.takeIf { canSend }, Sound.APPS.takeIf { appsListening > 0 })
    val sound = soundChoice?.takeIf { it in sounds } ?: if (canSend) Sound.KEYBOARD else Sound.TABLET
    val context = LocalContext.current
    val tools = remember { LilyTools(context) }
    /** Source offset of the selected note or rest; the selection follows it across re-renders. */
    var selectedOffset by remember { mutableStateOf<Int?>(null) }
    /**
     * A range drawn with the pen: the source offsets of its notes and rests
     * (two or more), which the selection follows across re-renders and edits.
     */
    var range by remember { mutableStateOf<List<Int>>(emptyList()) }
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
    /** A MusicXML file picked for import, waiting for the options dialog. */
    var musicXmlToImport by remember { mutableStateOf<java.io.File?>(null) }
    val pickImport = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        runCatching {
            val bytes = context.contentResolver.openInputStream(uri)!!.use { it.readBytes() }
            // Told apart by their first bytes, as file names and types are often wrong.
            when {
                bytes.size >= 4 && String(bytes, 0, 4, Charsets.ISO_8859_1) == "MThd" ->
                    midiToImport = java.io.File(context.cacheDir, "import.mid").apply { writeBytes(bytes) }
                bytes.size >= 2 && bytes[0] == 'P'.code.toByte() && bytes[1] == 'K'.code.toByte() ->
                    musicXmlToImport = java.io.File(context.cacheDir, "import.mxl").apply { writeBytes(bytes) }
                String(bytes, 0, minOf(bytes.size, 4096), Charsets.UTF_8).let { "<score-partwise" in it || "<score-timewise" in it } ->
                    musicXmlToImport = java.io.File(context.cacheDir, "import.musicxml").apply { writeBytes(bytes) }
                else -> status = "That isn't a MIDI or MusicXML file."
            }
        }.onFailure { status = "Can't read that file: ${it.message}" }
    }
    /** Note entry: notes played or tapped go in after the selected note. */
    var entering by remember { mutableStateOf(false) }
    var entryDuration by remember { mutableStateOf("4") }
    var entryDot by remember { mutableStateOf(false) }
    /** New notes take the place of rests after the selected note (as in a voice just added). */
    var fillRests by remember { mutableStateOf(true) }
    /** Notes, chords and rests waiting to be entered, in playing order. */
    val entries = remember { Channel<Entry>(Channel.UNLIMITED) }
    val connectedKeyboard by keyboard.connected.collectAsState()
    DisposableEffect(keyboard, entering) {
        if (!entering) return@DisposableEffect onDispose { }
        val chords = ChordCollector { keys -> entries.trySend(Entry.Keys(keys)) }
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
    /** Set by a MIDI import: its text, of which the first bars are shown before the whole score. */
    val pendingImport = remember { mutableStateOf<String?>(null) }

    /**
     * Renders the whole score, showing in [status] how far LilyPond has got,
     * since a long score (an imported orchestral MIDI file) can take minutes.
     */
    suspend fun renderWhole(source: String, what: String): LilyPondEngine.Result = coroutineScope {
        val progress = RenderProgress()
        val start = System.nanoTime()
        val ticker = launch {
            delay(1500) // most renders are done by now
            while (true) {
                status = "$what: ${progress.phase} (${(System.nanoTime() - start) / 1_000_000_000} s)…"
                delay(500)
            }
        }
        try {
            engine.render(
                source,
                LilyPondEngine.Options(midi = true, scoreMap = true, timeoutSeconds = WHOLE_SCORE_TIMEOUT_S, onOutput = progress::onOutput),
            )
        } finally {
            ticker.cancel()
        }
    }

    /** Shows a render's PDF and score map; returns false if it failed. */
    suspend fun show(r: LilyPondEngine.Result, source: String): Boolean {
        error = r.diagnostics.firstOrNull { it.severity == Diagnostic.Severity.ERROR }
        val pdf = r.outputs.firstOrNull()
        if (!r.success || pdf == null) {
            status = "LilyPond failed" + when {
                error != null -> ": line ${error!!.line}: ${error!!.message}"
                r.log.trimEnd().endsWith("s]") && "[timed out after" in r.log ->
                    ": it took longer than ${WHOLE_SCORE_TIMEOUT_S / 60} minutes and was stopped"
                r.exitCode >= 128 -> ": it crashed (signal ${r.exitCode - 128})"
                else -> " (exit code ${r.exitCode})"
            }
            failedLog = r.log
            return false
        }
        failedLog = null
        val newDoc = ScoreDocument.open(pdf)
        document?.close()
        document = newDoc
        lookup = r.scoreMap?.let { ScoreLookup(it) }
        renderedText = source
        selection = (range.ifEmpty { listOfNotNull(selectedOffset) }).flatMap { off ->
            val (line, char) = SourcePositions.locationOf(source, off)
            lookup?.objectsAtSource(line, char, "score.ly").orEmpty()
        }.distinct()
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
                val imported = pendingImport.value == source
                pendingImport.value = null
                val l = lookup
                if (imported) {
                    // An imported file can be long: show its beginning first.
                    status = "Engraving the first bars…"
                    val p = engine.render(source, LilyPondEngine.Options(midi = false, scoreMap = true, window = "0" to FIRST_LOOK_WHOLE_NOTES))
                    if (show(p, source)) {
                        status = "Preview: the first bars (${p.elapsedMillis} ms); whole score in progress…"
                    }
                } else if (edit != null && l != null && lastFullMillis > PREVIEW_THRESHOLD_MS) {
                    status = "Engraving the bars around the edit…"
                    val window = l.windowAround(edit.second)
                    val p = engine.render(source, LilyPondEngine.Options(midi = false, scoreMap = true, window = window))
                    if (show(p, source)) {
                        status = "Preview: bars around the edit (${p.elapsedMillis} ms); whole score in progress…"
                    }
                } else {
                    status = "Rendering…"
                }
                val r = renderWhole(source, if (lookup != null && renderedText == source) "Whole score" else "Rendering")
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
        range = emptyList()
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
        range = emptyList()
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

    /** A range drawn with the pen: its notes and rests are selected. */
    fun onSelectRange(grobs: List<Grob>) {
        if (text.text != renderedText) return
        val items = grobs.filter { it.origin != null }
        val offsets = items.map { SourcePositions.offsetOf(text.text, it.origin!!.line, it.origin!!.char) }.distinct().sorted()
        when (offsets.size) {
            0 -> onTap(null)
            1 -> onTap(items.first())
            else -> {
                range = offsets
                selection = grobs
                selectedOffset = offsets.first()
                text = text.copy(selection = TextRange(offsets.first(), SourcePositions.tokenEnd(text.text, offsets.last())))
                softKeyboard?.hide()
            }
        }
    }

    /** Passages whose voices aren't equally long (checked shortly after each change). */
    var uneven by remember { mutableStateOf<List<LilyTools.VoicePassage>>(emptyList()) }
    LaunchedEffect(text.text) {
        delay(VOICE_CHECK_DELAY_MS)
        uneven = runCatching { tools.voiceLengths(text.text) }.getOrDefault(emptyList())
    }

    /** The marks on the selected note, or on every item of the range (articulations, dynamics, slurs…), as LilyTools.toggle names them. */
    var selectedMarks by remember { mutableStateOf(emptySet<String>()) }
    LaunchedEffect(selectedOffset, range, text.text) {
        val at = range.ifEmpty { listOfNotNull(selectedOffset) }.filter { it < text.text.length }
        selectedMarks = if (at.isEmpty() || at.size > MAX_MARKS_LOOKUPS) emptySet()
        else runCatching { at.map { tools.marks(text.text, it) }.reduce { a, b -> a intersect b } }.getOrDefault(emptySet())
    }

    /** Runs an edit on every item of the range, as one text change; the range follows its items. */
    fun rangeEdit(action: suspend (source: String, offsets: List<Int>) -> LilyTools.RangeEdit) {
        if (busy || range.size < 2) return
        busy = true
        scope.launch {
            try {
                val before = text.text
                val r = action(before, range)
                if (!r.edit.isEmpty) {
                    undo.addLast(before)
                    val after = r.edit.applyTo(before)
                    selection.firstNotNullOfOrNull { it.moment }?.let { pendingEdit.value = after to it }
                    range = r.offsets.takeIf { it.size >= 2 }.orEmpty()
                    selectedOffset = r.offsets.firstOrNull()
                    if (r.offsets.isEmpty()) selection = emptyList()
                    text = TextFieldValue(
                        after,
                        if (r.offsets.isEmpty()) TextRange(0)
                        else TextRange(r.offsets.first(), SourcePositions.tokenEnd(after, r.offsets.last())),
                    )
                }
            } catch (ex: LilyTools.EditException) {
                status = "Can't do that here: ${ex.message}"
            } finally {
                busy = false
            }
        }
    }

    /** Adds a second voice over the range (or the selected note): rests to be filled by note entry. */
    fun addVoice() {
        val offsets = range.ifEmpty { listOfNotNull(selectedOffset) }
        if (busy || offsets.isEmpty()) return
        busy = true
        scope.launch {
            try {
                val before = text.text
                val r = tools.addVoice(before, offsets)
                undo.addLast(before)
                val after = r.edit.applyTo(before)
                selection.firstNotNullOfOrNull { it.moment }?.let { pendingEdit.value = after to it }
                range = emptyList()
                selectedOffset = r.start
                text = TextFieldValue(after, TextRange(r.start, SourcePositions.tokenEnd(after, r.start)))
                entering = true
                fillRests = true
                status = "Added a voice: play or tap its notes, and they take the place of the rests."
            } catch (ex: LilyTools.EditException) {
                status = "Can't add a voice here: ${ex.message}"
            } finally {
                busy = false
            }
        }
    }

    /** A slur or hairpin over the range: from its first to its last note or rest in each voice. */
    fun spanRange(kind: String) {
        val r = renderedText
        val pairs = selection.filter { (it.type == "NoteHead" || it.type == "Rest") && it.origin != null }
            .groupBy { it.voiceId.orEmpty() }
            .mapNotNull { (_, gs) ->
                val offs = gs.map { SourcePositions.offsetOf(r, it.origin!!.line, it.origin!!.char) }
                (offs.min() to offs.max()).takeIf { it.first != it.second }
            }
        if (pairs.isEmpty()) {
            status = "Select two or more notes in a voice for a slur or hairpin."
            return
        }
        rangeEdit { src, offsets -> tools.span(src, pairs, kind, offsets) }
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
        for (entry in entries) {
            snapshotFlow { busy }.first { !it }
            busy = true
            try {
                val before = text.text
                val selected = selectedOffset?.takeIf { it < before.length }
                val at = selected ?: text.selection.start
                val duration = entryDuration + if (entryDot) "." else ""
                val r = when (entry) {
                    is Entry.Keys -> tools.insert(before, at, entry.keys, duration, after = selected != null, fill = fillRests)
                    is Entry.Step -> tools.insertStep(before, at, entry.step, entry.octave, duration, after = selected != null, fill = fillRests)
                        .also { keyboard.play(it.keys, 300) } // the note tapped on the staff, as entered
                }
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
        range = emptyList()
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
                        // Until the whole score is engraved (minutes, for a big file), Play
                        // plays the MIDI file itself.
                        midi = file.copyTo(java.io.File(context.cacheDir, "import-playback.mid"), overwrite = true)
                        undo.addLast(text.text)
                        selectedOffset = null
                        selection = emptyList()
                        pendingImport.value = imported.text
                        text = TextFieldValue(imported.text)
                    } catch (e: LilyTools.EditException) {
                        status = "MIDI import failed: ${e.message}"
                    }
                }
            },
        )
    }

    musicXmlToImport?.let { file ->
        MusicXmlImportDialog(
            onDismiss = { musicXmlToImport = null },
            onImport = { options ->
                musicXmlToImport = null
                status = "Importing MusicXML…"
                scope.launch {
                    try {
                        val imported = tools.importMusicXml(file, options)
                        midi = null // the old score's; Play waits for the whole score's
                        undo.addLast(text.text)
                        selectedOffset = null
                        selection = emptyList()
                        range = emptyList()
                        pendingImport.value = imported.text
                        text = TextFieldValue(imported.text)
                    } catch (e: LilyTools.EditException) {
                        status = "MusicXML import failed: ${e.message}"
                    }
                }
            },
        )
    }

    BoxWithConstraints(Modifier.fillMaxSize()) {
        val landscape = maxWidth > maxHeight
        val piano: @Composable () -> Unit = {
            PianoBar(onKey = { k -> keyboard.tap(k) })
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
                        pickImport.launch(IMPORT_TYPES)
                    }) { Text("Import") }
                    TextButton(onClick = {
                        undo.clear(); selectedOffset = null; selection = emptyList()
                        longSample = !longSample
                        text = TextFieldValue(if (longSample) LONG_SAMPLE else SCORE_SAMPLE)
                    }) { Text(if (longSample) "Short sample" else "Long sample") }
                    if (sounds.size > 1) TextButton(onClick = { soundChoice = sounds[(sounds.indexOf(sound) + 1) % sounds.size] }) {
                        Text("Sound: ${sound.label}")
                    }
                    Button(enabled = midi != null || playback != null, onClick = {
                        val current = playback
                        if (current != null) {
                            current.stop(); playback = null; cursor = emptyList()
                        } else midi?.let { f ->
                            val start = selection.firstNotNullOfOrNull { it.momentValue }
                            playback = runCatching {
                                Playback.start(
                                    f, start,
                                    when (sound) {
                                        Sound.TABLET -> null
                                        Sound.KEYBOARD -> { b -> keyboard.send(b) }
                                        Sound.APPS -> { b -> keyboard.sendToApps(b) }
                                    },
                                )
                            }.onFailure { status = "Can't play: ${it.message}" }.getOrNull()
                        }
                    }) { Text(if (playback != null) "Stop" else if (selection.isNotEmpty()) "Play from here" else "Play") }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(status, fontSize = 13.sp, modifier = Modifier.padding(horizontal = 8.dp).weight(1f))
                    failedLog?.let { log ->
                        TextButton(onClick = {
                            shareText(context, "LilyPond log", SelfTest.deviceInfo() + "\n" + log.takeLast(SHARED_LOG_CHARS))
                        }) { Text("Share log") }
                    }
                }
                uneven.firstOrNull()?.let { p ->
                    val line = SourcePositions.locationOf(text.text, p.start.coerceAtMost(text.text.length)).first
                    Row(
                        Modifier.fillMaxWidth().background(Color(0xFFFFF3E0)).padding(horizontal = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            "⚠ Line $line: voices of unequal length (" + p.lengths.joinToString(" / ") + " whole notes)" +
                                if (uneven.size > 1) ", +${uneven.size - 1} more" else "",
                            fontSize = 13.sp, modifier = Modifier.weight(1f),
                        )
                        TextButton(onClick = {
                            if (p.end <= text.text.length) {
                                text = text.copy(selection = TextRange(p.start, p.end))
                                selectFromCursor(text)
                            }
                        }) { Text("Go there") }
                        TextButton(onClick = { edit(p.start + 2) { src, off -> tools.padVoices(src, off) } }) { Text("Fill with rests") }
                    }
                }
                SelectionInfo(selection, lookup, range.size)
                if (entering) EntryBar(
                    duration = entryDuration,
                    dot = entryDot,
                    keyboardName = connectedKeyboard?.let(MidiKeyboard::name),
                    hasPlace = selectedOffset != null,
                    canUndo = undo.isNotEmpty(),
                    onDuration = { entryDuration = it },
                    onDot = { entryDot = !entryDot },
                    onRest = { entries.trySend(Entry.Keys(emptyList())) },
                    fillRests = fillRests,
                    onFillRests = { fillRests = !fillRests },
                    onUndo = ::undoLast,
                ) else {
                    val selected = selection.firstOrNull { it.type == "NoteHead" || it.type == "Rest" }
                        ?.takeIf { text.text == renderedText }
                    val inRange = range.size >= 2 && selected != null
                    val pitch = selected?.pitch
                    EditBar(
                        enabled = selected != null,
                        pitched = if (inRange) selection.any { it.type == "NoteHead" } else pitch != null,
                        canUndo = undo.isNotEmpty(),
                        onStep = { by ->
                            if (inRange) rangeEdit { src, o -> tools.range(src, o, LilyTools.RangeOp.Step(by)) }
                            else edit { src, off -> tools.step(src, off, by) }
                        },
                        onAlter = { semitones ->
                            if (inRange) rangeEdit { src, o -> tools.range(src, o, LilyTools.RangeOp.Alter(semitones)) }
                            else if (pitch != null) {
                                val p = LilyTools.Pitch(pitch.notename, semitones, pitch.octave)
                                edit { src, off -> tools.setPitch(src, off, p) }
                            }
                        },
                        onDuration = { d ->
                            if (inRange) rangeEdit { src, o -> tools.range(src, o, LilyTools.RangeOp.Duration(d)) }
                            else edit { src, off -> tools.setDuration(src, off, d) }
                        },
                        onDot = {
                            if (inRange) rangeEdit { src, o -> tools.range(src, o, LilyTools.RangeOp.Dot) }
                            else selected?.duration?.let { d ->
                                val nd = if (d.endsWith(".")) d.trimEnd('.') else "$d."
                                edit { src, off -> tools.setDuration(src, off, nd) }
                            }
                        },
                        onDelete = {
                            if (inRange) rangeEdit { src, o -> tools.range(src, o, LilyTools.RangeOp.Delete) }
                            else edit { src, off -> tools.delete(src, off) }
                        },
                        onAddVoice = ::addVoice,
                        inVoice = selected?.voiceId?.isNotEmpty() == true,
                        onRemoveVoice = {
                            range = emptyList()
                            edit { src, off -> tools.removeVoice(src, off) }
                        },
                        onUndo = ::undoLast,
                    )
                    MarksBar(
                        enabled = selected != null,
                        range = inRange,
                        on = selectedMarks,
                        onToggle = { what ->
                            if (inRange) rangeEdit { src, o -> tools.range(src, o, LilyTools.RangeOp.Toggle(what)) }
                            else edit { src, off -> tools.toggle(src, off, what) }
                        },
                        onSpan = ::spanRange,
                    )
                }
                if (entering && !landscape) piano()
                ScoreView(
                    document, lookup, if (playback != null) cursor else selection, ::onTap,
                    onSelectRange = ::onSelectRange,
                    onStaffTap = if (entering) { p -> entries.trySend(Entry.Step(p.step, p.octave)) } else null,
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
                        if (v.text != text.text) range = emptyList() // typed: the offsets have moved
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

/** Something to enter: notes played or tapped on the piano (MIDI keys; none for a rest), or a place tapped on the staff. */
private sealed interface Entry {
    data class Keys(val keys: List<Int>) : Entry
    /** [step] 0 = c … 6 = b, [octave] 0 = from middle C up; the key signature gives the accidental. */
    data class Step(val step: Int, val octave: Int) : Entry
}

/** Where playback sounds. */
private enum class Sound(val label: String) {
    TABLET("tablet"),
    KEYBOARD("keyboard"),
    /** Apps listening to LilyPond's own MIDI device, e.g. a synthesizer. */
    APPS("MIDI apps"),
}

/**
 * A MIDI file playing, sent to a MIDI keyboard or app ([MidiPlayer]) or on
 * the tablet's own synthesizer ([MediaPlayer]), from a point in the music.
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
        /** Starts at [fromWholeNotes] into the music (null: the start), sending to [send] if given. */
        fun start(midi: java.io.File, fromWholeNotes: Double?, send: ((ByteArray) -> Unit)?): Playback {
            val file = MidiFile.read(midi)
            val from = fromWholeNotes?.let { file.micros((it * 4 * file.division).toLong()) } ?: 0L
            return if (send != null) {
                val s = MidiPlayer(file, send)
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

/** Options for musicxml2ly. */
@Composable
private fun MusicXmlImportDialog(onDismiss: () -> Unit, onImport: (LilyTools.MusicXmlImportOptions) -> Unit) {
    var absolute by remember { mutableStateOf(false) }
    var keepLayout by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Import MusicXML") },
        text = {
            Column {
                Row(Modifier.fillMaxWidth().clickable { absolute = !absolute }, verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = absolute, onCheckedChange = { absolute = it })
                    Text("Absolute pitches (not \\relative)")
                }
                Row(Modifier.fillMaxWidth().clickable { keepLayout = !keepLayout }, verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = keepLayout, onCheckedChange = { keepLayout = it })
                    Text("Keep the file's page size and line breaks")
                }
            }
        },
        confirmButton = { TextButton(onClick = { onImport(LilyTools.MusicXmlImportOptions(absolute, keepLayout)) }) { Text("Import") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/** What Import offers in the file picker: MIDI and MusicXML (some apps label files loosely). */
private val IMPORT_TYPES = arrayOf(
    "audio/midi", "audio/x-midi", "audio/sp-midi",
    "application/vnd.recordare.musicxml+xml", "application/vnd.recordare.musicxml",
    "application/xml", "text/xml", "application/zip", "application/octet-stream",
)

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
    fillRests: Boolean,
    onFillRests: () -> Unit,
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
            if (fillRests) Button(onClick = onFillRests, contentPadding = PaddingValues(horizontal = 10.dp)) { Text("Fill rests") }
            else OutlinedButton(onClick = onFillRests, contentPadding = PaddingValues(horizontal = 10.dp)) { Text("Fill rests") }
            OutlinedButton(onClick = onUndo, enabled = canUndo, contentPadding = PaddingValues(horizontal = 10.dp)) { Text("Undo") }
            Text(
                (if (hasPlace) "After the selected note. " else "At the cursor. ") +
                    (keyboardName?.let { "Play on $it, tap the piano, or tap a line or space on the staff." }
                        ?: "Tap the piano or a line or space on the staff, or plug in a MIDI keyboard."),
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

/** Articulations, dynamics, slurs, ties and hairpins for the selected note; those on it are filled. */
@Composable
private fun MarksBar(
    enabled: Boolean,
    /** A range is selected: the lines go over it, from its first to its last note in each voice. */
    range: Boolean,
    on: Set<String>,
    onToggle: (String) -> Unit,
    onSpan: (String) -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val pad = PaddingValues(horizontal = 8.dp)
        for ((group, marks) in MARKS) {
            Text(group, fontSize = 12.sp, color = Color.DarkGray, modifier = Modifier.padding(start = 6.dp, end = 2.dp))
            if (group == LINES && range) {
                for ((kind, label) in RANGE_LINES) OutlinedButton(onClick = { onSpan(kind) }, enabled = enabled, contentPadding = pad) { Text(label) }
                for ((what, label) in marks.filter { it.first == "tie" }) {
                    if (what in on) Button(onClick = { onToggle(what) }, enabled = enabled, contentPadding = pad) { Text(label) }
                    else OutlinedButton(onClick = { onToggle(what) }, enabled = enabled, contentPadding = pad) { Text(label) }
                }
                continue
            }
            for ((what, label) in marks) {
                if (what in on) Button(onClick = { onToggle(what) }, enabled = enabled, contentPadding = pad) { Text(label) }
                else OutlinedButton(onClick = { onToggle(what) }, enabled = enabled, contentPadding = pad) { Text(label) }
            }
        }
    }
}

private const val LINES = "Lines"

/** With a range selected, slurs and hairpins go over it (LilyTools.span's kinds). */
private val RANGE_LINES = listOf("slur" to "slur ( )", "cresc" to "cresc <", "decresc" to "dim >")

/** How long after a change the voices are checked for equal lengths. */
private const val VOICE_CHECK_DELAY_MS = 700L

/** Above this many items in a range, the marks on all of them aren't looked up. */
private const val MAX_MARKS_LOOKUPS = 40

/** The marks bar's buttons: what LilyTools.toggle calls each mark, and its label. */
private val MARKS = listOf(
    "Marks" to listOf(
        "staccato" to "•", "accent" to ">", "tenuto" to "–", "marcato" to "^",
        "staccatissimo" to "▾", "fermata" to "fermata", "trill" to "tr",
    ),
    "Dynamics" to listOf("pp", "p", "mp", "mf", "f", "ff", "sfz").map { it to it },
    LINES to listOf(
        "slur-start" to "(", "slur-end" to ")", "tie" to "tie",
        "cresc" to "cresc <", "decresc" to "dim >", "hairpin-end" to "end",
    ),
)

/** Pitch, duration and delete buttons for the selected note (or rest). */
@Composable
private fun EditBar(
    enabled: Boolean,
    /** There is a note to change the pitch of (not only rests). */
    pitched: Boolean,
    canUndo: Boolean,
    onStep: (Int) -> Unit,
    /** A new accidental: 1 sharp, -1 flat, 0 natural. */
    onAlter: (Int) -> Unit,
    onDuration: (String) -> Unit,
    onDot: () -> Unit,
    onDelete: () -> Unit,
    onAddVoice: () -> Unit,
    /** The selection is in one of several voices, so its voice can be removed. */
    inVoice: Boolean,
    onRemoveVoice: () -> Unit,
    onUndo: () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        @Composable
        fun B(label: String, enabled: Boolean = true, onClick: () -> Unit) =
            OutlinedButton(onClick, enabled = enabled, contentPadding = PaddingValues(horizontal = 10.dp)) { Text(label) }
        B("▲", pitched) { onStep(+1) }
        B("▼", pitched) { onStep(-1) }
        B("♯", pitched) { onAlter(1) }
        B("♭", pitched) { onAlter(-1) }
        B("♮", pitched) { onAlter(0) }
        for (d in listOf("1", "2", "4", "8", "16")) B(d, enabled) { onDuration(d) }
        B("Dot", enabled, onDot)
        B("Delete", enabled, onDelete)
        B("Add voice", enabled, onAddVoice)
        B("Remove voice", enabled && inVoice, onRemoveVoice)
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
private fun SelectionInfo(selection: List<Grob>, lookup: ScoreLookup?, rangeItems: Int) {
    val g = selection.firstOrNull { it.type == "NoteHead" || it.type == "Rest" } ?: selection.firstOrNull()
    val voices = remember(lookup) { lookup?.map?.grobs?.mapNotNull { it.voice }?.distinct()?.sorted().orEmpty() }
    // One line, always: a taller line would move the score under the pen whenever the selection changes.
    Row(
        Modifier.fillMaxWidth().height(22.dp).padding(horizontal = 8.dp, vertical = 2.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (g == null) {
            Text("Tap a note, or draw a box around notes with the pen.", fontSize = 13.sp, color = Color.Gray, maxLines = 1, overflow = TextOverflow.Ellipsis)
            return@Row
        }
        if (rangeItems >= 2) {
            val inRange = selection.filter { it.type == "NoteHead" || it.type == "Rest" }
            for (v in inRange.map { it.voice }.distinct()) Box(Modifier.size(12.dp).background(voiceColor(v, voices)))
            val heads = inRange.count { it.type == "NoteHead" }
            val rests = inRange.count { it.type == "Rest" }
            val voiceCount = inRange.map { it.voiceId }.distinct().size
            Text(
                listOfNotNull(
                    heads.takeIf { it > 0 }?.let { "$it note${if (it > 1) "s" else ""}" },
                    rests.takeIf { it > 0 }?.let { "$it rest${if (it > 1) "s" else ""}" },
                ).joinToString(" and ") + " selected" + if (voiceCount > 1) ", in $voiceCount voices" else "",
                fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
            return@Row
        }
        Box(Modifier.size(12.dp).background(voiceColor(g.voice, voices)))
        val parts = buildList {
            add(g.type)
            g.pitch?.let { add(it.name) }
            g.duration?.let { add("duration $it") }
            g.voice?.let { add(g.voiceId?.takeIf { id -> id.isNotEmpty() }?.let { id -> "voice \"$id\"" } ?: "unnamed voice") }
            g.moment?.let { add("at $it") }
            g.origin?.let { add("line ${it.line}, column ${it.char + 1}") }
        }
        Text(parts.joinToString(" · "), fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/** Above this, an edit first engraves only the bars around it (see [ScoreLookup.windowAround]). */
private const val PREVIEW_THRESHOLD_MS = 1500L

/** How much of an imported file is engraved first, in whole notes: 4 bars of 4/4. */
private const val FIRST_LOOK_WHOLE_NOTES = "4"

/** The end of a failed render's log that "Share log" sends (the start is mostly warnings). */
private const val SHARED_LOG_CHARS = 20_000

/**
 * A whole-score render may take this long before it's given up: a long
 * orchestral import takes minutes on a tablet, and a new edit cancels it anyway.
 */
private const val WHOLE_SCORE_TIMEOUT_S = 1800L

/** 400 bars of piano music with two voices in the right hand: about 15 pages. */
private val LONG_SAMPLE = pianoPiece(400)

/** Piano music with two voices in the right hand, and a chord, over a few systems. */
internal val SCORE_SAMPLE = """
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
