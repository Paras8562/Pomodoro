package com.example.pomodoro

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.ui.graphics.Brush
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
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

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun App(vm: SessionViewModel) {
    val ui by vm.ui.collectAsStateWithLifecycle()
    val running by vm.running.collectAsStateWithLifecycle()
    var sheet by remember { mutableStateOf<String?>(null) }
    val ctx = LocalContext.current
    val density = LocalDensity.current
    val dir = LocalLayoutDirection.current

    val view = LocalView.current
    SideEffect { view.keepScreenOn = running }   // keep the screen awake while a block runs

    // "Get back to work" reminder when you leave the app during a focus block (Android 13+ asks for permission once)
    val permLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
    var asked by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(running) {
        if (running && !asked && Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            asked = true
            permLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }
    LifecycleEventEffect(Lifecycle.Event.ON_STOP) { vm.appLeft() }
    LifecycleEventEffect(Lifecycle.Event.ON_START) { vm.appBack() }

    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface, contentColor = MaterialTheme.colorScheme.onSurface) {
        BoxWithConstraints(Modifier.fillMaxSize()) {
            val landscape = maxWidth > maxHeight
            // The margins the bars would take (so nothing sits in the swipe-in zone).
            // On the timer screen in landscape they are made equal on both sides, so the timer sits exactly in the
            // middle of the screen; changes between screens glide instead of jumping.
            val base = WindowInsets.systemBarsIgnoringVisibility.union(WindowInsets.displayCutout)
            val l = base.getLeft(density, dir)
            val r = base.getRight(density, dir)
            val t = base.getTop(density)
            val bt = base.getBottom(density)
            val sym = landscape && ui.inRun
            // in immersive mode only the camera cut-out really blocks content, so only that is kept (equal on both sides)
            val cut = WindowInsets.displayCutout
            val side = max(cut.getLeft(density, dir), cut.getRight(density, dir))
            val vert = max(t, bt)
            val glide = tween<Dp>(450)
            val padL by animateDpAsState(with(density) { (if (sym) side else l).toDp() }, glide, label = "padL")
            val padR by animateDpAsState(with(density) { (if (sym) side else r).toDp() }, glide, label = "padR")
            val padT by animateDpAsState(with(density) { (if (sym) vert else if (landscape) 0 else t).toDp() }, glide, label = "padT")
            val padB by animateDpAsState(with(density) { (if (sym) vert else bt).toDp() }, glide, label = "padB")
            Box(Modifier.fillMaxSize().padding(start = padL, end = padR, top = padT, bottom = padB), contentAlignment = Alignment.TopCenter) {
                Box(
                    Modifier.widthIn(max = if (landscape) 1000.dp else 480.dp).fillMaxSize().padding(
                        start = if (sym) 12.dp else 24.dp, end = if (sym) 12.dp else 24.dp,
                        top = if (sym) 10.dp else if (landscape) 8.dp else 16.dp,
                        bottom = if (sym) 10.dp else if (landscape) 12.dp else 24.dp,
                    )
                ) {
                    if (ui.inRun) RunScreen(vm, ui) { sheet = it } else SetupScreen(vm, ui) { sheet = it }
                }
            }
        }
    }
    when (sheet) {
        "look" -> LookSheet(vm, ui) { sheet = null }
        "snd" -> SoundSheet(vm, ui) { sheet = null }
    }
}

// ---------------------------------------------------------------- shared bits

/** Translucent accent-tinted buttons: a soft wash of the theme colour with an accent-coloured icon. */
@Composable
fun tintedButtonColors() = IconButtonDefaults.filledTonalIconButtonColors(
    containerColor = MaterialTheme.colorScheme.primary.copy(alpha = .16f),
    contentColor = MaterialTheme.colorScheme.primary,
)

