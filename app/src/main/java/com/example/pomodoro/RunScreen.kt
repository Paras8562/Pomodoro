package com.example.pomodoro

import android.os.Build
import android.view.Surface
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.core.snap
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.layout
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.BlurredEdgeTreatment
import androidx.compose.ui.draw.blur
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.GraphicsLayerScope
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.max
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** "Focus · 30m left", or the session-complete card. */
@Composable
fun Status(run: Run, b: Block, vm: SessionViewModel, bottom: Dp) {
    val cs = MaterialTheme.colorScheme
    if (run.finished) {
        val focus = run.plan.filter { it.kind == Kind.FOCUS }.sumOf { it.mins }
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(bottom = bottom)) {
            Text("Session complete", style = MaterialTheme.typography.titleLarge)
            Text("You focused for ${fmt(focus)}.", color = cs.onSurfaceVariant)
            Button(onClick = vm::openSetup) { Text("New session") }
        }
    } else {
        val fc = run.plan.count { it.kind == Kind.FOCUS }
        val fi = run.plan.take(run.idx + 1).count { it.kind == Kind.FOCUS }
        val label = when (b.kind) {
            Kind.FOCUS -> if (fc > 1) "Focus $fi of $fc" else "Focus"
            Kind.SHORT -> "Short break"
            Kind.LONG -> "Long break"
        }
        val later = run.plan.drop(run.idx + 1).sumOf { it.ms }
        val leftMin = max(1, ceil((run.remaining + later) / 60000.0).toInt())
        Text("$label · ${fmt(leftMin)} left", fontSize = 14.sp, color = cs.primary, modifier = Modifier.padding(bottom = bottom))
    }
}

@Composable
fun RunScreen(vm: SessionViewModel, ui: Ui, open: (String) -> Unit) {
    val run by vm.run.collectAsStateWithLifecycle()
    val b = run.plan.getOrNull(run.idx) ?: return
    val cs = MaterialTheme.colorScheme
    val openNow by rememberUpdatedState(open)

    // While a focus block runs, the plan bar and buttons fade away after 4 s; any tap brings them back.
    var visible by remember { mutableStateOf(true) }
    var touch by remember { mutableIntStateOf(0) }
    val focusRunning = run.running && b.kind == Kind.FOCUS && !run.finished
    LaunchedEffect(focusRunning, touch) { if (focusRunning) { delay(4000); visible = false } else visible = true }
    val a by animateFloatAsState(if (visible) 1f else 0f, tween(if (visible) 350 else 700), label = "chrome")
    val fade = Modifier.graphicsLayer { alpha = a; scaleX = .94f + .06f * a; scaleY = .94f + .06f * a }

    // gesture hint: shown for the first 3 sessions; closes after 9 s or on any touch
    LaunchedEffect(Unit) { vm.maybeShowHint(); if (vm.hintVisible.value) { delay(9000); vm.hideHint() } }
    LaunchedEffect(touch) { if (touch > 0) vm.hideHint() }

    val rotation = LocalView.current.display?.rotation ?: Surface.ROTATION_90
    val cur = if (run.finished) run.plan.size else run.idx
    val thr = with(LocalDensity.current) { 72.dp.toPx() }

    BoxWithConstraints(
        Modifier.fillMaxSize()
            .pointerInput(Unit) { awaitEachGesture { awaitFirstDown(requireUnconsumed = false); touch++ } }
            .pointerInput(Unit) {
                // swipe up = timer settings, swipe left = theme, swipe right = sound
                var dx = 0f
                var dy = 0f
                detectDragGestures(
                    onDragStart = { dx = 0f; dy = 0f },
                    onDragEnd = {
                        if (abs(dx) > abs(dy)) {
                            if (dx > thr) openNow("snd") else if (dx < -thr) openNow("look")
                        } else if (dy < -thr) vm.openSetup()
                    },
                ) { _, amount -> dx += amount.x; dy += amount.y }
            }
    ) {
        if (maxWidth <= maxHeight) {
            // ---------- portrait ----------
            Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally) {
                Box(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp).then(fade)) {
                    PlanBar(run.plan, cur, Modifier.fillMaxWidth(), gap = 6.dp, breakColor = cs.secondaryContainer)
                }
                CenterBlock(run, b, vm, false, Modifier.weight(1f).fillMaxWidth())
                if (!run.finished) {
                    Box(fade) { Controls(run.running, vm, vertical = false) }
                    Spacer(Modifier.height(8.dp))
                }
            }
        } else {
            // ---------- landscape: same pieces turned onto the sides; the timer stays centred ----------
            val barFirst = rotation != Surface.ROTATION_270   // phone turned clockwise: swap sides
            val barGroup: @Composable () -> Unit = {
                Column(Modifier.fillMaxHeight().width(88.dp).then(fade), horizontalAlignment = Alignment.Start, verticalArrangement = Arrangement.Center) {
                    PlanBar(run.plan, cur, Modifier.fillMaxHeight(.72f), vertical = true, gap = 6.dp, breakColor = cs.secondaryContainer)
                }
            }
            val controlGroup: @Composable () -> Unit = {
                if (!run.finished) {
                    Column(Modifier.fillMaxHeight().width(88.dp).then(fade), horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.Center) {
                        Controls(run.running, vm, vertical = true)
                    }
                } else Spacer(Modifier.width(88.dp))
            }
            Row(Modifier.fillMaxSize(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                if (barFirst) barGroup() else controlGroup()
                CenterBlock(run, b, vm, true, Modifier.weight(1f).fillMaxHeight())
                if (barFirst) controlGroup() else barGroup()
            }
        }
        AnimatedVisibility(
            visible = vm.hintVisible.value, enter = fadeIn(tween(500)), exit = fadeOut(tween(500)),
            modifier = Modifier.align(if (maxWidth > maxHeight) Alignment.Center else Alignment.BottomCenter)
                .padding(bottom = if (maxWidth > maxHeight) 0.dp else 150.dp),
        ) { HintCard() }
        // while the controls are hidden, the first tap only brings them back
        if (!visible) Box(Modifier.fillMaxSize().pointerInput(Unit) { detectTapGestures { visible = true; touch++ } })
    }
}

