package com.example.pomodoro

import android.os.Build
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.ui.graphics.GraphicsLayerScope
import kotlinx.coroutines.launch
import android.view.Surface
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.Dp
import kotlinx.coroutines.delay
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.BlurredEdgeTreatment
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlin.math.ceil
import kotlin.math.max

@Composable
fun Actions(open: (String) -> Unit, vm: SessionViewModel) {
    TopButtons(open)
    SmallIconButton(Icons.Rounded.Tune, "New session") { vm.openSetup() }
}

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
        Text("$label · ${fmt(leftMin)} left", fontSize = 14.sp, color = cs.onSurfaceVariant, modifier = Modifier.padding(bottom = bottom))
    }
}

@Composable
fun RunScreen(vm: SessionViewModel, ui: Ui, open: (String) -> Unit) {
    val run by vm.run.collectAsStateWithLifecycle()
    val b = run.plan.getOrNull(run.idx) ?: return

    // While a focus block runs, the buttons fade away after 4 s; any tap brings them back.
    var visible by remember { mutableStateOf(true) }
    var touch by remember { mutableIntStateOf(0) }
    val focusRunning = run.running && b.kind == Kind.FOCUS && !run.finished
    LaunchedEffect(focusRunning, touch) { if (focusRunning) { delay(4000); visible = false } else visible = true }
    val a by animateFloatAsState(if (visible) 1f else 0f, tween(if (visible) 350 else 700), label = "chrome")
    val fade = Modifier.graphicsLayer { alpha = a; scaleX = .94f + .06f * a; scaleY = .94f + .06f * a }

    val rotation = LocalView.current.display?.rotation ?: Surface.ROTATION_90
    val cur = if (run.finished) run.plan.size else run.idx

    BoxWithConstraints(
        Modifier.fillMaxSize().pointerInput(Unit) { awaitEachGesture { awaitFirstDown(requireUnconsumed = false); touch++ } }
    ) {
        if (maxWidth <= maxHeight) {
            // ---------- portrait ----------
            Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally) {
                Row(Modifier.fillMaxWidth().then(fade), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    PlanBar(run.plan, cur, Modifier.weight(1f))
                    Actions(open, vm)
                }
                Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) { TimerRing(run, b) }
                Status(run, b, vm, 24.dp)
                if (!run.finished) {
                    Box(fade) { Controls(run.running, vm, vertical = false) }
                    Spacer(Modifier.height(8.dp))
                }
            }
        } else {
            // ---------- landscape: the same pieces, turned onto the sides; the timer stays centred ----------
            val topFirst = rotation != Surface.ROTATION_270   // phone turned clockwise: swap sides
            val topGroup: @Composable () -> Unit = {
                Column(Modifier.fillMaxHeight().then(fade), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Actions(open, vm)
                    PlanBar(run.plan, cur, Modifier.weight(1f), vertical = true)
                }
            }
            val controlGroup: @Composable () -> Unit = {
                if (!run.finished) {
                    Column(Modifier.fillMaxHeight().then(fade), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                        Controls(run.running, vm, vertical = true)
                    }
                } else Spacer(Modifier.width(40.dp))
            }
            Row(Modifier.fillMaxSize(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                if (topFirst) topGroup() else controlGroup()
                Column(Modifier.weight(1f).fillMaxHeight(), horizontalAlignment = Alignment.CenterHorizontally) {
                    Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) { TimerRing(run, b) }
                    Status(run, b, vm, 4.dp)
                }
                if (topFirst) controlGroup() else topGroup()
            }
        }
        // while the buttons are hidden, the first tap only brings them back
        if (!visible) Box(Modifier.fillMaxSize().pointerInput(Unit) { detectTapGestures { visible = true; touch++ } })
    }
}

