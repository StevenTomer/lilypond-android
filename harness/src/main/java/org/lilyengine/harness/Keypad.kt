package org.lilyengine.harness

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.PlainTooltip
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TooltipAnchorPosition
import androidx.compose.material3.TooltipBox
import androidx.compose.material3.TooltipDefaults
import androidx.compose.material3.rememberTooltipState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.edit
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.roundToInt

/** How a command looks on its button. */
sealed interface Glyph {
    data class Label(val text: String, val size: Int = 18, val italic: Boolean = false, val bold: Boolean = false) : Glyph
    data class Vector(val icon: ImageVector) : Glyph
    data class Note(val duration: String, val dotted: Boolean = false) : Glyph
}

/**
 * Something the user can do: a button on the keypad, an entry in Add….
 * [id] is stable (keypad layouts are saved by it); [name] is its tooltip;
 * [enabled] and [selected] are read when drawn, so they follow the selection.
 */
class Command(
    val id: String,
    val name: String,
    val group: String,
    val glyph: Glyph,
    val enabled: () -> Boolean = { true },
    val selected: () -> Boolean = { false },
    /** A colour for the glyph (a voice's), if any. */
    val color: () -> Color? = { null },
    val run: () -> Unit,
)

/** A page of the keypad: its slots hold command ids (null: empty), row by row. */
data class KeypadPage(val id: String, val name: String, val slots: List<String?>)

/** A page floating in its own window: where (a fraction of the area, for tall and wide screens) and whether collapsed. */
data class FloatingPage(val pageId: String, val tall: Offset, val wide: Offset, val collapsed: Boolean = false)

/**
 * The keypad's layout, as the user arranged it: its pages, grid size,
 * whether pages follow what is being done, where it docks, which pages
 * float. Saved between sessions.
 */
data class KeypadConfig(
    val columns: Int = 5,
    val rows: Int = 4,
    val pages: List<KeypadPage> = DEFAULT_PAGES,
    /** Pages follow the mode and selection (unpinned). */
    val follow: Boolean = true,
    /** "auto" (bottom when tall, side when wide), "bottom" or "side". */
    val dock: String = "auto",
    val floating: List<FloatingPage> = emptyList(),
    /** Floating windows are see-through. */
    val translucent: Boolean = false,
) {
    fun toJson(): String = JSONObject()
        .put("version", VERSION).put("columns", columns).put("rows", rows).put("follow", follow).put("dock", dock).put("translucent", translucent)
        .put("pages", JSONArray(pages.map { p -> JSONObject().put("id", p.id).put("name", p.name).put("slots", JSONArray(p.slots.map { it ?: JSONObject.NULL })) }))
        .put("floating", JSONArray(floating.map { f ->
            JSONObject().put("page", f.pageId).put("tx", f.tall.x).put("ty", f.tall.y).put("wx", f.wide.x).put("wy", f.wide.y).put("collapsed", f.collapsed)
        }))
        .toString()

    companion object {
        private const val PREFS = "keypad"

        fun load(context: Context): KeypadConfig = runCatching {
            val o = JSONObject(context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString("config", null) ?: return KeypadConfig())
            if (o.optInt("version", 1) < VERSION) return KeypadConfig()
            val pages = o.getJSONArray("pages").let { a ->
                List(a.length()) { i ->
                    val p = a.getJSONObject(i)
                    val slots = p.getJSONArray("slots")
                    KeypadPage(p.getString("id"), p.getString("name"), List(slots.length()) { if (slots.isNull(it)) null else slots.getString(it) })
                }
            }
            val floating = o.optJSONArray("floating")?.let { a ->
                List(a.length()) { i ->
                    val f = a.getJSONObject(i)
                    FloatingPage(
                        f.getString("page"), Offset(f.getDouble("tx").toFloat(), f.getDouble("ty").toFloat()),
                        Offset(f.getDouble("wx").toFloat(), f.getDouble("wy").toFloat()), f.optBoolean("collapsed"),
                    )
                }
            }.orEmpty()
            KeypadConfig(
                o.optInt("columns", 5), o.optInt("rows", 4), pages, o.optBoolean("follow", true),
                o.optString("dock", "auto"), floating, o.optBoolean("translucent"),
            )
        }.getOrDefault(KeypadConfig())

        fun save(context: Context, config: KeypadConfig) =
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit { putString("config", config.toJson()) }

        /** Saved layouts older than this are replaced by the defaults (their commands changed). */
        const val VERSION = 2

        val DEFAULT_PAGES = listOf(
            // Both modes: in Notes mode the values are for new notes, otherwise they change the selection.
            KeypadPage("notes", "Notes", listOf(
                "dur.1", "dur.2", "dur.4", "dur.8", "dur.16",
                "dur.32", "dot", "entry.rest", "mark.tie", "respell",
                "step.up", "step.down", "acc.sharp", "acc.flat", "acc.natural",
                "voice.1", "voice.2", "voice.3", "voice.4", null,
            )),
            KeypadPage("marks", "Marks", listOf(
                "mark.staccato", "mark.accent", "mark.tenuto", "mark.marcato", "mark.staccatissimo",
                "mark.fermata", "mark.trill", "mark.turn", "mark.prall", "mark.mordent",
                "mark.pp", "mark.p", "mark.mp", "mark.mf", "mark.f",
                "mark.ff", "mark.sfz", "mark.upbow", "mark.downbow", "mark.portato",
            )),
            KeypadPage("lines", "Lines", listOf(
                "span.slur", "mark.slur-start", "mark.slur-end", "span.cresc", "span.decresc",
                "mark.cresc", "mark.decresc", "mark.hairpin-end", "add.text", null,
                "wrap.triplet", "wrap.tuplet", "wrap.grace", "wrap.8va", "wrap.8vb",
                "repeat", "ending.first", "ending.next", "bar.double", "bar.final",
            )),
            KeypadPage("structure", "Score", listOf(
                "add.clef", "add.key", "add.time", "add.tempo", "add.bar",
                "add.repeat", "voice.add", "voice.remove", "transpose", null,
                null, null, null, null, null,
                null, null, null, null, null,
            )),
        )

        /** Fewer buttons: the ones used most. */
        val MINIMAL_PAGES = listOf(
            KeypadPage("notes", "Notes", listOf(
                "dur.2", "dur.4", "dur.8", "dot",
                "step.up", "step.down", "acc.sharp", "acc.flat",
                "entry.rest", "mark.tie", "voice.1", "voice.2",
            )),
            KeypadPage("marks", "Marks", listOf(
                "mark.staccato", "mark.accent", "mark.tenuto", "mark.fermata",
                "mark.p", "mark.mf", "mark.f", "span.slur",
                "span.cresc", "span.decresc", "repeat", "add.text",
            )),
        )
    }
}