@Composable
fun SmallIconButton(icon: androidx.compose.ui.graphics.vector.ImageVector, desc: String, onClick: () -> Unit) {
    FilledTonalIconButton(onClick = onClick, modifier = Modifier.size(40.dp), colors = tintedButtonColors()) {
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

@Composable
private fun segAnim(i: Int, cur: Int?): Pair<State<Float>, State<Float>> {
    val alpha = animateFloatAsState(if (cur != null && i > cur) .3f else 1f, tween(300), label = "a")
    val scale = animateFloatAsState(if (cur != null && i == cur) 1.6f else 1f, tween(300), label = "s")
    return alpha to scale
}

/**
 * Whole-session bar. [cur] = index of the running block (null = plain preview). [height] is the bar's thickness.
 * If [breakColor] is set, both kinds of break use it (the calm look of the timer screen).
 * The gaps shrink when there are many blocks (e.g. a 9-hour session), so the bar never collapses to nothing.
 */
@Composable
fun PlanBar(
    plan: List<Block>, cur: Int?, modifier: Modifier = Modifier, height: Dp = 12.dp,
    vertical: Boolean = false, gap: Dp = 2.dp, breakColor: Color? = null, reverse: Boolean = false,
) {
    BoxWithConstraints(modifier.then(if (vertical) Modifier.width(height) else Modifier.height(height))) {
        val len = if (vertical) maxHeight else maxWidth
        val g = if (plan.size > 1) minOf(gap, len * 0.25f / (plan.size - 1)) else gap
        @Composable
        fun colorOf(b: Block) = if (breakColor != null && b.kind != Kind.FOCUS) breakColor else kindColor(b.kind)

        if (vertical) {
            Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(g), horizontalAlignment = Alignment.CenterHorizontally) {
                (if (reverse) plan.indices.reversed() else plan.indices.toList()).forEach { i ->
                    val b = plan[i]
                    val (a, sc) = segAnim(i, cur)
                    val flat = breakColor != null && b.kind != Kind.FOCUS
                    Box(
                        Modifier.weight(b.ms.toFloat()).fillMaxWidth()
                            .graphicsLayer { alpha = if (flat) 1f else a.value; scaleX = sc.value }
                            .background(colorOf(b), RoundedCornerShape(50))
                    )
                }
            }
        } else {
            Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(g), verticalAlignment = Alignment.CenterVertically) {
                plan.forEachIndexed { i, b ->
                    val (a, sc) = segAnim(i, cur)
                    val flat = breakColor != null && b.kind != Kind.FOCUS
                    Box(
                        Modifier.weight(b.ms.toFloat()).fillMaxHeight()
                            .graphicsLayer { alpha = if (flat) 1f else a.value; scaleY = sc.value }
                            .background(colorOf(b), RoundedCornerShape(50))
                    )
                }
            }
        }
    }
}

// ---------------------------------------------------------------- setup flow

@Composable
fun SetupScreen(vm: SessionViewModel, ui: Ui, open: (String) -> Unit) {
    val o = ui.opts
    val cs = MaterialTheme.colorScheme
    val hasPlan = vm.run.value.plan.isNotEmpty()
    val last = ui.step == 3 || (ui.step == 2 && o.brk == false)

    BoxWithConstraints(Modifier.fillMaxSize()) {
        val landscape = maxWidth > maxHeight
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

            if (landscape) {
                // title + big number on the left, controls on the right, so everything fits without scrolling
                Row(Modifier.weight(1f).fillMaxWidth().padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(40.dp)) {
                    Box(Modifier.weight(1f).fillMaxHeight(), contentAlignment = Alignment.CenterStart) {
                        Column(Modifier.verticalScroll(rememberScrollState())) { StepPart(ui.step, 0, vm, o, true) }
                    }
                    Box(Modifier.weight(1f).fillMaxHeight(), contentAlignment = Alignment.CenterStart) {
                        Column(Modifier.verticalScroll(rememberScrollState())) { StepPart(ui.step, 1, vm, o, true) }
                    }
                }
            } else {
                Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
                    StepPart(ui.step, 0, vm, o, false)
                    StepPart(ui.step, 1, vm, o, false)
                }
            }

            Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                if (ui.step > 1 || hasPlan) TextButton(onClick = vm::back) { Text(if (ui.step > 1) "Back" else "Cancel") } else Spacer(Modifier)
                Button(onClick = vm::next, enabled = !(ui.step == 2 && o.brk == null)) { Text(if (last) "Create session" else "Next") }
            }
        }
    }
}

/** part 0 = heading side, part 1 = controls side. Portrait stacks them; landscape puts them side by side. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun StepPart(step: Int, part: Int, vm: SessionViewModel, o: Opts, land: Boolean) {
    val cs = MaterialTheme.colorScheme
    when (step) {
        1 -> if (part == 0) {
            Title("How long will you work?", "Your session runs for this long in total.", land)
            val big = if (land) 64.sp else 76.sp
            Row(verticalAlignment = Alignment.Bottom, modifier = Modifier.padding(vertical = 8.dp)) {
                Text("${o.total / 60}", fontSize = big, fontWeight = FontWeight.Light)
                Text("h", fontSize = 22.sp, color = cs.onSurfaceVariant, modifier = Modifier.padding(start = 6.dp, end = 14.dp, bottom = 12.dp))
                Text("%02d".format(o.total % 60), fontSize = big, fontWeight = FontWeight.Light)
                Text("m", fontSize = 22.sp, color = cs.onSurfaceVariant, modifier = Modifier.padding(start = 6.dp, bottom = 12.dp))
            }
        } else {
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
        2 -> if (part == 0) {
            Title("Add breaks?", "Total time: ${fmt(o.total)}", land)
        } else {
            ChoiceCard("No breaks", "One focus timer for the whole session.", o.brk == false) { vm.setOpts { it.copy(brk = false) } }
            Spacer(Modifier.height(12.dp))
            ChoiceCard("Yes, plan breaks", "Short breaks, and long breaks if you want them. You choose the lengths.", o.brk == true) { vm.setOpts { it.copy(brk = true) } }
        }
        else -> if (part == 0) PlanHead(vm, o, land) else PlanBody(vm, o)
    }
}

@Composable
fun Title(title: String, sub: String, land: Boolean = false) {
    Text(
        title, style = if (land) MaterialTheme.typography.headlineSmall else MaterialTheme.typography.headlineMedium,
        modifier = Modifier.padding(top = if (land) 0.dp else 20.dp, bottom = 6.dp),
    )
    Text(sub, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(bottom = if (land) 12.dp else 20.dp))
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

/** Heading + the Flow State card, the star of the app. */
@Composable
fun PlanHead(vm: SessionViewModel, o: Opts, land: Boolean) {
    Title("Plan your session", "Pick your rhythm, or let Flow State find it.", land)
    FlowCard(vm, o)
    Spacer(Modifier.height(16.dp))
}

