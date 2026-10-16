package com.example.pomodoro

import android.Manifest
import android.app.Application
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.PowerManager
import android.os.SystemClock
import android.os.VibrationEffect
import android.os.Vibrator
import android.widget.Toast
import androidx.compose.runtime.mutableStateOf
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToLong

/** Fast-changing timer state (updates ~10x per second). */
data class Run(
    val plan: List<Block> = emptyList(), val idx: Int = 0, val remaining: Long = 0,
    val running: Boolean = false, val finished: Boolean = false,
    /** Flow mode on for this session. */
    val flow: Boolean = false,
    /** Flow mode: the planned focus time is over and the timer is counting up ([overMs]) until a break is taken. */
    val overtime: Boolean = false, val overMs: Long = 0,
)

/** Slow-changing screen / settings state. */
data class Ui(val inRun: Boolean, val step: Int, val opts: Opts, val look: Look, val snd: Snd)

class SessionViewModel(app: Application) : AndroidViewModel(app) {
    private val sp = app.getSharedPreferences("pomo", Context.MODE_PRIVATE)
    private val engine = SoundEngine().also { it.warm() }

    private val _run = MutableStateFlow(Run())
    val run: StateFlow<Run> = _run.asStateFlow()
    val running: StateFlow<Boolean> =
        run.map { it.running }.distinctUntilChanged().stateIn(viewModelScope, SharingStarted.Eagerly, false)

    private val _ui = MutableStateFlow(load())
    val ui: StateFlow<Ui> = _ui.asStateFlow()

    private var job: Job? = null
    private var endAt = 0L
    private var overStart = 0L
    private var lastSec = -1L
    private var tock = false
    private fun now() = SystemClock.elapsedRealtime()

    // time spent away from the app during focus that still has to be taken off a break
    private var pendingCutMs = 0L
    private var awayStart = 0L
    private var awayStop = 0L
    private var awayCap = 0L