@Composable
fun TimerRing(run: Run, b: Block) {
    val cs = MaterialTheme.colorScheme
    val glow = LocalGlow.current
    val running = run.running
    val paused = !running && !run.finished && run.remaining < b.ms

    // ---- start / pause feedback: a soft ripple + a small settle, and the time dims while paused ----
    val pulse = remember { Animatable(1f) }
    val ripple = remember { Animatable(1f) }          // 1f = finished (invisible)
    val prev = remember { mutableStateOf(running) }
    LaunchedEffect(running) {
        if (running == prev.value) return@LaunchedEffect
        prev.value = running
        if (running) {
            launch { ripple.snapTo(0f); ripple.animateTo(1f, tween(1100, easing = FastOutSlowInEasing)) }
            pulse.animateTo(1.04f, tween(160, easing = FastOutSlowInEasing))
            pulse.animateTo(1f, spring(dampingRatio = .45f, stiffness = 260f))
        } else {
            pulse.animateTo(.965f, tween(140))
            pulse.animateTo(1f, spring(dampingRatio = .55f, stiffness = 240f))
        }
    }
    val dim by animateFloatAsState(if (paused) .6f else 1f, tween(500), label = "dim")

    // ---- background glow: calmer, softer, slowly drifting; a little brighter while running ----
    val glowA by animateFloatAsState(if (running) .6f else .4f, tween(900), label = "glowA")
    val glowS by animateFloatAsState(if (running) 1.06f else 1f, tween(1400, easing = FastOutSlowInEasing), label = "glowS")
    val inf = rememberInfiniteTransition(label = "drift")
    val d1 by inf.animateFloat(-1f, 1f, infiniteRepeatable(tween(11000, easing = FastOutSlowInEasing), RepeatMode.Reverse), label = "d1")
    val d2 by inf.animateFloat(-1f, 1f, infiniteRepeatable(tween(15000, easing = FastOutSlowInEasing), RepeatMode.Reverse), label = "d2")
    val drift: GraphicsLayerScope.() -> Unit = {
        translationX = d1 * 14.dp.toPx(); translationY = d2 * 12.dp.toPx(); rotationZ = d1 * 5f
        scaleX = glowS * (1f + .04f * d2); scaleY = glowS * (1f + .03f * d1)
    }

    BoxWithConstraints(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        val d = minOf(300.dp, maxWidth * 0.78f, maxHeight * 0.8f)
        val w = d * 0.62f
        val h = d * 1.5f

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val pad = 150.dp   // room for the wide blur so it is never cut off
            Box(
                Modifier.requiredSize(w + pad * 2, h + pad * 2).graphicsLayer(drift)
                    .blur(56.dp, BlurredEdgeTreatment.Unbounded),
                contentAlignment = Alignment.Center,
            ) {
                Box(
                    Modifier.size(w, h).background(
                        Brush.verticalGradient(listOf(glow.g1.copy(alpha = glowA), glow.g2.copy(alpha = glowA))),
                        RoundedCornerShape(50),
                    )
                )
            }
        } else { // older phones: fake the soft edge with stacked translucent capsules
            Canvas(Modifier.requiredSize(w, h).graphicsLayer(drift)) {
                for (i in 8 downTo 0) {
                    val e = (i * 5).dp.toPx()
                    drawRoundRect(
                        brush = Brush.verticalGradient(listOf(glow.g1, glow.g2), startY = -e, endY = size.height + e),
                        topLeft = Offset(-e, -e), size = Size(size.width + 2 * e, size.height + 2 * e),
                        cornerRadius = CornerRadius((size.width + 2 * e) / 2), alpha = .08f * (glowA / .6f),
                    )
                }
            }
        }

        val progress by animateFloatAsState(
            targetValue = (run.remaining / b.ms.toFloat()).coerceIn(0f, 1f),
            animationSpec = if (running) tween(250, easing = LinearEasing) else tween(500, easing = FastOutSlowInEasing),
            label = "progress",
        )
        Box(Modifier.size(d).graphicsLayer { scaleX = pulse.value; scaleY = pulse.value }, contentAlignment = Alignment.Center) {
            Canvas(Modifier.fillMaxSize()) {
                val sw = 14.dp.toPx()
                val tl = Offset(sw / 2, sw / 2)
                val sz = Size(size.width - sw, size.height - sw)
                drawArc(cs.secondaryContainer, 0f, 360f, false, tl, sz, style = Stroke(sw))
                if (progress > .004f) {
                    drawArc(cs.primary.copy(alpha = dim), -90f, 360f * progress, false, tl, sz, style = Stroke(sw, cap = StrokeCap.Round))
                }
                val rp = ripple.value
                if (rp < 1f) {
                    drawCircle(cs.primary.copy(alpha = (1f - rp) * .35f), radius = (size.width - sw) / 2f * (1f + .3f * rp), style = Stroke(3.dp.toPx()))
                }
            }
            Column(Modifier.graphicsLayer { alpha = dim }, horizontalAlignment = Alignment.CenterHorizontally) {
                TimeText(run.remaining)
                Crossfade(
                    targetState = if (run.finished) "All done" else when (b.kind) {
                        Kind.FOCUS -> "Stay focused"; Kind.SHORT -> "Take a breather"; Kind.LONG -> "You earned it"
                    },
                    label = "caption",
                ) { Text(it, style = MaterialTheme.typography.titleMedium, color = cs.onSurfaceVariant) }
            }
        }
    }
}

/** Each character animates on its own: a soft 1/5-height slide + fade, so only changing digits move. */
@Composable
fun TimeText(ms: Long) {
    val secs = (ms + 999) / 1000
    val h = secs / 3600; val m = secs % 3600 / 60; val s = secs % 60
    val text = if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%02d:%02d".format(m, s)
    Row(verticalAlignment = Alignment.CenterVertically) {
        text.forEachIndexed { i, ch ->
            key(i) {
                AnimatedContent(
                    targetState = ch, label = "digit$i",
                    transitionSpec = {
                        (slideInVertically(tween(220)) { it / 5 } + fadeIn(tween(220))) togetherWith
                            (slideOutVertically(tween(220)) { -it / 5 } + fadeOut(tween(120)))
                    },
                ) { c ->
                    Text(
                        c.toString(), fontSize = if (h > 0) 54.sp else 64.sp, fontWeight = FontWeight.Light,
                        style = TextStyle(fontFeatureSettings = "tnum"),
                    )
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
