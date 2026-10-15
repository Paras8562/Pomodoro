package com.example.pomodoro

import android.Manifest
import android.app.Application
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.PowerManager
import androidx.core.content.ContextCompat
import android.content.Context
import android.os.SystemClock
import android.os.VibrationEffect
import android.os.Vibrator
import androidx.compose.runtime.mutableStateOf
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.ceil

/** Fast-changing timer state (updates ~10x per second). */
data class Run(
    val plan: List<Block> = emptyList(), val idx: Int = 0, val remaining: Long = 0,
    val running: Boolean = false, val finished: Boolean = false,
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

    // ---------- "Get back to work" nudge when the app is left during a focus block ----------
    private var nudgeJob: Job? = null
    private val nm get() = getApplication<Application>().getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

    fun appLeft() {
        nudgeJob?.cancel()
        // a short delay, so a screen rotation (which stops and restarts the app) never flashes a notification
        nudgeJob = viewModelScope.launch { delay(1500); postNudge() }
    }

    fun appBack() { nudgeJob?.cancel(); nm.cancel(NUDGE_ID) }

    private fun postNudge() {
        val app = getApplication<Application>()
        val r = _run.value
        val b = r.plan.getOrNull(r.idx) ?: return
        if (!r.running || r.finished || b.kind != Kind.FOCUS) return
        val pm = app.getSystemService(Context.POWER_SERVICE) as PowerManager
        if (!pm.isInteractive) return   // the screen turned off; the person didn't walk away
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(app, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) return
        nm.createNotificationChannel(
            NotificationChannel("nudge", "Focus reminders", NotificationManager.IMPORTANCE_HIGH)
                .apply { description = "Reminds you to come back while a focus timer is running" }
        )
        val left = ceil(r.remaining / 1000.0).toLong()
        val open = PendingIntent.getActivity(
            app, 0,
            Intent(app, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val n = Notification.Builder(app, "nudge")
            .setSmallIcon(R.drawable.ic_stat_timer)
            .setContentTitle("Get back to work")
            .setContentText(String.format("Your focus timer is still running – %d:%02d left.", left / 60, left % 60))
            .setContentIntent(open).setAutoCancel(true).setCategory(Notification.CATEGORY_REMINDER)
            .build()
        nm.notify(NUDGE_ID, n)
    }

    private var job: Job? = null
    private var endAt = 0L
    private var lastSec = -1L
    private var tock = false
    private fun now() = SystemClock.elapsedRealtime()

    // ---------- persistence ----------
    private fun load() = Ui(
        inRun = false, step = 1,
        opts = Opts(
            total = sp.getInt("total", 120),
            brk = if (sp.contains("brk")) sp.getBoolean("brk", false) else null,
            f = sp.getInt("f", 25), s = sp.getInt("s", 5), l = sp.getInt("l", 15),
            useLong = sp.getBoolean("useLong", true), every = sp.getInt("every", 4),
        ),
        look = Look(sp.getString("mode", "system") ?: "system", sp.getString("pal", "you") ?: "you"),
        snd = Snd(sp.getString("tick", "soft") ?: "soft", sp.getBoolean("cues", true), sp.getInt("vol", 70)),
    )

    private fun saveOpts(o: Opts) {
        val e = sp.edit().putInt("total", o.total).putInt("f", o.f).putInt("s", o.s).putInt("l", o.l)
            .putBoolean("useLong", o.useLong).putInt("every", o.every)
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
        _run.value = Run(plan, 0, plan[0].ms, running = false, finished = false)
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
        lastSec = ceil(r.remaining / 1000.0).toLong()
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
        var idx = r.idx
        var left = endAt - now()
        var moved = false
        while (left <= 0) { // roll into the next block without drift
            if (idx >= r.plan.lastIndex) { complete(); return }
            idx++; endAt += r.plan[idx].ms; left = endAt - now(); moved = true
        }
        if (moved) cue(keyOf(r.plan[idx].kind))
        val sc = ceil(left / 1000.0).toLong()
        if (!moved && sc != lastSec && r.plan[idx].kind == Kind.FOCUS) playTick(_ui.value.snd.tick)
        lastSec = sc
        _run.update { it.copy(idx = idx, remaining = left) }
    }

    private fun complete() {
        job?.cancel()
        cue("done")
        _run.update { it.copy(running = false, finished = true, remaining = 0) }
    }

    fun reset() {
        val r = _run.value
        if (r.plan.isEmpty()) return
        val ms = r.plan[r.idx].ms
        if (r.running) { endAt = now() + ms; lastSec = ceil(ms / 1000.0).toLong() }
        _run.update { it.copy(remaining = ms) }
    }

    fun skip() {
        val r = _run.value
        if (r.plan.isEmpty() || r.finished) return
        if (r.idx >= r.plan.lastIndex) { complete(); return }
        val i = r.idx + 1
        val ms = r.plan[i].ms
        if (r.running) { endAt = now() + ms; lastSec = ceil(ms / 1000.0).toLong() }
        cue(keyOf(r.plan[i].kind))
        _run.update { it.copy(idx = i, remaining = ms) }
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

    private companion object { const val NUDGE_ID = 1 }
}