    // when the screen turns off, "away" stops counting (the phone is just lying there)
    private val screenOff = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) { if (awayStart > 0L && awayStop == 0L) awayStop = now() }
    }
    init {
        ContextCompat.registerReceiver(app, screenOff, IntentFilter(Intent.ACTION_SCREEN_OFF), ContextCompat.RECEIVER_NOT_EXPORTED)
    }
    override fun onCleared() {
        try { getApplication<Application>().unregisterReceiver(screenOff) } catch (_: Exception) {}
        super.onCleared()
    }

    // ---------- swipe hint: shown the first 3 times the timer screen opens ----------
    val hintVisible = mutableStateOf(false)
    private var hintCount = sp.getInt("hintCount", 0)
    fun maybeShowHint() {
        if (hintCount < 3 && !hintVisible.value) {
            hintVisible.value = true
            hintCount++
            sp.edit().putInt("hintCount", hintCount).apply()
        }
    }
    fun hideHint() { hintVisible.value = false }

    // ---------- persistence ----------
    private fun load() = Ui(
        inRun = false, step = 1,
        opts = Opts(
            total = sp.getInt("total", 120),
            brk = if (sp.contains("brk")) sp.getBoolean("brk", false) else null,
            f = sp.getInt("f", 25), s = sp.getInt("s", 5), l = sp.getInt("l", 15),
            useLong = sp.getBoolean("useLong", true), every = sp.getInt("every", 4),
            flow = sp.getBoolean("flow", false),
        ),
        look = Look(sp.getString("mode", "system") ?: "system", sp.getString("pal", "you") ?: "you"),
        snd = Snd(sp.getString("tick", "soft") ?: "soft", sp.getBoolean("cues", true), sp.getInt("vol", 70)),
    )

    private fun saveOpts(o: Opts) {
        val e = sp.edit().putInt("total", o.total).putInt("f", o.f).putInt("s", o.s).putInt("l", o.l)
            .putBoolean("useLong", o.useLong).putInt("every", o.every).putBoolean("flow", o.flow)
        o.brk?.let { e.putBoolean("brk", it) }
        e.apply()
    }

    // ---------- settings ----------
    fun setOpts(f: (Opts) -> Opts) {
        val o = f(_ui.value.opts)
        _ui.update { it.copy(opts = o) }; saveOpts(o)
    }
    fun setLook(l: Look) {
        _ui.update { it.copy(look = l) }
        sp.edit().putString("mode", l.mode).putString("pal", l.pal).apply()
    }
    fun setSnd(s: Snd) {
        _ui.update { it.copy(snd = s) }
        sp.edit().putString("tick", s.tick).putBoolean("cues", s.cues).putInt("vol", s.vol).apply()
    }

    // ---------- setup flow ----------
    fun go(n: Int) = _ui.update { it.copy(step = n) }
    fun next() {
        val u = _ui.value
        when {
            u.step == 1 -> go(2)
            u.step == 2 && u.opts.brk == true -> go(3)
            else -> create()
        }
    }
    fun back() { if (_ui.value.step > 1) go(_ui.value.step - 1) else _ui.update { it.copy(inRun = true) } }
    fun openSetup() { stop(); _ui.update { it.copy(inRun = false, step = 1) } }

    private fun create() {
        stop()
        val o = _ui.value.opts.copy(brk = _ui.value.opts.brk == true)
        val plan = buildPlan(o)
        pendingCutMs = 0L; awayStart = 0L; awayStop = 0L
        _run.value = Run(plan, 0, plan[0].ms, running = false, finished = false, flow = o.flow && plan.size > 1)
        _ui.update { it.copy(inRun = true, opts = o) }
        saveOpts(o)
    }

    // ---------- timer ----------
    fun toggle() {
        val r = _run.value
        if (r.finished || r.plan.isEmpty()) return
        if (r.running) stop() else start()
    }

    private fun start() {
        val r = _run.value
        endAt = now() + r.remaining
        if (r.overtime) overStart = now() - r.overMs
        lastSec = if (r.overtime) r.overMs / 1000 else ceil(r.remaining / 1000.0).toLong()
        _run.update { it.copy(running = true) }
        job?.cancel()
        job = viewModelScope.launch { while (isActive) { tick(); delay(100) } }
    }

    private fun stop() {
        job?.cancel()
        _run.update { it.copy(running = false) }
    }

    private fun tick() {
        val r = _run.value
        val t = now()

        // flow mode: the planned focus time is over, keep counting up – silently
        if (r.overtime) {
            val over = t - overStart
            val later = r.plan.drop(r.idx + 1).sumOf { it.ms }
            if (over >= later) { complete(); return }          // the whole set time has been used up
            val sc = over / 1000
            if (sc != lastSec) { playTick(_ui.value.snd.tick); lastSec = sc }
            _run.update { it.copy(overMs = over) }
            return
        }

        var plan = r.plan
        var idx = r.idx
        var left = endAt - t
        var moved = false
        while (left <= 0) {                                    // roll into the next block without drift
            if (idx >= plan.lastIndex) { complete(); return }
            if (r.flow && plan[idx].kind == Kind.FOCUS) {
                // flow mode: no break, no sound, no vibration, no notification – just start counting up
                overStart = endAt
                lastSec = (t - endAt) / 1000
                _run.update { it.copy(plan = plan, idx = idx, overtime = true, remaining = 0, overMs = t - endAt) }
                return
            }
            idx++
            plan = applyCut(plan, idx)                         // time spent away comes off this break (if it is one)
            endAt += plan[idx].ms
            left = endAt - t
            moved = true
        }
        if (moved) cue(keyOf(plan[idx].kind))
        val sc = ceil(left / 1000.0).toLong()
        if (!moved && sc != lastSec && plan[idx].kind == Kind.FOCUS) playTick(_ui.value.snd.tick)
        lastSec = sc
        _run.update { it.copy(plan = plan, idx = idx, remaining = left) }
    }

    private fun complete() {
        job?.cancel()
        cue("done")
        _run.update { it.copy(running = false, finished = true, remaining = 0, overtime = false, overMs = 0) }
    }

    fun reset() {
        val r = _run.value
        if (r.plan.isEmpty()) return
        val ms = r.plan[r.idx].ms
        if (r.running) { endAt = now() + ms; lastSec = ceil(ms / 1000.0).toLong() }
        _run.update { it.copy(remaining = ms, overtime = false, overMs = 0) }
    }

    fun skip() {
        val r = _run.value
        if (r.plan.isEmpty() || r.finished) return
        if (r.overtime) { takeBreak(); return }
        if (r.idx >= r.plan.lastIndex) { complete(); return }
        val i = r.idx + 1
        val plan = applyCut(r.plan, i)
        val ms = plan[i].ms
        if (r.running) { endAt = now() + ms; lastSec = ceil(ms / 1000.0).toLong() }
        cue(keyOf(plan[i].kind))
        _run.update { it.copy(plan = plan, idx = i, remaining = ms) }
    }

    /**
     * Flow mode: "Take a break". The break is proportional to how long you actually worked
     * (a 25 min block with a 5 min break, worked for 40 min, earns an 8 min break), and everything after it is
     * re-planned so the session still ends at the set time.
     */
    fun takeBreak() {
        val r = _run.value
        if (!r.overtime || r.finished) return
        val o = _ui.value.opts
        val cur = r.plan[r.idx]
        val over = if (r.running) now() - overStart else r.overMs
        val avail = r.plan.drop(r.idx + 1).sumOf { it.ms } - over     // time left in the whole session
        if (avail <= 0L) { complete(); return }
        val focusDone = r.plan.take(r.idx + 1).count { it.kind == Kind.FOCUS }
        val worked = cur.ms + over
        val long = o.useLong && focusDone % o.every == 0
        val nominal = (if (long) o.l else o.s) * 60_000L
        val proportional = (worked.toDouble() / (o.f * 60_000.0) * nominal / 60_000.0).roundToLong() * 60_000L
        val future = replanFuture(o, avail, focusDone, max(MIN_BREAK_MS, proportional))
        val i = r.idx + 1
        val plan = applyCut(r.plan.take(r.idx) + cur.copy(ms = worked) + future, i)
        val ms = plan[i].ms
        if (r.running) { endAt = now() + ms; lastSec = ceil(ms / 1000.0).toLong() }
        cue(keyOf(plan[i].kind))
        _run.update { it.copy(plan = plan, idx = i, remaining = ms, overtime = false, overMs = 0) }
    }

    /**
     * When a break starts, take the time spent away from the app off it (never below [MIN_BREAK_MS]) and give the same
     * time to the focus block that follows, so the session still ends exactly at the set time.
     */
    private fun applyCut(plan: List<Block>, i: Int): List<Block> {
        val b = plan[i]
        if (b.kind == Kind.FOCUS || pendingCutMs <= 0L) return plan
        if (plan.getOrNull(i + 1)?.kind != Kind.FOCUS) return plan
        val c = min(pendingCutMs, b.ms - MIN_BREAK_MS)
        if (c <= 0L) return plan
        pendingCutMs -= c
        val p = plan.toMutableList()
        p[i] = b.copy(ms = b.ms - c)
        p[i + 1] = p[i + 1].copy(ms = p[i + 1].ms + c)
        return p
    }

    // ---------- sound & haptics ----------
    private fun keyOf(k: Kind) = when (k) { Kind.FOCUS -> "focus"; Kind.SHORT -> "short"; Kind.LONG -> "long" }

    private fun playTick(kind: String) {
        val v = _ui.value.snd.vol
        when (kind) {
            "off" -> {}
            "soft" -> engine.play("soft", v)
            else -> { engine.play(if (tock) "tock" else "tick", v); tock = !tock }
        }
    }

    private fun cue(key: String, preview: Boolean = false) {
        val s = _ui.value.snd
        if (!preview && !s.cues) return
        if (!preview) vibrate(key)
        engine.play(key, s.vol)
    }

    fun preview(key: String) = cue(key, true)
    fun previewTick(kind: String) = playTick(kind)

    private fun vibrate(key: String) {
        try {
            @Suppress("DEPRECATION")
            val v = getApplication<Application>().getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator ?: return
            val pattern = when (key) {
                "done" -> longArrayOf(0, 120, 60, 120, 60, 200)
                "focus" -> longArrayOf(0, 80)
                else -> longArrayOf(0, 200)
            }
            v.vibrate(VibrationEffect.createWaveform(pattern, -1))
        } catch (_: Exception) {}
    }

    // ---------- leaving / returning to the app ----------
    private var nudgeJob: Job? = null
    private val nm get() = getApplication<Application>().getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

    fun appLeft() {
        val app = getApplication<Application>()
        val r = _run.value
        val b = r.plan.getOrNull(r.idx)
        val pm = app.getSystemService(Context.POWER_SERVICE) as PowerManager
        if (r.running && !r.finished && b?.kind == Kind.FOCUS && pm.isInteractive) {
            awayStart = now(); awayStop = 0L
            // only the time still left in this focus block counts (flow mode: all of it, the focus carries on)
            awayCap = if (r.flow) Long.MAX_VALUE else r.remaining
        }
        nudgeJob?.cancel()
        // a short delay, so a screen rotation (which stops and restarts the app) never flashes a notification
        nudgeJob = viewModelScope.launch { delay(1500); postNudge() }
    }

    fun appBack() {
        nudgeJob?.cancel(); nm.cancel(NUDGE_ID)
        if (awayStart <= 0L) return
        val r = _run.value
        val end = if (awayStop > 0L) awayStop else now()
        val away = min(max(0L, end - awayStart), awayCap)
        awayStart = 0L; awayStop = 0L
        val breaksAhead = r.plan.drop(r.idx + 1).any { it.kind != Kind.FOCUS } || r.flow
        if (away >= 5_000L && breaksAhead && !r.finished) {   // ignore short blips (a dialog, a quick peek)
            pendingCutMs += away
            val s = away / 1000
            val text = if (s >= 60) String.format("%dm %02ds", s / 60, s % 60) else "${s}s"
            Toast.makeText(getApplication<Application>(), "You were away for $text. That comes off your next break.", Toast.LENGTH_LONG).show()
        }
    }

    private fun postNudge() {
        val app = getApplication<Application>()
        val r = _run.value
        val b = r.plan.getOrNull(r.idx) ?: return
        if (!r.running || r.finished || b.kind != Kind.FOCUS) return
        val pm = app.getSystemService(Context.POWER_SERVICE) as PowerManager
        if (!pm.isInteractive) return   // the screen turned off; the person didn't walk away
        if (android.os.Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(app, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) return
        nm.createNotificationChannel(
            NotificationChannel("nudge", "Focus reminders", NotificationManager.IMPORTANCE_HIGH)
                .apply { description = "Reminds you to come back while a focus timer is running" }
        )
        val left = ceil(r.remaining / 1000.0).toLong()
        val text = if (r.overtime) "Your focus timer is still running."
        else String.format("Your focus timer is still running – %d:%02d left.", left / 60, left % 60)
        val open = PendingIntent.getActivity(
            app, 0,
            Intent(app, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val n = Notification.Builder(app, "nudge")
            .setSmallIcon(R.drawable.ic_stat_timer)
            .setContentTitle("Get back to work")
            .setContentText(text)
            .setContentIntent(open).setAutoCancel(true).setCategory(Notification.CATEGORY_REMINDER)
            .build()
        nm.notify(NUDGE_ID, n)
    }

    private companion object { const val NUDGE_ID = 1 }
}
