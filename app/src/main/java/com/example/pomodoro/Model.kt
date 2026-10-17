package com.example.pomodoro

import kotlin.math.min

enum class Kind { FOCUS, SHORT, LONG }

/** One stretch of the session. Length is kept in milliseconds so the plan can be re-planned to the second. */
data class Block(val kind: Kind, val ms: Long) {
    val mins: Int get() = (ms / 60_000L).toInt()
}

private fun blk(k: Kind, mins: Int) = Block(k, mins * 60_000L)

data class Opts(
    val total: Int = 120, val brk: Boolean? = null,
    val f: Int = 25, val s: Int = 5, val l: Int = 15,
    val useLong: Boolean = true, val every: Int = 4,
    val flow: Boolean = false,
)
/** A saved, labelled setup. */
data class Preset(val id: Long, val name: String, val opts: Opts)
data class Look(val mode: String = "system", val pal: String = "you")
data class Snd(val tick: String = "soft", val cues: Boolean = true, val vol: Int = 70)

const val MIN_FOCUS_MS = 5 * 60_000L   // a focus block (or the tail of one) shorter than this isn't worth starting
const val MIN_BREAK_MS = 60_000L       // time lost while away can shorten a break, but never below this

/** Fills the work time with focus blocks and breaks; a long break follows every Nth focus block. */
fun buildPlan(o: Opts, forceBreaks: Boolean = false): List<Block> {
    if (o.brk != true && !forceBreaks) return listOf(blk(Kind.FOCUS, o.total))
    val p = mutableListOf<Block>()
    var left = o.total
    var n = 0
    while (left > 0) {
        val f = min(o.f, left)
        p += blk(Kind.FOCUS, f); left -= f; n++
        if (left <= 0) break
        val long = o.useLong && n % o.every == 0
        val b = if (long) o.l else o.s
        if (left < b + 5) { // no room for a break plus useful focus: extend the last focus block
            p[p.lastIndex] = p.last().copy(ms = p.last().ms + left * 60_000L); break
        }
        p += blk(if (long) Kind.LONG else Kind.SHORT, b); left -= b
    }
    return p
}

/**
 * Flow mode: the person worked past the planned end and now takes a break. Re-plans the rest of the session so it
 * still ends on time: a break first (of [firstBreakMs]), then focus / break blocks until [availMs] is used up.
 * [focusDone] = focus blocks finished so far (decides whether the break is short or long).
 */
fun replanFuture(o: Opts, availMs: Long, focusDone: Int, firstBreakMs: Long): List<Block> {
    val out = mutableListOf<Block>()
    var left = availMs
    var n = focusDone
    if (left <= 0L) return out
    val kind0 = if (o.useLong && n % o.every == 0) Kind.LONG else Kind.SHORT
    if (left < firstBreakMs + MIN_FOCUS_MS) { out += Block(kind0, left); return out }   // the rest of the session is the break
    out += Block(kind0, firstBreakMs); left -= firstBreakMs
    while (left > 0L) {
        val f = min(o.f * 60_000L, left)
        out += Block(Kind.FOCUS, f); left -= f; n++
        if (left <= 0L) break
        val long = o.useLong && n % o.every == 0
        val b = (if (long) o.l else o.s) * 60_000L
        if (left < b + MIN_FOCUS_MS) { out[out.lastIndex] = out.last().copy(ms = out.last().ms + left); break }
        out += Block(if (long) Kind.LONG else Kind.SHORT, b); left -= b
    }
    return out
}

fun fmt(m: Int): String {
    val h = m / 60; val r = m % 60
    return if (h > 0) (if (r > 0) String.format("%dh %02dm", h, r) else "${h}h") else "${r}m"
}

/** order: primary, onPrimary, secondary, onSecondary, surface, onSurface, onVariant, outline, container, gradientTop, gradientBottom */
class Pal(val id: String, val name: String, val l: List<String>, val d: List<String>)

val PALS = listOf(
    Pal("tomato", "Tomato",
        listOf("#B5322A","#FFFFFF","#FFE1DC","#2C1512","#FFF8F7","#231918","#534341","#857370","#F8EAE8","#7ED8CB","#FFB4A9"),
        listOf("#FFB4A9","#690006","#5C3F3B","#FFDAD5","#1A1110","#F1DFDC","#D8C2BE","#A08C89","#271D1C","#1F7A72","#A33A31")),
    Pal("ocean", "Ocean",
        listOf("#0B5FA5","#FFFFFF","#D3E4FF","#001C38","#F8F9FF","#191C20","#43474E","#73777F","#ECEEF4","#8EC8FF","#FFC9A8"),
        listOf("#A4C8FF","#00315C","#3B4858","#D7E3F8","#101418","#E0E2E8","#C3C7CF","#8D9199","#1D2024","#1F6FB5","#B0573A")),
    Pal("forest", "Forest",
        listOf("#2E6B3A","#FFFFFF","#D4EBCF","#0A2010","#F6FBF3","#181D18","#414941","#717971","#EAF0E7","#9BDB9D","#F2D98C"),
        listOf("#9BD59F","#003913","#3B4B3C","#D2E8D0","#101510","#E0E4DC","#C1C9BE","#8B9389","#1C211B","#2C7A45","#8B7A2B")),
    Pal("lavender", "Lavender",
        listOf("#6750A4","#FFFFFF","#E8DEF8","#1D192B","#FEF7FF","#1D1B20","#49454F","#7A757F","#F3EDF7","#CBB8FF","#FFB8D9"),
        listOf("#D0BCFF","#381E72","#4A4458","#E8DEF8","#141218","#E6E0E9","#CAC4D0","#938F99","#211F26","#5B3FB0","#A8447A")),
    Pal("amber", "Amber",
        listOf("#8A5100","#FFFFFF","#FFDCBE","#2C1600","#FFF8F4","#201A17","#51443A","#84746A","#F8EDE6","#FFD27D","#FF9EA8"),
        listOf("#FFB86C","#4A2800","#5A4330","#FFDCBE","#181210","#ECE0DA","#D5C3B8","#9D8D82","#251E1A","#A8680F","#A33A55")),
    Pal("slate", "Slate",
        listOf("#455A64","#FFFFFF","#DCE4E9","#151D21","#F7F9FA","#191C1D","#40484C","#70787D","#EBEEF0","#A9C4D6","#E8C9B6"),
        listOf("#A8C8DC","#0F3345","#3A4A54","#D2E4F0","#0F1416","#DEE3E6","#BFC8CD","#899296","#1B2124","#2F5F7E","#7A5A48")),
)
