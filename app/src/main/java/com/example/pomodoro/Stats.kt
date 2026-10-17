package com.example.pomodoro

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ChevronLeft
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.time.temporal.ChronoUnit
import java.time.temporal.TemporalAdjusters
import java.util.Locale
import kotlin.math.max
import kotlin.math.min

/** Milliseconds as "2h 05m" (rounded to the nearest minute). */
fun fmtMs(ms: Long): String = fmt(((ms + 30_000L) / 60_000L).toInt())

private fun hourLabel(h: Int): String {
    val hh = h % 24
    val h12 = if (hh % 12 == 0) 12 else hh % 12
    return "$h12 ${if (hh < 12) "AM" else "PM"}"
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StatsScreen(vm: SessionViewModel) {
    val cs = MaterialTheme.colorScheme
    @Suppress("UNUSED_VARIABLE") val version = vm.statsVersion.intValue   // re-read whenever new focus time has been saved
    var period by rememberSaveable { mutableIntStateOf(0) }                // 0 day · 1 week · 2 month
    var anchorDay by rememberSaveable { mutableLongStateOf(LocalDate.now().toEpochDay()) }
    val today = LocalDate.now()
    val anchor = LocalDate.ofEpochDay(anchorDay)

    val start = when (period) {
        0 -> anchor
        1 -> anchor.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
        else -> anchor.withDayOfMonth(1)
    }
    val endEx = when (period) { 0 -> start.plusDays(1); 1 -> start.plusDays(7); else -> start.plusMonths(1) }
    val days = ChronoUnit.DAYS.between(start, endEx).toInt()
    val values: List<Long> =
        if (period == 0) vm.focusBuckets(start).toList() else (0 until days).map { vm.focusBuckets(start.plusDays(it.toLong())).sum() }
    val total = values.sum()
    val todayIdx = when {
        period == 0 -> if (start == today) LocalTime.now().hour else -1
        !today.isBefore(start) && today.isBefore(endEx) -> ChronoUnit.DAYS.between(start, today).toInt()
        else -> -1
    }
    var sel by remember(period, anchorDay) { mutableIntStateOf(todayIdx) }
    val canNext = !endEx.isAfter(today)

    val loc = Locale.getDefault()
    val dayFmt = DateTimeFormatter.ofPattern("EEE, d MMM", loc)
    val label = when (period) {
        0 -> when (anchor) { today -> "Today"; today.minusDays(1) -> "Yesterday"; else -> anchor.format(dayFmt) }
        1 -> if (start == today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))) "This week"
        else "${start.format(DateTimeFormatter.ofPattern("d MMM", loc))} – ${endEx.minusDays(1).format(DateTimeFormatter.ofPattern("d MMM", loc))}"
        else -> if (start.year == today.year && start.month == today.month) "This month"
        else start.format(DateTimeFormatter.ofPattern("MMMM yyyy", loc))
    }
    fun shift(dir: Int) {
        anchorDay = when (period) {
            0 -> anchor.plusDays(dir.toLong())
            1 -> anchor.plusDays(7L * dir)
            else -> anchor.plusMonths(dir.toLong())
        }.toEpochDay()
    }

    val labelFor: (Int) -> String = { i ->
        when (period) {
            0 -> when (i) { 0 -> "12a"; 6 -> "6a"; 12 -> "12p"; 18 -> "6p"; else -> "" }
            1 -> start.plusDays(i.toLong()).dayOfWeek.getDisplayName(TextStyle.NARROW, loc)
            else -> if (i == 0 || (i + 1) % 5 == 0) "${i + 1}" else ""
        }
    }
    val detail = when {
        sel < 0 || sel >= values.size -> "Tap a bar for details"
        period == 0 -> "${hourLabel(sel)} – ${hourLabel(sel + 1)} · ${fmtMs(values[sel])}"
        else -> "${start.plusDays(sel.toLong()).format(dayFmt)} · ${fmtMs(values[sel])}"
    }

    // the small summary cards under the chart
    val elapsed = if (period == 0) 1 else max(1, min(days, ChronoUnit.DAYS.between(start, today).toInt() + 1))
    val best = values.indices.maxByOrNull { values[it] } ?: 0
    val bestValue = values.getOrElse(best) { 0L }
    val cards: List<Pair<String, String>> = when (period) {
        0 -> listOf(
            "Peak hour" to if (bestValue > 0) "${hourLabel(best)} · ${fmtMs(bestValue)}" else "–",
            "7-day average" to fmtMs((0..6).sumOf { vm.focusBuckets(anchor.minusDays(it.toLong())).sum() } / 7),
        )
        1 -> listOf(
            "Daily average" to fmtMs(total / elapsed),
            "Best day" to if (bestValue > 0) "${start.plusDays(best.toLong()).dayOfWeek.getDisplayName(TextStyle.SHORT, loc)} · ${fmtMs(bestValue)}" else "–",
        )
        else -> listOf(
            "Daily average" to fmtMs(total / elapsed),
            "Best day" to if (bestValue > 0) "${best + 1} ${start.month.getDisplayName(TextStyle.SHORT, loc)} · ${fmtMs(bestValue)}" else "–",
            "Active days" to "${values.count { it > 0 }} of $elapsed",
        )
    }

    val head: @Composable ColumnScope.() -> Unit = {
        Title("Your focus", "Time spent in focus blocks, flow time included.", false)
        val opts = listOf("Day", "Week", "Month")
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
            opts.forEachIndexed { i, n ->
                SegmentedButton(
                    selected = period == i, onClick = { period = i; anchorDay = today.toEpochDay() },
                    shape = SegmentedButtonDefaults.itemShape(i, opts.size), label = { Text(n) },
                )
            }
        }
        Row(Modifier.fillMaxWidth().padding(top = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { shift(-1) }) { Icon(Icons.Rounded.ChevronLeft, "Previous") }
            Text(label, Modifier.weight(1f), textAlign = androidx.compose.ui.text.style.TextAlign.Center, fontWeight = FontWeight.Medium)
            IconButton(onClick = { shift(1) }, enabled = canNext) { Icon(Icons.Rounded.ChevronRight, "Next") }
        }
        BigDuration(total)
        if (total == 0L) {
            Text("Nothing recorded here yet. Start a focus block and it will show up.", color = cs.onSurfaceVariant, fontSize = 13.sp, modifier = Modifier.padding(top = 4.dp))
        }
    }
    val body: @Composable ColumnScope.() -> Unit = {
        Spacer(Modifier.height(16.dp))
        FocusChart(values, labelFor, todayIdx, sel) { sel = it }
        Text(detail, color = cs.onSurfaceVariant, fontSize = 13.sp, modifier = Modifier.padding(top = 10.dp, bottom = 16.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            cards.forEach { (l, v) -> StatCard(l, v, Modifier.weight(1f)) }
        }
        Spacer(Modifier.height(12.dp))
    }

    BoxWithConstraints(Modifier.fillMaxSize()) {
        if (maxWidth > maxHeight) {
            Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(32.dp)) {
                Column(Modifier.weight(1f).verticalScroll(rememberScrollState()), content = head)
                Column(Modifier.weight(1f).verticalScroll(rememberScrollState()), content = body)
            }
        } else {
            Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) { head(); body() }
        }
    }
}