/** A hover tooltip (pen, mouse) that also shows on a long press, for [content]. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WithTooltip(text: String, content: @Composable () -> Unit) {
    val state = rememberTooltipState()
    val scope = rememberCoroutineScope()
    var pending by remember { mutableStateOf<Job?>(null) }
    TooltipBox(
        positionProvider = TooltipDefaults.rememberTooltipPositionProvider(TooltipAnchorPosition.Above),
        tooltip = { PlainTooltip { Text(text) } },
        state = state,
    ) {
        Box(
            Modifier.pointerInput(text) {
                awaitPointerEventScope {
                    while (true) {
                        val e = awaitPointerEvent()
                        when (e.type) {
                            PointerEventType.Enter -> { pending?.cancel(); pending = scope.launch { delay(TOOLTIP_DELAY_MS); state.show() } }
                            PointerEventType.Exit, PointerEventType.Press -> { pending?.cancel(); state.dismiss() }
                            else -> {}
                        }
                    }
                }
            },
        ) { content() }
    }
}

private const val TOOLTIP_DELAY_MS = 450L

/** A keypad button: the command's glyph in a square, dimmed when it doesn't apply, filled when on. */
@Composable
fun KeyButton(command: Command, size: androidx.compose.ui.unit.Dp, arranging: Boolean = false, onArrange: (() -> Unit)? = null) {
    val scheme = MaterialTheme.colorScheme
    val enabled = arranging || command.enabled()
    val selected = !arranging && enabled && command.selected()
    val tint = command.color()
    val fg = when { !enabled -> scheme.onSurface.copy(alpha = 0.3f); selected -> if (tint != null) Color.White else scheme.onPrimary; else -> tint ?: scheme.primary }
    val shape = RoundedCornerShape(10.dp)
    WithTooltip(command.name) {
        Box(
            Modifier.padding(1.5.dp).size(size)
                .clip(shape)
                .background(if (selected) (tint ?: scheme.primary) else if (arranging) scheme.secondaryContainer.copy(alpha = 0.4f) else Color.Transparent)
                .border(1.dp, scheme.outline.copy(alpha = if (enabled) 0.45f else 0.15f), shape)
                .clickable(enabled = enabled) { if (arranging) onArrange?.invoke() else command.run() }
                .semantics { contentDescription = command.name },
            contentAlignment = Alignment.Center,
        ) { GlyphView(command.glyph, fg) }
    }
}

