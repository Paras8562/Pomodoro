package com.example.pomodoro

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext

/** The two contrasting colours of the vertical gradient behind the timer. */
class Glow(val g1: Color, val g2: Color)
val LocalGlow = staticCompositionLocalOf { Glow(Color(0xFF7ED8CB), Color(0xFFFFB4A9)) }

fun hex(s: String) = Color(android.graphics.Color.parseColor(s))

fun schemeOf(v: List<String>, dark: Boolean): ColorScheme {
    val x = v.map(::hex)
    return (if (dark) darkColorScheme() else lightColorScheme()).copy(
        primary = x[0], onPrimary = x[1], primaryContainer = x[2], onPrimaryContainer = x[3],
        secondaryContainer = x[2], onSecondaryContainer = x[3],
        surface = x[4], background = x[4], onSurface = x[5], onBackground = x[5],
        onSurfaceVariant = x[6], outline = x[7], surfaceVariant = x[2], surfaceContainer = x[8],
    )
}

@Composable
fun isDark(l: Look) = l.mode == "dark" || (l.mode == "system" && isSystemInDarkTheme())

/** "You" = Material You dynamic colour (Android 12+). Other palettes are fixed. */
@Composable
fun PomodoroTheme(look: Look, content: @Composable () -> Unit) {
    val dark = isDark(look)
    val ctx = LocalContext.current
    val dyn = look.pal == "you" && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
    val scheme: ColorScheme
    val glow: Glow
    if (dyn) {
        scheme = if (dark) dynamicDarkColorScheme(ctx) else dynamicLightColorScheme(ctx)
        glow = Glow(scheme.tertiaryContainer, scheme.primaryContainer)
    } else {
        val p = PALS.firstOrNull { it.id == look.pal } ?: PALS[0]
        val v = if (dark) p.d else p.l
        scheme = schemeOf(v, dark)
        glow = Glow(hex(v[9]), hex(v[10]))
    }
    CompositionLocalProvider(LocalGlow provides glow) {
        MaterialTheme(colorScheme = scheme, content = content)
    }
}
