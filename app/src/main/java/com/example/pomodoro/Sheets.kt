package com.example.pomodoro

import android.content.Context
import android.os.Build
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.roundToInt

// ---------------------------------------------------------------- appearance

class Tile(val surface: Color, val g1: Color, val g2: Color, val primary: Color, val sec: Color)

fun tileFor(id: String, dark: Boolean, ctx: Context): Tile {
    if (id == "you" && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        val s = if (dark) dynamicDarkColorScheme(ctx) else dynamicLightColorScheme(ctx)
        return Tile(s.surface, s.tertiaryContainer, s.primaryContainer, s.primary, s.secondaryContainer)
    }
    val p = PALS.firstOrNull { it.id == id } ?: PALS[0]
    val v = (if (dark) p.d else p.l).map(::hex)
    return Tile(v[4], v[9], v[10], v[0], v[2])
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LookSheet(vm: SessionViewModel, ui: Ui, onClose: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    val ctx = LocalContext.current
    val dark = isDark(ui.look)
    val dynamic = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
    val selected = if (ui.look.pal == "you" && !dynamic) "tomato" else ui.look.pal
    val entries = (if (dynamic) listOf("you" to "You") else emptyList()) + PALS.map { it.id to it.name }

    ModalBottomSheet(
        onDismissRequest = onClose, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = cs.surfaceContainer,
    ) {
        Column(Modifier.padding(horizontal = 24.dp).padding(bottom = 24.dp).verticalScroll(rememberScrollState())) {
            Text("Appearance", style = MaterialTheme.typography.headlineSmall, modifier = Modifier.padding(bottom = 20.dp))
            Text("Mode", fontWeight = FontWeight.Medium)
            val modes = listOf("system" to "Auto", "light" to "Light", "dark" to "Dark")
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth().padding(top = 12.dp, bottom = 22.dp)) {
                modes.forEachIndexed { i, (k, n) ->
                    SegmentedButton(
                        selected = ui.look.mode == k, onClick = { vm.setLook(ui.look.copy(mode = k)) },
                        shape = SegmentedButtonDefaults.itemShape(i, modes.size), label = { Text(n) },
                    )
                }
            }
            Text("Color theme", fontWeight = FontWeight.Medium)
            Spacer(Modifier.height(12.dp))
            entries.chunked(3).forEach { row ->
                Row(Modifier.fillMaxWidth().padding(bottom = 14.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    row.forEach { (id, name) ->
                        PalTile(name, tileFor(id, dark, ctx), id == selected, Modifier.weight(1f)) { vm.setLook(ui.look.copy(pal = id)) }
                    }
                    repeat(3 - row.size) { Spacer(Modifier.weight(1f)) }
                }
            }
            if (dynamic) Text("“You” uses the colors from your wallpaper.", fontSize = 13.sp, color = cs.onSurfaceVariant)
        }
    }
}

@Composable
fun PalTile(name: String, t: Tile, selected: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    val shape = RoundedCornerShape(20.dp)
    Column(modifier.clip(shape).clickable(onClick = onClick), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Box(
            Modifier.fillMaxWidth().aspectRatio(1.2f).clip(shape).background(t.surface)
                .border(2.dp, if (selected) t.primary else t.sec, shape),
            contentAlignment = Alignment.Center,
        ) {
            Box(
                Modifier.fillMaxWidth(.34f).fillMaxHeight(.74f)
                    .background(Brush.verticalGradient(listOf(t.g1, t.g2)), RoundedCornerShape(50)),
                contentAlignment = Alignment.Center,
            ) { Box(Modifier.size(12.dp).background(t.primary, RoundedCornerShape(50))) }
        }
        Text(
            name, fontSize = 13.sp, fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
            color = if (selected) cs.onSurface else cs.onSurfaceVariant,
        )
    }
}

// ---------------------------------------------------------------- sound

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun SoundSheet(vm: SessionViewModel, ui: Ui, onClose: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    val s = ui.snd
    ModalBottomSheet(
        onDismissRequest = onClose, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = cs.surfaceContainer,
    ) {
        Column(Modifier.padding(horizontal = 24.dp).padding(bottom = 24.dp).verticalScroll(rememberScrollState())) {
            Text("Sound", style = MaterialTheme.typography.headlineSmall, modifier = Modifier.padding(bottom = 20.dp))
            Row {
                Text("Ticking", fontWeight = FontWeight.Medium)
                Text("during focus only", fontSize = 13.sp, color = cs.onSurfaceVariant, modifier = Modifier.padding(start = 6.dp, top = 2.dp))
            }
            val ticks = listOf("off" to "Off", "soft" to "Soft", "clock" to "Clock")
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth().padding(top = 12.dp, bottom = 16.dp)) {
                ticks.forEachIndexed { i, (k, n) ->
                    SegmentedButton(
                        selected = s.tick == k, onClick = { vm.setSnd(s.copy(tick = k)); vm.previewTick(k) },
                        shape = SegmentedButtonDefaults.itemShape(i, ticks.size), label = { Text(n) },
                    )
                }
            }
            Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text("Sound when a block changes", fontWeight = FontWeight.Medium)
                Switch(checked = s.cues, onCheckedChange = { vm.setSnd(s.copy(cues = it)) })
            }
            Row(Modifier.fillMaxWidth().padding(top = 12.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("Volume", fontWeight = FontWeight.Medium)
                Text("${s.vol}%", color = cs.primary, fontWeight = FontWeight.Medium)
            }
            Slider(
                value = s.vol.toFloat(),
                onValueChange = { vm.setSnd(s.copy(vol = (it / 5).roundToInt() * 5)) },
                onValueChangeFinished = { vm.previewTick(if (s.tick == "off") "soft" else s.tick) },
                valueRange = 0f..100f, steps = 19,
            )
            Text("Preview sounds", fontWeight = FontWeight.Medium, modifier = Modifier.padding(top = 8.dp, bottom = 8.dp))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("focus" to "Focus", "short" to "Short break", "long" to "Long break", "done" to "Done").forEach { (k, n) ->
                    AssistChip(onClick = { vm.preview(k) }, label = { Text(n) })
                }
            }
        }
    }
}