@Composable
fun GlyphView(glyph: Glyph, color: Color) = when (glyph) {
    is Glyph.Label -> Text(
        glyph.text, color = color, fontSize = glyph.size.sp, maxLines = 1,
        fontStyle = if (glyph.italic) FontStyle.Italic else FontStyle.Normal,
        fontWeight = if (glyph.bold) FontWeight.Bold else FontWeight.Normal,
    )
    is Glyph.Vector -> Icon(glyph.icon, contentDescription = null, tint = color, modifier = Modifier.size(22.dp))
    is Glyph.Note -> DurationGlyph(glyph.duration, color, glyph.dotted)
}

/** An empty slot: nothing, or a "+" to fill while arranging. */
@Composable
private fun EmptySlot(size: androidx.compose.ui.unit.Dp, arranging: Boolean, onArrange: () -> Unit) {
    val shape = RoundedCornerShape(10.dp)
    Box(
        Modifier.padding(1.5.dp).size(size).clip(shape)
            .then(if (arranging) Modifier.border(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.3f), shape).clickable(onClick = onArrange) else Modifier),
        contentAlignment = Alignment.Center,
    ) { if (arranging) Text("+", color = MaterialTheme.colorScheme.outline, fontSize = 18.sp) }
}

/** A page's buttons as a fixed grid. */
@Composable
fun KeyGrid(
    page: KeypadPage,
    columns: Int,
    rows: Int,
    commands: Map<String, Command>,
    cell: androidx.compose.ui.unit.Dp,
    arranging: Boolean,
    onArrange: (slot: Int) -> Unit,
) {
    Column {
        for (r in 0 until rows) {
            Row {
                for (c in 0 until columns) {
                    val i = r * columns + c
                    val command = page.slots.getOrNull(i)?.let(commands::get)
                    if (command != null) KeyButton(command, cell, arranging) { onArrange(i) }
                    else EmptySlot(cell, arranging) { onArrange(i) }
                }
            }
        }
    }
}

/** The page tabs, and the pin, float and arrange controls. */
@Composable
fun KeypadTabs(
    pages: List<KeypadPage>,
    current: String,
    pinned: Boolean,
    arranging: Boolean,
    onPage: (String) -> Unit,
    onPin: () -> Unit,
    onFloat: () -> Unit,
    onArrangeDone: () -> Unit,
    onSettings: () -> Unit,
) {
    Row(Modifier.horizontalScroll(rememberScrollState()), verticalAlignment = Alignment.CenterVertically) {
        for (p in pages) {
            val on = p.id == current
            WithTooltip("Page: ${p.name}") {
                Text(
                    p.name,
                    fontSize = 14.sp,
                    fontWeight = if (on) FontWeight.Bold else FontWeight.Normal,
                    color = if (on) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.clip(RoundedCornerShape(8.dp))
                        .background(if (on) MaterialTheme.colorScheme.primaryContainer else Color.Transparent)
                        .clickable { onPage(p.id) }.padding(horizontal = 10.dp, vertical = 6.dp),
                )
            }
        }
        if (arranging) {
            TextButton(onClick = onArrangeDone) { Text("Done arranging") }
        } else {
            SmallControl(if (pinned) "📌" else "📍", if (pinned) "Pinned: pages stay where you put them (tap to follow along)" else "Following along: pages change with what you do (tap to pin)", onPin)
            SmallControl("⧉", "Float this page in its own window", onFloat)
            SmallControl("⚙", "Keypad settings: grid size, docking, arranging buttons", onSettings)
        }
    }
}

@Composable
private fun SmallControl(label: String, tooltip: String, onClick: () -> Unit) = WithTooltip(tooltip) {
    Text(label, fontSize = 16.sp, modifier = Modifier.clip(RoundedCornerShape(8.dp)).clickable(onClick = onClick).padding(horizontal = 8.dp, vertical = 6.dp))
}

/**
 * A page in its own window over the score: drag it by its title, double-tap
 * the title to collapse it, "↧" to dock it again. Snaps to the edges.
 */