@Composable
fun BigDuration(ms: Long) {
    val cs = MaterialTheme.colorScheme
    val m = ((ms + 30_000L) / 60_000L).toInt()
    Row(verticalAlignment = Alignment.Bottom, modifier = Modifier.padding(top = 8.dp)) {
        if (m >= 60) {
            Text("${m / 60}", fontSize = 56.sp, fontWeight = FontWeight.SemiBold)
            Text("h", fontSize = 20.sp, color = cs.onSurfaceVariant, modifier = Modifier.padding(start = 4.dp, end = 12.dp, bottom = 10.dp))
        }
        Text(if (m >= 60) "%02d".format(m % 60) else "$m", fontSize = 56.sp, fontWeight = FontWeight.SemiBold)
        Text("m", fontSize = 20.sp, color = cs.onSurfaceVariant, modifier = Modifier.padding(start = 4.dp, bottom = 10.dp))
    }
}

@Composable
fun StatCard(label: String, value: String, modifier: Modifier = Modifier) {
    val cs = MaterialTheme.colorScheme
    Column(modifier.clip(RoundedCornerShape(18.dp)).background(cs.surfaceContainer).padding(14.dp)) {
        Text(label, fontSize = 12.sp, color = cs.onSurfaceVariant)
        Text(value, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 2.dp))
    }
}

/** Bars with a faint track behind each; tap a bar to read its value. [today] is highlighted in the labels. */
@Composable
fun FocusChart(values: List<Long>, label: (Int) -> String, today: Int, sel: Int, onSel: (Int) -> Unit) {
    val cs = MaterialTheme.colorScheme
    val n = values.size
    val maxV = max(1L, values.maxOrNull() ?: 1L)
    val gap = if (n > 24) 2.dp else if (n > 7) 4.dp else 10.dp
    Row(Modifier.fillMaxWidth().height(170.dp), horizontalArrangement = Arrangement.spacedBy(gap), verticalAlignment = Alignment.Bottom) {
        values.forEachIndexed { i, v ->
            val target = if (v <= 0L) 0f else max(.05f, v.toFloat() / maxV)
            val frac by animateFloatAsState(target, tween(450), label = "bar$i")
            Box(
                Modifier.weight(1f).fillMaxHeight().clip(RoundedCornerShape(50)).background(cs.primary.copy(alpha = .10f))
                    .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { onSel(if (sel == i) -1 else i) },
                contentAlignment = Alignment.BottomCenter,
            ) {
                Box(
                    Modifier.fillMaxWidth().fillMaxHeight(frac).clip(RoundedCornerShape(50))
                        .background(if (i == sel) cs.primary else cs.primary.copy(alpha = .55f))
                )
            }
        }
    }
    Row(Modifier.fillMaxWidth().padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(gap)) {
        for (i in 0 until n) {
            Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
                val t = label(i)
                if (t.isNotEmpty()) {
                    Text(
                        t, fontSize = 10.sp, softWrap = false, maxLines = 1, overflow = TextOverflow.Visible,
                        color = if (i == today) cs.primary else cs.onSurfaceVariant,
                        fontWeight = if (i == today) FontWeight.SemiBold else FontWeight.Normal,
                        modifier = Modifier.wrapContentWidth(unbounded = true),
                    )
                }
            }
        }
    }
}
