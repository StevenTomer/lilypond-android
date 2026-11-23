package org.lilyengine.harness

import android.app.Activity
import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.edit
import androidx.core.view.WindowCompat

/** Light, dark, or as the system is set. */
enum class Appearance(val label: String, val glyph: String) {
    SYSTEM("As the system", "◐"), LIGHT("Light", "☀\uFE0E"), DARK("Dark", "☾\uFE0E");

    fun next() = entries[(ordinal + 1) % entries.size]

    companion object {
        private const val PREFS = "appearance"
        fun load(context: Context): Appearance =
            runCatching { valueOf(context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString("mode", null) ?: "SYSTEM") }
                .getOrDefault(SYSTEM)
        fun save(context: Context, a: Appearance) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit { putString("mode", a.name) }
    }
}

/**
 * The colours Material doesn't name: the score's paper (never inverted: the
 * music stays black, on cream or on gray), the desk the pages lie on, the
 * title band, the source editor, the note-entry strip, warnings.
 */
@Immutable
data class AppColors(
    val dark: Boolean,
    val paper: Color,
    val desk: Color,
    val band: Color,
    val onBand: Color,
    val editor: Color,
    val strip: Color,
    val warning: Color,
)

private val LightApp = AppColors(
    dark = false,
    paper = Color(0xFFF6F1E4), // warm paper, not white
    desk = Color(0xFFD8D2C4),
    band = Color(0xFF2B2F45),
    onBand = Color(0xFFE6DCC2),
    editor = Color(0xFFF3EFE6),
    strip = Color(0xFFE4E7EF),
    warning = Color(0xFFF6E3C4),
)

private val DarkApp = AppColors(
    dark = true,
    paper = Color(0xFFA7A6A1), // gray paper; the notes stay black
    desk = Color(0xFF1F2023),
    band = Color(0xFF121317),
    onBand = Color(0xFFC9BE9F),
    editor = Color(0xFF26282C),
    strip = Color(0xFF2C3140),
    warning = Color(0xFF4A3A22),
)

private val LightScheme = lightColorScheme(
    primary = Color(0xFF3E4E7C),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFD9DEF0),
    onPrimaryContainer = Color(0xFF1B2547),
    secondary = Color(0xFF8A6A2E),
    secondaryContainer = Color(0xFFF0E1C0),
    background = Color(0xFFECE8DF),
    onBackground = Color(0xFF23242A),
    surface = Color(0xFFF1EDE4),
    onSurface = Color(0xFF23242A),
    surfaceVariant = Color(0xFFE2DDD1),
    onSurfaceVariant = Color(0xFF5A5A62),
    surfaceContainerLowest = Color(0xFFF7F4EE),
    surfaceContainerLow = Color(0xFFE7E2D7),
    surfaceContainer = Color(0xFFE9E4DA),
    surfaceContainerHigh = Color(0xFFE3DED3),
    surfaceContainerHighest = Color(0xFFDDD8CC),
    outline = Color(0xFF8C8A86),
    outlineVariant = Color(0xFFC9C4B8),
)

private val DarkScheme = darkColorScheme(
    primary = Color(0xFFAFC0EE),
    onPrimary = Color(0xFF15204A),
    primaryContainer = Color(0xFF34406A),
    onPrimaryContainer = Color(0xFFDCE2F8),
    secondary = Color(0xFFD9BD82),
    secondaryContainer = Color(0xFF4E4027),
    background = Color(0xFF2A2B2F),
    onBackground = Color(0xFFE3E2DE),
    surface = Color(0xFF2E3034),
    onSurface = Color(0xFFE3E2DE),
    surfaceVariant = Color(0xFF3A3C42),
    onSurfaceVariant = Color(0xFFB4B3B0),
    surfaceContainerLowest = Color(0xFF232427),
    surfaceContainerLow = Color(0xFF303237),
    surfaceContainer = Color(0xFF33353A),
    surfaceContainerHigh = Color(0xFF3A3C41),
    surfaceContainerHighest = Color(0xFF424449),
    outline = Color(0xFF8E8D8A),
    outlineVariant = Color(0xFF4A4C52),
)

val LocalAppColors = staticCompositionLocalOf { LightApp }

/** The app's own colours, for the current appearance. */
val appColors: AppColors
    @Composable @ReadOnlyComposable get() = LocalAppColors.current

@Composable
fun AndroLilyTheme(appearance: Appearance, content: @Composable () -> Unit) {
    val dark = when (appearance) { Appearance.SYSTEM -> isSystemInDarkTheme(); Appearance.LIGHT -> false; Appearance.DARK -> true }
    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            // The title band runs under the status bar, dark in both modes: light icons on it.
            (view.context as? Activity)?.window?.let { w ->
                WindowCompat.getInsetsController(w, view).isAppearanceLightStatusBars = false
                WindowCompat.getInsetsController(w, view).isAppearanceLightNavigationBars = !dark
            }
        }
    }
    CompositionLocalProvider(LocalAppColors provides if (dark) DarkApp else LightApp) {
        MaterialTheme(colorScheme = if (dark) DarkScheme else LightScheme, content = content)
    }
}

/** The narrow band along the top: the app's name, and the settings button. */
@Composable
fun TitleBand(onSettings: () -> Unit) {
    val c = appColors
    Box(Modifier.fillMaxWidth().height(20.dp).background(c.band)) {
        Text(
            "ANDROLILY",
            color = c.onBand,
            fontSize = 10.sp,
            lineHeight = 10.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = 5.sp,
            modifier = Modifier.align(Alignment.Center),
        )
        Box(Modifier.align(Alignment.CenterEnd)) {
            WithTooltip("Settings: appearance, keypad, diagnostics") {
                Text(
                    "⚙",
                    color = c.onBand,
                    fontSize = 13.sp,
                    lineHeight = 13.sp,
                    modifier = Modifier.clickable(onClick = onSettings).padding(horizontal = 14.dp),
                )
            }
        }
    }
}

/** The app's settings: appearance, and the ways to the keypad's settings and the diagnostics. */
@Composable
fun AppSettingsDialog(
    appearance: Appearance,
    onAppearance: (Appearance) -> Unit,
    onKeypad: () -> Unit,
    onDiagnostics: () -> Unit,
    onDismiss: () -> Unit,
) {
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Settings") },
        text = {
            androidx.compose.foundation.layout.Column(verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(8.dp)) {
                Text("Appearance", fontWeight = FontWeight.Bold)
                androidx.compose.foundation.layout.Row(horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(4.dp)) {
                    val pad = androidx.compose.foundation.layout.PaddingValues(horizontal = 12.dp)
                    for (a in Appearance.entries) {
                        val label = a.glyph + " " + if (a == Appearance.SYSTEM) "System" else a.label
                        if (a == appearance) androidx.compose.material3.Button(onClick = {}, contentPadding = pad) { Text(label) }
                        else androidx.compose.material3.OutlinedButton(onClick = { onAppearance(a) }, contentPadding = pad) { Text(label) }
                    }
                }
                Text("In both, the music stays black: on warm paper when light, on gray when dark.", fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                androidx.compose.material3.HorizontalDivider()
                androidx.compose.material3.TextButton(onClick = onKeypad) { Text("Keypad… (grid size, docking, pages, arranging)") }
                androidx.compose.material3.TextButton(onClick = onDiagnostics) { Text("Diagnostics… (self-test, benchmark, MIDI devices)") }
            }
        },
        confirmButton = { androidx.compose.material3.TextButton(onClick = onDismiss) { Text("Close") } },
    )
}