@Composable
fun HintCard() {
    val cs = MaterialTheme.colorScheme
    Column(
        Modifier.clip(RoundedCornerShape(20.dp)).background(cs.surfaceContainer.copy(alpha = .96f)).padding(horizontal = 22.dp, vertical = 14.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text("Swipe anywhere", fontWeight = FontWeight.SemiBold, color = cs.primary, fontSize = 13.sp)
        Text("↑   Timer settings", fontSize = 13.sp)
        Text("←   Theme", fontSize = 13.sp)
        Text("→   Sound", fontSize = 13.sp)
    }
}

/** Big time + caption on the glow, then the thin progress line and the "Focus 3 of 3 · 30m left" text. */
@Composable
fun CenterBlock(run: Run, b: Block, vm: SessionViewModel, landscape: Boolean, modifier: Modifier) {
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Box(Modifier.weight(1f).fillMaxWidth()) { TimerFace(run, b, landscape) }
        if (!run.finished) {
            ProgressLine(run, b, Modifier.widthIn(max = 360.dp).fillMaxWidth().padding(horizontal = 4.dp))
            Spacer(Modifier.height(10.dp))
        }
        Status(run, b, vm, if (landscape) 0.dp else 14.dp)
    }
}

/** Thin progress line for the current block, with a round thumb. */
@Composable
fun ProgressLine(run: Run, b: Block, modifier: Modifier) {
    val cs = MaterialTheme.colorScheme
    val p by animateFloatAsState(
        targetValue = 1f - (run.remaining / b.ms.toFloat()).coerceIn(0f, 1f),
        animationSpec = if (run.running) tween(250, easing = LinearEasing) else tween(500, easing = FastOutSlowInEasing),
        label = "line",
    )
    Canvas(modifier.height(20.dp)) {
        val th = 6.dp.toPx()
        val r = 8.dp.toPx()
        val y = size.height / 2
        val x0 = r
        val x1 = size.width - r
        val x = x0 + (x1 - x0) * p
        drawLine(cs.primary.copy(alpha = .3f), Offset(x0, y), Offset(x1, y), th, StrokeCap.Round)
        drawLine(cs.primary, Offset(x0, y), Offset(x, y), th, StrokeCap.Round)
        drawCircle(cs.primary, r, Offset(x, y))
    }
}

