package com.example.pomodoro

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Palette
import androidx.compose.material.icons.rounded.VolumeUp
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.Dp
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.roundToInt

@Composable
fun App(vm: SessionViewModel) {
    val ui by vm.ui.collectAsStateWithLifecycle()
    val running by vm.running.collectAsStateWithLifecycle()
    var sheet by remember { mutableStateOf<String?>(null) }

    val view = LocalView.current
    SideEffect { view.keepScreenOn = running }   // keep the screen awake while a block runs

    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface, contentColor = MaterialTheme.colorScheme.onSurface) {
        Box(Modifier.fillMaxSize().safeDrawingPadding(), contentAlignment = Alignment.TopCenter) {
            Box(Modifier.widthIn(max = 480.dp).fillMaxSize().padding(start = 24.dp, end = 24.dp, top = 16.dp, bottom = 24.dp)) {
                if (ui.inRun) RunScreen(vm, ui) { sheet = it } else SetupScreen(vm, ui) { sheet = it }
            }
        }
    }
    when (sheet) {
        "look" -> LookSheet(vm, ui) { sheet = null }
        "snd" -> SoundSheet(vm, ui) { sheet = null }
    }
}

// ---------------------------------------------------------------- shared bits

@Composable
fun SmallIconButton(icon: androidx.compose.ui.graphics.vector.ImageVector, desc: String, onClick: () -> Unit) {
    FilledTonalIconButton(onClick = onClick, modifier = Modifier.size(40.dp)) {
        Icon(icon, contentDescription = desc, modifier = Modifier.size(22.dp))
    }
}

@Composable
fun TopButtons(open: (String) -> Unit) {
    SmallIconButton(Icons.Rounded.VolumeUp, "Sound") { open("snd") }
    SmallIconButton(Icons.Rounded.Palette, "Appearance") { open("look") }
}

@Composable
fun kindColor(k: Kind) = when (k) {
    Kind.FOCUS -> MaterialTheme.colorScheme.primary
    Kind.SHORT -> LocalGlow.current.g1
    Kind.LONG -> LocalGlow.current.g2
}

/** Whole-session bar. [cur] = index of the running block (null = plain preview). */
@Composable
fun PlanBar(plan: List<Block>, cur: Int?, modifier: Modifier = Modifier, height: Dp = 12.dp) {
    Row(modifier.height(height), horizontalArrangement = Arrangement.spacedBy(2.dp), verticalAlignment = Alignment.CenterVertically) {
        plan.forEachIndexed { i, b ->
            val todo = cur != null && i > cur
            val now = cur != null && i == cur
            val a by animateFloatAsState(if (todo) .3f else 1f, tween(300), label = "a")
            val s by animateFloatAsState(if (now) 1.6f else 1f, tween(300), label = "s")
            Box(
                Modifier.weight(b.mins.toFloat()).fillMaxHeight()
                    .graphicsLayerAlphaScale(a, s)
                    .background(kindColor(b.kind), RoundedCornerShape(50))
            )
        }
    }
}

fun Modifier.graphicsLayerAlphaScale(alpha: Float, scaleY: Float) =
    graphicsLayer { this.alpha = alpha; this.scaleY = scaleY }

// ---------------------------------------------------------------- setup flow

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SetupScreen(vm: SessionViewModel, ui: Ui, open: (String) -> Unit) {
    val o = ui.opts
    val cs = MaterialTheme.colorScheme
    val hasPlan = vm.run.value.plan.isNotEmpty()
    val last = ui.step == 3 || (ui.step == 2 && o.brk == false)

    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                repeat(3) { i ->
                    Box(Modifier.weight(1f).height(4.dp).clip(RoundedCornerShape(2.dp))
                        .background(if (i < ui.step) cs.primary else cs.secondaryContainer))
                }
            }
            TopButtons(open)
        }

        Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
            when (ui.step) {
                1 -> {
                    Title("How long will you study?", "Your session runs for this long in total.")
                    Row(verticalAlignment = Alignment.Bottom, modifier = Modifier.padding(vertical = 8.dp)) {
                        Text("${o.total / 60}", fontSize = 76.sp, fontWeight = FontWeight.Light)
                        Text("h", fontSize = 22.sp, color = cs.onSurfaceVariant, modifier = Modifier.padding(start = 6.dp, end = 14.dp, bottom = 12.dp))
                        Text("%02d".format(o.total % 60), fontSize = 76.sp, fontWeight = FontWeight.Light)
                        Text("m", fontSize = 22.sp, color = cs.onSurfaceVariant, modifier = Modifier.padding(start = 6.dp, bottom = 12.dp))
                    }
                    Slider(
                        value = o.total.toFloat(),
                        onValueChange = { v -> vm.setOpts { it.copy(total = (v / 5).roundToInt() * 5) } },
                        valueRange = 10f..540f, steps = 105,
                    )
                    FlowRow(Modifier.padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf(30, 60, 120, 180, 240).forEach { m ->
                            FilterChip(selected = o.total == m, onClick = { vm.setOpts { it.copy(total = m) } }, label = { Text(fmt(m)) })
                        }
                    }
                }
                2 -> {
                    Title("Add breaks?", "Total time: ${fmt(o.total)}")
                    ChoiceCard("No breaks", "One focus timer for the whole session.", o.brk == false) { vm.setOpts { it.copy(brk = false) } }
                    Spacer(Modifier.height(12.dp))
                    ChoiceCard("Yes, plan breaks", "Short breaks, and long breaks if you want them. You choose the lengths.", o.brk == true) { vm.setOpts { it.copy(brk = true) } }
                }
                else -> PlanStep(vm, o)
            }
        }

        Row(Modifier.fillMaxWidth().padding(top = 12.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            if (ui.step > 1 || hasPlan) TextButton(onClick = vm::back) { Text(if (ui.step > 1) "Back" else "Cancel") } else Spacer(Modifier)
            Button(onClick = vm::next, enabled = !(ui.step == 2 && o.brk == null)) { Text(if (last) "Create session" else "Next") }
        }
    }
}