@Composable
fun FloatingWindow(
    page: KeypadPage,
    position: Offset,
    collapsed: Boolean,
    translucent: Boolean,
    areaWidth: Float,
    areaHeight: Float,
    content: @Composable () -> Unit,
    onMove: (Offset) -> Unit,
    onCollapse: () -> Unit,
    onDock: () -> Unit,
) {
    val density = LocalDensity.current
    var drag by remember(page.id) { mutableStateOf(Offset.Zero) }
    var size by remember { mutableStateOf(androidx.compose.ui.unit.IntSize.Zero) }
    // The gesture below outlives recompositions: it reads the position through this, never a stale copy.
    val current by rememberUpdatedState(position)
    fun placeX() = (current.x * areaWidth + drag.x).coerceIn(0f, (areaWidth - size.width).coerceAtLeast(0f))
    fun placeY() = (current.y * areaHeight + drag.y).coerceIn(0f, (areaHeight - size.height).coerceAtLeast(0f))
    Surface(
        shape = RoundedCornerShape(12.dp),
        tonalElevation = 3.dp,
        shadowElevation = 6.dp,
        modifier = Modifier.offset { IntOffset(placeX().roundToInt(), placeY().roundToInt()) }
            .alpha(if (translucent) 0.72f else 1f)
            .onSizeChanged { size = it },
    ) {
        Column(Modifier.padding(4.dp)) {
            Row(
                Modifier.clip(RoundedCornerShape(8.dp)).background(MaterialTheme.colorScheme.surfaceVariant)
                    .pointerInput(page.id, areaWidth, areaHeight) {
                        detectDragGestures(
                            onDrag = { change, amount -> change.consume(); drag += amount },
                            onDragEnd = {
                                // Snap to an edge when let go near it.
                                val snap = with(density) { 28.dp.toPx() }
                                var nx = placeX(); var ny = placeY()
                                if (nx < snap) nx = 0f
                                if (ny < snap) ny = 0f
                                if (areaWidth - (nx + size.width) < snap) nx = areaWidth - size.width
                                if (areaHeight - (ny + size.height) < snap) ny = areaHeight - size.height
                                drag = Offset.Zero
                                onMove(Offset((nx / areaWidth).coerceIn(0f, 1f), (ny / areaHeight).coerceIn(0f, 1f)))
                            },
                        )
                    }
                    .pointerInput(page.id) { detectTapGestures(onDoubleTap = { onCollapse() }) }
                    .padding(horizontal = 8.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                // The handle to drag by (not a button, so no tooltip: one would take the drag).
                Text("⠿ " + page.name, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                Box(Modifier.width(12.dp))
                SmallControl(if (collapsed) "▢" else "–", if (collapsed) "Open" else "Collapse", onCollapse)
                SmallControl("↧", "Dock this page again", onDock)
            }
            if (!collapsed) content()
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
/**
 * The whole catalog, searchable: to run any command ([onPick] runs it) or,
 * when arranging, to choose what goes in a slot ("Empty slot" clears it).
 */
@Composable
fun CommandCatalog(
    title: String,
    commands: List<Command>,
    allowEmpty: Boolean,
    onPick: (Command?) -> Unit,
    onDismiss: () -> Unit,
) {
    var query by remember { mutableStateOf("") }
    val shown = commands.filter { query.isBlank() || it.name.contains(query, ignoreCase = true) || it.group.contains(query, ignoreCase = true) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column {
                OutlinedTextField(value = query, onValueChange = { query = it }, label = { Text("Search") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                Column(Modifier.verticalScroll(rememberScrollState()).padding(top = 4.dp)) {
                    if (allowEmpty) TextButton(onClick = { onPick(null) }) { Text("Empty slot", fontStyle = FontStyle.Italic) }
                    for ((group, items) in shown.groupBy { it.group }) {
                        Text(group, fontSize = 12.sp, color = MaterialTheme.colorScheme.outline, modifier = Modifier.padding(top = 6.dp, bottom = 2.dp))
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            for (c in items) {
                                val enabled = allowEmpty || c.enabled()
                                val fg = if (enabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.3f)
                                val shape = RoundedCornerShape(10.dp)
                                WithTooltip(c.name) {
                                    Column(
                                        Modifier.width(92.dp).heightIn(min = 64.dp).clip(shape)
                                            .border(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = if (enabled) 0.4f else 0.15f), shape)
                                            .clickable(enabled = enabled) { onPick(c) }.padding(4.dp)
                                            .semantics { contentDescription = c.name },
                                        horizontalAlignment = Alignment.CenterHorizontally,
                                    ) {
                                        Box(Modifier.size(30.dp), contentAlignment = Alignment.Center) { GlyphView(c.glyph, fg) }
                                        Text(
                                            c.name.substringBefore(" (").substringBefore(":").substringBefore(" —"),
                                            fontSize = 11.sp, lineHeight = 13.sp, maxLines = 2, color = fg,
                                            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        },
        properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false),
        modifier = Modifier.fillMaxWidth(0.92f),
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } },
    )
}

@OptIn(ExperimentalLayoutApi::class)
/** Grid size, docking, following, see-through windows, presets, pages, and arranging. */
@Composable
fun KeypadSettingsDialog(
    config: KeypadConfig,
    onChange: (KeypadConfig) -> Unit,
    onArrange: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Keypad") },
        text = {
            Column(Modifier.heightIn(max = 520.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                @Composable
                fun Stepper(label: String, value: Int, range: IntRange, set: (Int) -> Unit) = Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(label, modifier = Modifier.width(110.dp))
                    OutlinedButton(onClick = { set((value - 1).coerceIn(range)) }, enabled = value > range.first) { Text("−") }
                    Text("$value", fontSize = 18.sp, modifier = Modifier.padding(horizontal = 12.dp))
                    OutlinedButton(onClick = { set((value + 1).coerceIn(range)) }, enabled = value < range.last) { Text("+") }
                }
                Stepper("Columns", config.columns, 2..8) { onChange(config.copy(columns = it)) }
                Stepper("Rows", config.rows, 1..8) { onChange(config.copy(rows = it)) }
                Text("Dock")
                FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    val pad = androidx.compose.foundation.layout.PaddingValues(horizontal = 12.dp)
                    for ((value, label) in listOf("auto" to "Automatic", "bottom" to "Bottom", "side" to "Side")) {
                        if (config.dock == value) androidx.compose.material3.Button(onClick = {}, contentPadding = pad) { Text(label) }
                        else OutlinedButton(onClick = { onChange(config.copy(dock = value)) }, contentPadding = pad) { Text(label) }
                    }
                }
                Text("Automatic: along the bottom when the screen is taller than wide, at the side when wider.", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Row(Modifier.clickable { onChange(config.copy(follow = !config.follow)) }, verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = config.follow, onCheckedChange = { onChange(config.copy(follow = it)) })
                    Text("Pages follow along (the pin turns this off)")
                }
                Row(Modifier.clickable { onChange(config.copy(translucent = !config.translucent)) }, verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = config.translucent, onCheckedChange = { onChange(config.copy(translucent = it)) })
                    Text("See-through floating windows")
                }
                HorizontalDivider()
                TextButton(onClick = onArrange) { Text("Arrange buttons… (tap a slot to choose what goes there)") }
                Text("Pages", fontWeight = FontWeight.Bold)
                for ((i, p) in config.pages.withIndex()) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        var name by remember(p.id) { mutableStateOf(p.name) }
                        OutlinedTextField(
                            value = name, singleLine = true, modifier = Modifier.weight(1f),
                            onValueChange = { name = it; onChange(config.copy(pages = config.pages.toMutableList().also { l -> l[i] = p.copy(name = it) })) },
                        )
                        TextButton(enabled = config.pages.size > 1, onClick = {
                            onChange(config.copy(pages = config.pages.filterIndexed { j, _ -> j != i }, floating = config.floating.filter { it.pageId != p.id }))
                        }) { Text("Remove") }
                    }
                }
                TextButton(onClick = {
                    val id = "page" + System.currentTimeMillis()
                    onChange(config.copy(pages = config.pages + KeypadPage(id, "My page", List(config.columns * config.rows) { null })))
                }) { Text("+ Add a page") }
                HorizontalDivider()
                Text("Start again from", fontWeight = FontWeight.Bold)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    OutlinedButton(onClick = { onChange(KeypadConfig(follow = config.follow, translucent = config.translucent)) }) { Text("All buttons") }
                    OutlinedButton(onClick = { onChange(KeypadConfig(columns = 4, rows = 3, pages = KeypadConfig.MINIMAL_PAGES, follow = config.follow)) }) { Text("Minimal") }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } },
        properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false),
        modifier = Modifier.fillMaxWidth(0.92f).widthIn(max = 640.dp),
    )
}