@Composable
fun TimerFace(run: Run, b: Block, landscape: Boolean) {
    val cs = MaterialTheme.colorScheme
    val glow = LocalGlow.current
    val running = run.running
    val paused = !running && !run.finished && run.remaining < b.ms

    // ---- start / pause feedback: a small settle, and the time dims while paused ----
    val pulse = remember { Animatable(1f) }
    val prev = remember { mutableStateOf(running) }
    LaunchedEffect(running) {
        if (running == prev.value) return@LaunchedEffect
        prev.value = running
        if (running) {
            pulse.animateTo(1.04f, tween(160, easing = FastOutSlowInEasing))
            pulse.animateTo(1f, spring(dampingRatio = .45f, stiffness = 260f))
        } else {
            pulse.animateTo(.965f, tween(140))
            pulse.animateTo(1f, spring(dampingRatio = .55f, stiffness = 240f))
        }
    }
    val dim by animateFloatAsState(if (paused) .6f else 1f, tween(500), label = "dim")

    // ---- background glow: soft, slowly drifting; a little brighter while running ----
    val glowA by animateFloatAsState(if (running) .6f else .42f, tween(900), label = "glowA")
    val glowS by animateFloatAsState(if (running) 1.06f else 1f, tween(1400, easing = FastOutSlowInEasing), label = "glowS")
    val inf = rememberInfiniteTransition(label = "drift")
    val d1 by inf.animateFloat(-1f, 1f, infiniteRepeatable(tween(11000, easing = FastOutSlowInEasing), RepeatMode.Reverse), label = "d1")
    val d2 by inf.animateFloat(-1f, 1f, infiniteRepeatable(tween(15000, easing = FastOutSlowInEasing), RepeatMode.Reverse), label = "d2")
    val drift: GraphicsLayerScope.() -> Unit = {
        translationX = d1 * 14.dp.toPx(); translationY = d2 * 12.dp.toPx(); rotationZ = d1 * 4f
        scaleX = glowS * (1f + .04f * d2); scaleY = glowS * (1f + .03f * d1)
    }

    BoxWithConstraints(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        // portrait: pink on top fading to blue below; landscape: the same turned on its side (blue left, pink right)
        val gw = if (landscape) minOf(maxWidth * .95f, 640.dp) else minOf(maxWidth * .8f, 320.dp)
        val gh = if (landscape) maxHeight * .75f else maxHeight * 1.05f
        val c1 = glow.g1.copy(alpha = glowA)
        val c2 = glow.g2.copy(alpha = glowA)
        val brush = if (landscape) Brush.horizontalGradient(listOf(c2, c1)) else Brush.verticalGradient(listOf(c1, c2))

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val pad = 150.dp   // room for the wide blur so it is never cut off
            Box(
                Modifier.requiredSize(gw + pad * 2, gh + pad * 2).graphicsLayer(drift)
                    .blur(56.dp, BlurredEdgeTreatment.Unbounded),
                contentAlignment = Alignment.Center,
            ) { Box(Modifier.size(gw, gh).background(brush, RoundedCornerShape(50))) }
        } else { // older phones: fake the soft edge with stacked translucent capsules
            Canvas(Modifier.requiredSize(gw, gh).graphicsLayer(drift)) {
                for (i in 8 downTo 0) {
                    val e = (i * 5).dp.toPx()
                    val br = if (landscape) Brush.horizontalGradient(listOf(glow.g2, glow.g1), startX = -e, endX = size.width + e)
                    else Brush.verticalGradient(listOf(glow.g1, glow.g2), startY = -e, endY = size.height + e)
                    drawRoundRect(
                        brush = br, topLeft = Offset(-e, -e), size = Size(size.width + 2 * e, size.height + 2 * e),
                        cornerRadius = CornerRadius(minOf(size.width, size.height) / 2 + e), alpha = .08f * (glowA / .6f),
                    )
                }
            }
        }

        val fs = with(LocalDensity.current) {
            (if (landscape) minOf(maxHeight * .52f, maxWidth * .26f) else minOf(maxHeight * .30f, maxWidth * .58f)).toSp()
        }
        Column(
            Modifier.graphicsLayer { scaleX = pulse.value; scaleY = pulse.value; alpha = dim },
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            TimeGroups(run.remaining, fs, stacked = !landscape)
            Crossfade(
                targetState = if (run.finished) "All Done" else when (b.kind) {
                    Kind.FOCUS -> "Stay Focused"; Kind.SHORT -> "Take a Breather"; Kind.LONG -> "You Earned It"
                },
                label = "caption",
            ) { Text(it, style = MaterialTheme.typography.titleMedium, color = cs.primary) }
        }
    }
}