@Composable
fun Title(title: String, sub: String) {
    Text(title, style = MaterialTheme.typography.headlineMedium, modifier = Modifier.padding(top = 20.dp, bottom = 6.dp))
    Text(sub, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(bottom = 20.dp))
}

@Composable
fun ChoiceCard(title: String, text: String, selected: Boolean, onClick: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    val shape = RoundedCornerShape(20.dp)
    Column(
        Modifier.fillMaxWidth().clip(shape)
            .background(if (selected) cs.secondaryContainer else Color.Transparent)
            .border(1.dp, if (selected) Color.Transparent else cs.outline, shape)
            .clickable(onClick = onClick).padding(horizontal = 20.dp, vertical = 16.dp)
    ) {
        Text(title, fontSize = 16.sp, fontWeight = FontWeight.Medium, color = if (selected) cs.onSecondaryContainer else cs.onSurface)
        Text(text, fontSize = 14.sp, color = if (selected) cs.onSecondaryContainer else cs.onSurfaceVariant)
    }
}

@Composable
fun SliderRow(label: String, valueText: String, value: Int, min: Int, max: Int, step: Int, onChange: (Int) -> Unit) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, fontWeight = FontWeight.Medium)
        Text(valueText, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Medium)
    }
    Slider(
        value = value.toFloat(), onValueChange = { onChange((it / step).roundToInt() * step) },
        valueRange = min.toFloat()..max.toFloat(), steps = (max - min) / step - 1,
    )
}

@Composable
fun PlanStep(vm: SessionViewModel, o: Opts) {
    val cs = MaterialTheme.colorScheme
    val p = buildPlan(o, forceBreaks = true)
    val fm = p.filter { it.kind == Kind.FOCUS }
    val bm = p.filter { it.kind != Kind.FOCUS }
    Title("Plan your session", "Focus and breaks repeat until your study time is filled.")

    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(cs.surfaceContainer).padding(16.dp)) {
        PlanBar(p, null, Modifier.fillMaxWidth(), 14.dp)
        Row(Modifier.padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            Legend("Focus", kindColor(Kind.FOCUS)); Legend("Short break", kindColor(Kind.SHORT)); Legend("Long break", kindColor(Kind.LONG))
        }
        val fw = if (fm.size == 1) "block" else "blocks"
        val bw = if (bm.size == 1) "break" else "breaks"
        Text(
            "${fm.size} focus $fw (${fmt(fm.sumOf { it.mins })}) and ${bm.size} $bw (${fmt(bm.sumOf { it.mins })}) fill your ${fmt(o.total)}.",
            fontSize = 14.sp, color = cs.onSurfaceVariant, modifier = Modifier.padding(top = 12.dp),
        )
    }
    Spacer(Modifier.height(16.dp))
    SliderRow("Focus length", "${o.f} min", o.f, 5, 90, 5) { v -> vm.setOpts { it.copy(f = v) } }
    SliderRow("Short break", "${o.s} min", o.s, 1, 30, 1) { v -> vm.setOpts { it.copy(s = v) } }
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
        Text("Include long breaks", fontWeight = FontWeight.Medium)
        Switch(checked = o.useLong, onCheckedChange = { v -> vm.setOpts { it.copy(useLong = v) } })
    }
    if (o.useLong) {
        SliderRow("Long break length", "${o.l} min", o.l, 5, 60, 5) { v -> vm.setOpts { it.copy(l = v) } }
        SliderRow("Long break after", "${o.every} focus sessions", o.every, 2, 8, 1) { v -> vm.setOpts { it.copy(every = v) } }
    }
}

@Composable
fun Legend(text: String, color: Color) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(8.dp).background(color, CircleShape))
        Text(text, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(start = 5.dp))
    }
}
