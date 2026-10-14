package com.example.pomodoro

import android.os.Build
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
fun RunScreen(vm: SessionViewModel, ui: Ui, open: (String) -> Unit) {
    val run by vm.run.collectAsStateWithLifecycle()
    val b = run.plan.getOrNull(run.idx) ?: return
    val cs = MaterialTheme.colorScheme

    Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            PlanBar(run.plan, if (run.finished) run.plan.size else run.idx, Modifier.weight(1f))
            TopButtons(open)
            SmallIconButton(Icons.Rounded.Tune, "New session") { vm.openSetup() }
        }

        Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
            TimerRing(run, b)
        }

        if (run.finished) {
            val focus = run.plan.filter { it.kind == Kind.FOCUS }.sumOf { it.mins }
            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(bottom = 24.dp)) {
                Text("Session complete", style = MaterialTheme.typography.titleLarge)
                Text("You focused for ${fmt(focus)}.", color = cs.onSurfaceVariant)
                Spacer(Modifier.height(6.dp))
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
            Text("$label · ${fmt(leftMin)} left", fontSize = 14.sp, color = cs.onSurfaceVariant, modifier = Modifier.padding(bottom = 24.dp))
            Controls(run.running, vm)
            Spacer(Modifier.height(8.dp))
        }
    }
}

@Composable
fun TimerRing(run: Run, b: Block) {
    val cs = MaterialTheme.colorScheme
    val glow = LocalGlow.current
    BoxWithConstraints(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        val d = minOf(300.dp, maxWidth * 0.78f, maxHeight * 0.8f)

        // contrasting vertical gradient capsule, blurred, behind the timer
        val w = d * 0.62f
        val h = d * 1.5f
        val brush = Brush.verticalGradient(listOf(glow.g1, glow.g2))
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val pad = 80.dp
            Box(
                Modifier.requiredSize(w + pad * 2, h + pad * 2).blur(36.dp, BlurredEdgeTreatment.Unbounded),
                contentAlignment = Alignment.Center,
            ) {
                Box(
                    Modifier.size(w, h).background(
                        Brush.verticalGradient(listOf(glow.g1.copy(alpha = .9f), glow.g2.copy(alpha = .9f))),
                        RoundedCornerShape(50),
                    )
                )
            }
        } else { // older phones: fake the soft edge with stacked translucent capsules
            Canvas(Modifier.requiredSize(w, h)) {
                for (i in 8 downTo 0) {
                    val e = (i * 4).dp.toPx()
                    drawRoundRect(
                        brush = Brush.verticalGradient(listOf(glow.g1, glow.g2), startY = -e, endY = size.height + e),
                        topLeft = Offset(-e, -e), size = Size(size.width + 2 * e, size.height + 2 * e),
                        cornerRadius = CornerRadius((size.width + 2 * e) / 2), alpha = .11f,
                    )
                }
            }
        }

        val progress by animateFloatAsState(
            targetValue = (run.remaining / b.ms.toFloat()).coerceIn(0f, 1f),
            animationSpec = if (run.running) tween(250, easing = LinearEasing) else tween(500, easing = FastOutSlowInEasing),
            label = "progress",
        )
        Box(Modifier.size(d), contentAlignment = Alignment.Center) {
            Canvas(Modifier.fillMaxSize()) {
                val sw = 14.dp.toPx()
                val tl = Offset(sw / 2, sw / 2)
                val sz = Size(size.width - sw, size.height - sw)
                drawArc(cs.secondaryContainer, 0f, 360f, false, tl, sz, style = Stroke(sw))
                if (progress > .004f) {
                    drawArc(cs.primary, -90f, 360f * progress, false, tl, sz, style = Stroke(sw, cap = StrokeCap.Round))
                }
            }
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
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
fun Controls(running: Boolean, vm: SessionViewModel) {
    // Play button morphs from a pill to a squarer shape while running.
    val corner by animateDpAsState(if (running) 28.dp else 44.dp, spring(dampingRatio = .7f, stiffness = 400f), label = "corner")
    Row(
        Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterHorizontally),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        FilledTonalIconButton(onClick = vm::reset, modifier = Modifier.size(64.dp), colors = tintedButtonColors()) { Icon(Icons.Rounded.Refresh, "Restart block") }
        FilledIconButton(onClick = vm::toggle, modifier = Modifier.size(120.dp, 88.dp), shape = RoundedCornerShape(corner)) {
            Crossfade(running, label = "playPause") {
                Icon(if (it) Icons.Rounded.Pause else Icons.Rounded.PlayArrow, if (it) "Pause" else "Start", Modifier.size(40.dp))
            }
        }
        FilledTonalIconButton(onClick = vm::skip, modifier = Modifier.size(64.dp), colors = tintedButtonColors()) { Icon(Icons.Rounded.SkipNext, "Next block") }
    }
}