@Composable
fun FlowCard(vm: SessionViewModel, o: Opts) {
    val cs = MaterialTheme.colorScheme
    val glow = LocalGlow.current
    val shape = RoundedCornerShape(24.dp)
    val on = o.flow
    val wash = if (on) .38f else .14f
    Column(
        Modifier.fillMaxWidth().clip(shape)
            .background(Brush.verticalGradient(listOf(glow.g1.copy(alpha = wash), glow.g2.copy(alpha = wash))))
            .border(if (on) 2.dp else 1.dp, if (on) cs.primary else cs.outline.copy(alpha = .5f), shape)
            .clickable { vm.setOpts { it.copy(flow = !on) } }
            .padding(20.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f).padding(end = 12.dp)) {
                Text("FLOW STATE", fontSize = 11.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 1.5.sp, color = cs.primary)
                Text("Work until you lose focus", fontSize = 20.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 2.dp))
            }
            Switch(checked = on, onCheckedChange = { v -> vm.setOpts { it.copy(flow = v) } })
        }
        Text(
            "No alarm in the middle of deep focus. Keep going past the timer, then take a break sized to how long you really worked. The rest of the plan adjusts so you still finish on time.",
            fontSize = 13.sp, lineHeight = 19.sp, color = cs.onSurfaceVariant, modifier = Modifier.padding(top = 10.dp),
        )
        AnimatedVisibility(visible = on) {
            Text(
                "e.g. work ${o.f * 2} min → earn a ${o.s * 2} min break",
                fontSize = 13.sp, fontWeight = FontWeight.Medium, color = cs.primary, modifier = Modifier.padding(top = 10.dp),
            )
        }
    }
}

/** Plan preview + the rhythm settings, tucked away until you want them. */
@Composable
fun PlanBody(vm: SessionViewModel, o: Opts) {
    val cs = MaterialTheme.colorScheme
    val p = buildPlan(o, forceBreaks = true)
    val fm = p.filter { it.kind == Kind.FOCUS }
    val bm = p.filter { it.kind != Kind.FOCUS }
    var open by remember { mutableStateOf(false) }

    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(cs.surfaceContainer).padding(16.dp)) {
        PlanBar(p, null, Modifier.fillMaxWidth(), 14.dp)
        Row(Modifier.padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            Legend("Focus", kindColor(Kind.FOCUS)); Legend("Short break", kindColor(Kind.SHORT)); Legend("Long break", kindColor(Kind.LONG))
        }
        Text(
            "${fm.size} focus · ${bm.size} ${if (bm.size == 1) "break" else "breaks"} · ${fmt(o.total)}",
            fontSize = 14.sp, color = cs.onSurfaceVariant, modifier = Modifier.padding(top = 10.dp),
        )
    }

    Row(
        Modifier.fillMaxWidth().padding(top = 12.dp).clip(RoundedCornerShape(12.dp)).clickable { open = !open }.padding(vertical = 6.dp),
        horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text("Rhythm", fontWeight = FontWeight.Medium)
            Text(
                "${o.f} min focus · ${o.s} min break" + if (o.useLong) " · ${o.l} min long break every ${o.every}" else "",
                fontSize = 12.sp, color = cs.onSurfaceVariant,
            )
        }
        TextButton(onClick = { open = !open }) { Text(if (open) "Done" else "Edit") }
    }
    AnimatedVisibility(visible = open) {
        Column {
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
    }
}

@Composable
fun Legend(text: String, color: Color) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(8.dp).background(color, CircleShape))
        Text(text, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(start = 5.dp))
    }
}