/** Bold digits: minutes over seconds in portrait, side by side in landscape (hours get their own group). */
@Composable
fun TimeGroups(ms: Long, fs: TextUnit, stacked: Boolean) {
    val secs = (ms + 999) / 1000
    val h = secs / 3600
    val m = secs % 3600 / 60
    val s = secs % 60
    val groups = if (h > 0) listOf(h.toString(), "%02d".format(m), "%02d".format(s)) else listOf("%02d".format(m), "%02d".format(s))
    val size = if (h > 0) fs * 0.72f else fs
    if (stacked) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) { groups.forEachIndexed { gi, g -> DigitRow(g, size, gi) } }
    } else {
        val gap = with(LocalDensity.current) { (size * 0.28f).toDp() }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(gap)) {
            groups.forEachIndexed { gi, g -> DigitRow(g, size, gi) }
        }
    }
}

/** Each character animates on its own: a soft 1/5-height slide + fade, so only changing digits move. */
@Composable
fun DigitRow(g: String, size: TextUnit, gi: Int) {
    val dens = LocalDensity.current
    val slotW = with(dens) { (size * 0.64f).toDp() }
    // Row pitch 0.9 em: a digit is ~0.7 em tall, so ~0.2 em of air between stacked rows.
    val slotH = with(dens) { (size * 0.9f).toDp() }
    Row {
        g.forEachIndexed { i, ch ->
            key(gi * 10 + i) {
                // Fixed-size slot, digit centred in it and free to overflow it (never clipped, never drifting sideways).
                Box(Modifier.size(slotW, slotH), contentAlignment = Alignment.Center) {
                    AnimatedContent(
                        targetState = ch, modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center,
                        label = "digit$gi$i",
                        transitionSpec = {
                            ((slideInVertically(tween(220)) { it / 5 } + fadeIn(tween(220))) togetherWith
                                (slideOutVertically(tween(220)) { -it / 5 } + fadeOut(tween(120))))
                                .using(SizeTransform(clip = false) { _, _ -> snap() })
                        },
                    ) { c ->
                        Text(
                            c.toString(), fontSize = size, fontFamily = Poppins, fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary,
                            softWrap = false, maxLines = 1, overflow = TextOverflow.Visible,
                            modifier = Modifier.wrapContentSize(Alignment.Center, unbounded = true),
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun Controls(running: Boolean, vm: SessionViewModel, vertical: Boolean) {
    // Play button morphs from a pill to a squarer shape while running.
    val corner by animateDpAsState(if (running) 28.dp else 44.dp, spring(dampingRatio = .7f, stiffness = 400f), label = "corner")
    val content: @Composable () -> Unit = {
        FilledTonalIconButton(onClick = vm::reset, modifier = Modifier.size(64.dp), colors = tintedButtonColors()) { Icon(Icons.Rounded.Refresh, "Restart block") }
        FilledIconButton(
            onClick = vm::toggle,
            modifier = if (vertical) Modifier.size(88.dp, 120.dp) else Modifier.size(120.dp, 88.dp),
            shape = RoundedCornerShape(corner),
        ) {
            Crossfade(running, label = "playPause") {
                Icon(if (it) Icons.Rounded.Pause else Icons.Rounded.PlayArrow, if (it) "Pause" else "Start", Modifier.size(40.dp))
            }
        }
        FilledTonalIconButton(onClick = vm::skip, modifier = Modifier.size(64.dp), colors = tintedButtonColors()) { Icon(Icons.Rounded.SkipNext, "Next block") }
    }
    if (vertical) {
        Column(verticalArrangement = Arrangement.spacedBy(16.dp), horizontalAlignment = Alignment.CenterHorizontally) { content() }
    } else {
        Row(
            Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterHorizontally),
            verticalAlignment = Alignment.CenterVertically,
        ) { content() }
    }
}

/** Reports a smaller height than the content really has, so stacked rows sit closer without clipping the glyphs. */
fun Modifier.tightHeight(factor: Float) = layout { measurable, constraints ->
    val p = measurable.measure(constraints)
    val h = (p.height * factor).toInt()
    layout(p.width, h) { p.placeRelative(0, (h - p.height) / 2) }
}
