package com.ascend.ui

import android.animation.ValueAnimator
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.drawable.BitmapDrawable
import android.media.MediaMetadata
import android.media.session.PlaybackState
import android.os.BatteryManager
import android.text.TextUtils
import android.text.format.DateFormat
import android.view.Gravity
import android.view.MotionEvent
import android.view.VelocityTracker
import android.view.View
import android.view.ViewConfiguration
import android.view.animation.DecelerateInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.abs

/** What the home pages ask the launcher to do. */
interface HomeHost {
    fun launch(e: AppEntry, v: View)
    fun appMenu(e: AppEntry, v: View)
    fun openDrawer(letter: String? = null, search: Boolean = false)
    fun openShade()
    fun editQuests()
    fun editName()
    fun editWeather()
    fun askSteps()
    fun askCalendar()
    fun openCalendar()
    fun askNotificationAccess()
}

/** Everything the pages display, gathered once per refresh. */
class HomeState(
    val t: AscendTheme,
    val apps: List<AppEntry>,
    val favorites: List<AppEntry>,
    val battery: Int,
    val charging: Boolean,
    val steps: Int,
    val notices: Int,
    val listening: Boolean
)

fun homeState(ctx: Context, apps: List<AppEntry>): HomeState {
    val b = ctx.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
    val level = b?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
    val scale = (b?.getIntExtra(BatteryManager.EXTRA_SCALE, 100) ?: 100).coerceAtLeast(1)
    val status = b?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
    val favs = Prefs.dock(ctx).mapNotNull { id -> apps.firstOrNull { it.id == id } }
    return HomeState(
        Prefs.theme(ctx), apps, favs,
        if (level >= 0) level * 100 / scale else -1,
        status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL,
        Steps.today(ctx), NotifyListener.count(), NotifyListener.instance != null
    )
}

private fun clockText(ctx: Context): String =
    SimpleDateFormat(if (DateFormat.is24HourFormat(ctx)) "HH:mm" else "h:mm", Locale.GERMAN).format(Date())

private fun Context.panel(t: AscendTheme, radius: Float = 14f): LinearLayout = LinearLayout(this).apply {
    orientation = LinearLayout.VERTICAL
    background = HudDrawable(t, resources.displayMetrics.density, radiusDp = radius)
}

private fun TextView.glow(t: AscendTheme, r: Float = 14f): TextView = apply {
    if (!t.light) setShadowLayer(context.dpf(r), 0f, 0f, t.accent.withAlpha(200))
}

private fun Context.diamond(t: AscendTheme, sizeDp: Float = 6f): View = object : View(this) {
    val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = t.accent }
    override fun onDraw(c: Canvas) {
        val h = width / 2f
        c.save(); c.rotate(45f, h, h); val s = h * 0.7f; c.drawRect(h - s, h - s, h + s, h + s, p); c.restore()
    }
}.also { it.minimumWidth = dp(sizeDp) }

// ======================================================================== small views

/** Circular progress ring with a value in the middle. */
class RingView(ctx: Context, private val t: AscendTheme) : View(ctx) {
    var value = 0f
        set(v) { field = v.coerceIn(0f, 1f); invalidate() }
    var label = ""
        set(v) { field = v; invalidate() }
    private val track = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND }
    private val txt = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER }
    private val r = RectF()

    init { if (android.os.Build.VERSION.SDK_INT < 28) setLayerType(LAYER_TYPE_SOFTWARE, null) }

    override fun onDraw(c: Canvas) {
        val sw = context.dpf(6f)
        val size = minOf(width, height) - sw * 2 - context.dpf(6f)
        r.set((width - size) / 2f, (height - size) / 2f, (width + size) / 2f, (height + size) / 2f)
        track.strokeWidth = sw
        track.color = t.surfaceHi
        track.clearShadowLayer()
        c.drawArc(r, 0f, 360f, false, track)
        track.color = t.accent
        if (!t.light) track.setShadowLayer(context.dpf(5f), 0f, 0f, t.accent)
        c.drawArc(r, -90f, 360f * value, false, track)
        txt.typeface = Fonts.medium
        txt.textSize = context.dpf(18f)
        txt.color = t.text
        val fm = txt.fontMetrics
        c.drawText(label, width / 2f, height / 2f - (fm.ascent + fm.descent) / 2f, txt)
    }
}

/** Rounded check box drawn in the theme colours. */
class CheckView(ctx: Context, private val t: AscendTheme) : FrameLayout(ctx) {
    private val tick = GlyphView(ctx, Glyph.CHECK, if (t.light) android.graphics.Color.WHITE else t.bg)
    var checked = false
        set(v) {
            field = v
            background = roundRect(if (v) t.accent else 0, context.dpf(5f), t.accent, context.dp(1.5f))
            tick.visibility = if (v) VISIBLE else INVISIBLE
        }

    init {
        addView(tick, LayoutParams(MATCH, MATCH).apply { setMargins(dp(2), dp(2), dp(2), dp(2)) })
        checked = false
    }
}

/** A–Z strip; touching a letter opens the app list at that letter. */
class LetterBar(ctx: Context, private val t: AscendTheme, private val onLetter: (String) -> Unit) : View(ctx) {
    private val letters = "ABCDEFGHIJKLMNOPQRSTUVWXYZ".map { it.toString() }
    private val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER }
    private var hot = -1

    override fun onDraw(c: Canvas) {
        val step = height / letters.size.toFloat()
        p.typeface = Fonts.mono
        letters.forEachIndexed { i, l ->
            val hit = i == hot
            p.textSize = context.dpf(if (hit) 13f else 8.5f)
            p.color = if (hit) t.accent else t.textDim
            if (hit && !t.light) p.setShadowLayer(context.dpf(6f), 0f, 0f, t.accent) else p.clearShadowLayer()
            c.drawText(l, width / 2f, step * i + step / 2f + p.textSize / 3f, p)
        }
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        val i = (e.y / (height / letters.size.toFloat())).toInt().coerceIn(0, letters.size - 1)
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE -> {
                parent?.requestDisallowInterceptTouchEvent(true)
                if (i != hot) { hot = i; performHapticFeedback(android.view.HapticFeedbackConstants.CLOCK_TICK); invalidate() }
            }
            MotionEvent.ACTION_UP -> { hot = -1; invalidate(); onLetter(letters[i]) }
            MotionEvent.ACTION_CANCEL -> { hot = -1; invalidate() }
        }
        return true
    }
}

/** Horizontal pages with drag, fling and snap. Vertical gestures pass through to the launcher. */
class Pager(ctx: Context, private val onPage: (Int) -> Unit) : FrameLayout(ctx) {
    private val slop = ViewConfiguration.get(ctx).scaledTouchSlop
    private var downX = 0f
    private var downY = 0f
    private var dragging = false
    private var offset = 0f
    private var vt: VelocityTracker? = null
    private var anim: ValueAnimator? = null
    var current = 1
        private set

    fun pageCount() = childCount

    fun show(i: Int, animate: Boolean) {
        val target = i.coerceIn(0, childCount - 1)
        val from = offset + (current - target) * width
        current = target
        anim?.cancel()
        if (!animate || width == 0) { offset = 0f; place(); onPage(current); return }
        offset = from
        anim = ValueAnimator.ofFloat(from, 0f).apply {
            duration = 280
            interpolator = DecelerateInterpolator(1.6f)
            addUpdateListener { offset = it.animatedValue as Float; place() }
            start()
        }
        onPage(current)
    }

    private fun place() {
        for (i in 0 until childCount) {
            val c = getChildAt(i)
            val x = (i - current) * width + offset
            c.translationX = x
            val f = (abs(x) / width.coerceAtLeast(1)).coerceIn(0f, 1f)
            c.alpha = 1f - f * 0.6f
            c.visibility = if (f >= 1f) INVISIBLE else VISIBLE
        }
    }

    override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) { super.onSizeChanged(w, h, ow, oh); place() }

    override fun onInterceptTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = e.x; downY = e.y; dragging = false
                anim?.cancel()
                vt?.recycle(); vt = VelocityTracker.obtain()
                vt?.addMovement(e)
            }
            MotionEvent.ACTION_MOVE -> {
                vt?.addMovement(e)
                val dx = e.x - downX
                val dy = e.y - downY
                if (abs(dx) > slop && abs(dx) > abs(dy) * 1.3f) { dragging = true; downX = e.x - offset }
            }
        }
        return dragging
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        vt?.addMovement(e)
        when (e.actionMasked) {
            MotionEvent.ACTION_MOVE -> {
                var o = e.x - downX
                if ((current == 0 && o > 0) || (current == childCount - 1 && o < 0)) o *= 0.3f
                offset = o
                place()
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                vt?.computeCurrentVelocity(1000)
                val v = vt?.xVelocity ?: 0f
                val target = when {
                    v < -900 || offset < -width / 3f -> current + 1
                    v > 900 || offset > width / 3f -> current - 1
                    else -> current
                }
                dragging = false
                show(target, true)
            }
        }
        return true
    }
}

// ======================================================================== page A: status window

class StatusPage(ctx: Context, private val host: HomeHost) : LinearLayout(ctx) {
    private lateinit var t: AscendTheme
    private val clock = TextView(ctx)
    private val date = TextView(ctx)
    private val wxTop = TextView(ctx)
    private val wxSub = TextView(ctx)
    private val rank = TextView(ctx)
    private val levelText = TextView(ctx)
    private val nameText = TextView(ctx)
    private lateinit var xpBar: SegBar
    private val xpText = TextView(ctx)
    private val streakText = TextView(ctx)
    private val stats = Array(4) { TextView(ctx) }
    private val questBox = LinearLayout(ctx)
    private val questCount = TextView(ctx)
    private val notice = TextView(ctx)

    init { orientation = VERTICAL }

    fun build(theme: AscendTheme) {
        t = theme
        removeAllViews()
        val c = context
        // Clock + weather
        val top = LinearLayout(c).apply { orientation = HORIZONTAL; gravity = Gravity.BOTTOM }
        val left = LinearLayout(c).apply { orientation = VERTICAL }
        style(clock, 66f, t.text, Fonts.thin).apply { letterSpacing = -0.02f }
        left.addView(clock)
        style(date, 10f, t.accent, Fonts.mono, 0.12f)
        left.addView(date, lp(WRAP, WRAP).margins(top = dp(4), left = dp(2)))
        top.addView(left, lp(0, WRAP).weighted())
        val right = LinearLayout(c).apply {
            orientation = VERTICAL; gravity = Gravity.END
            setOnClickListener { host.editWeather() }
        }
        style(wxTop, 10f, t.textDim, Fonts.mono, 0.12f)
        style(wxSub, 13.5f, t.textDim, Fonts.medium)
        right.addView(wxTop)
        right.addView(wxSub, lp(WRAP, WRAP).margins(top = dp(4)))
        top.addView(right, lp(WRAP, WRAP).margins(bottom = dp(4)))
        addView(top, lp(MATCH, WRAP).margins(top = dp(14)))

        // Status window
        val st = c.panel(t).apply { setPadding(dp(18), dp(15), dp(18), dp(16)); setOnClickListener { host.editName() } }
        val h1 = LinearLayout(c).apply { orientation = HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        h1.addView(c.hudLabel("◆ Status", t, 11f), lp(0, WRAP).weighted())
        style(rank, 9f, t.textDim, Fonts.mono, 0.12f)
        h1.addView(rank)
        st.addView(h1)
        val h2 = LinearLayout(c).apply { orientation = HORIZONTAL; gravity = Gravity.BOTTOM }
        style(levelText, 31f, t.accent, Fonts.medium).glow(t)
        style(nameText, 18f, t.text, Fonts.medium)
        nameText.maxLines = 1; nameText.ellipsize = TextUtils.TruncateAt.END
        h2.addView(levelText)
        h2.addView(nameText, lp(0, WRAP).weighted().margins(left = dp(12), bottom = dp(4)))
        st.addView(h2, lp(MATCH, WRAP).margins(top = dp(8)))
        xpBar = SegBar(c, t, 20)
        st.addView(xpBar, lp(MATCH, dp(6)).margins(top = dp(8)))
        val h3 = LinearLayout(c).apply { orientation = HORIZONTAL }
        style(xpText, 12.5f, t.textDim, Fonts.medium)
        style(streakText, 12.5f, t.textDim, Fonts.medium)
        h3.addView(xpText, lp(0, WRAP).weighted())
        h3.addView(streakText)
        st.addView(h3, lp(MATCH, WRAP).margins(top = dp(5)))
        val grid = LinearLayout(c).apply { orientation = HORIZONTAL }
        listOf("STR", "INT", "VIT", "AGI").forEachIndexed { i, k ->
            val cell = LinearLayout(c).apply {
                orientation = VERTICAL; gravity = Gravity.CENTER
                background = roundRect(0, c.dpf(8f), t.accent.withAlpha(64), dp(1))
                setPadding(0, dp(6), 0, dp(6))
            }
            cell.addView(c.text(k, 8f, t.textDim, Fonts.mono, 0.12f))
            style(stats[i], 16.5f, t.text, Fonts.medium)
            cell.addView(stats[i], lp(WRAP, WRAP).margins(top = dp(2)))
            grid.addView(cell, lp(0, WRAP).weighted().margins(left = if (i > 0) dp(6) else 0))
        }
        st.addView(grid, lp(MATCH, WRAP).margins(top = dp(12)))
        addView(st, lp(MATCH, WRAP).margins(top = dp(18)))

        // Daily quests
        val qp = c.panel(t).apply { setPadding(dp(18), dp(15), dp(18), dp(12)) }
        val qh = LinearLayout(c).apply { orientation = HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        qh.addView(c.hudLabel("◆ Tägliche Quests", t, 11f), lp(0, WRAP).weighted())
        style(questCount, 9f, t.textDim, Fonts.mono, 0.12f)
        qh.addView(questCount)
        qh.addView(GlyphView(c, Glyph.EDIT, t.textDim).apply {
            setPadding(dp(6), dp(6), dp(6), dp(6)); setOnClickListener { host.editQuests() }
        }, lp(dp(30), dp(30)).margins(left = dp(4)))
        qp.addView(qh)
        questBox.orientation = VERTICAL
        qp.addView(questBox, lp(MATCH, WRAP))
        addView(qp, lp(MATCH, WRAP).margins(top = dp(12)))

        // Notification line
        val nl = LinearLayout(c).apply {
            orientation = HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(4), dp(12), dp(4), dp(6))
            setOnClickListener { host.openShade() }
        }
        nl.addView(c.diamond(t), lp(dp(7), dp(7)))
        style(notice, 13.5f, t.textDim, Fonts.medium)
        nl.addView(notice, lp(WRAP, WRAP).margins(left = dp(9)))
        addView(nl, lp(MATCH, WRAP))
    }

    fun update(s: HomeState) {
        val c = context
        clock.text = clockText(c)
        date.text = SimpleDateFormat("EEEE · d MMMM", Locale.GERMAN).format(Date()).uppercase()
        val w = Weather.cached(c)
        wxTop.text = if (w != null) "${w.tempC}° · ${w.place}".uppercase() else Weather.city(c).uppercase()
        wxSub.text = if (w != null) Weather.describe(w.code) else "Wetter laden…"

        val lv = Player.level(Player.xp(c))
        rank.text = "RANG ${lv.rank}"
        levelText.text = "LV. ${lv.level}"
        nameText.text = Player.name(c)
        xpBar.value = lv.into.toFloat() / lv.need
        xpText.text = "XP %,d / %,d".format(Locale.GERMAN, lv.into, lv.need)
        val streak = Player.streak(c)
        streakText.text = if (streak > 0) "Serie $streak ${if (streak == 1) "Tag" else "Tage"}" else ""

        val quests = Player.quests(c)
        val steps = s.steps
        val done = quests.count { Player.isDone(c, it, steps.coerceAtLeast(0)) }
        stats[0].text = when { steps < 0 -> "—"; steps >= 1000 -> "%.1fk".format(Locale.GERMAN, steps / 1000f); else -> "$steps" }
        stats[1].text = "$done/${quests.size}"
        stats[2].text = if (s.battery >= 0) "${s.battery}%" else "—"
        stats[3].text = "$streak"
        questCount.text = "$done / ${quests.size}"

        questBox.removeAllViews()
        if (quests.isEmpty()) {
            questBox.addView(c.text("Keine Quests. Tippe auf den Stift, um welche anzulegen.", 13f, t.textDim),
                lp(MATCH, WRAP).margins(top = dp(10)))
        }
        for (q in quests) questBox.addView(questRow(q, steps), lp(MATCH, WRAP).margins(top = dp(8)))

        notice.text = when {
            !s.listening -> "Benachrichtigungen verbinden"
            s.notices == 0 -> "Keine neuen Benachrichtigungen"
            s.notices == 1 -> "1 neue Benachrichtigung"
            else -> "${s.notices} neue Benachrichtigungen"
        }
        notice.setTextColor(if (s.notices > 0) t.text else t.textDim)
        (notice.parent as View).setOnClickListener { if (s.listening) host.openShade() else host.askNotificationAccess() }
    }

    private fun questRow(q: Quest, steps: Int): View {
        val c = context
        val done = Player.isDone(c, q, steps.coerceAtLeast(0))
        val row = LinearLayout(c).apply {
            orientation = HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(3), 0, dp(3))
        }
        row.addView(CheckView(c, t).apply { checked = done }, lp(dp(19), dp(19)))
        val col = LinearLayout(c).apply { orientation = VERTICAL }
        val line = LinearLayout(c).apply { orientation = HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        val title = c.text(q.title, 15.5f, if (done) t.textDim else t.text, Fonts.medium).apply {
            maxLines = 1; ellipsize = TextUtils.TruncateAt.END
            if (done) paintFlags = paintFlags or Paint.STRIKE_THRU_TEXT_FLAG
        }
        line.addView(title, lp(0, WRAP).weighted())
        val valueText = when {
            q.auto && steps < 0 -> "aktivieren"
            q.auto -> "%,d".format(Locale.GERMAN, steps)
            done -> "erledigt"
            else -> "offen"
        }
        line.addView(c.text(valueText, 12.5f, if (done || (q.auto && steps < 0)) t.accent else t.textDim, Fonts.medium),
            lp(WRAP, WRAP).margins(left = dp(8)))
        col.addView(line)
        if (q.auto && steps >= 0 && !done) {
            col.addView(SegBar(c, t, 24).apply { value = steps.toFloat() / q.stepsGoal }, lp(MATCH, dp(3)).margins(top = dp(5)))
        }
        row.addView(col, lp(0, WRAP).weighted().margins(left = dp(11)))
        row.setOnClickListener {
            when {
                q.auto && steps < 0 -> host.askSteps()
                q.auto -> {}
                else -> {
                    Player.toggle(c, q)
                    it.performHapticFeedback(android.view.HapticFeedbackConstants.CONTEXT_CLICK)
                    onQuestToggled?.invoke()
                }
            }
        }
        return row
    }

    var onQuestToggled: (() -> Unit)? = null

    private fun style(v: TextView, sp: Float, color: Int, face: android.graphics.Typeface, spacing: Float = 0f): TextView = v.apply {
        textSize = sp; setTextColor(color); typeface = face; letterSpacing = spacing; includeFontPadding = false
    }
}

// ======================================================================== page B: monarch minimal

class MinimalPage(ctx: Context, private val host: HomeHost) : FrameLayout(ctx) {
    private lateinit var t: AscendTheme
    private val hours = TextView(ctx)
    private val minutes = TextView(ctx)
    private val info = TextView(ctx)
    private val list = LinearLayout(ctx)
    private val status = TextView(ctx)

    fun build(theme: AscendTheme) {
        t = theme
        removeAllViews()
        val c = context
        val clockCol = LinearLayout(c).apply { orientation = LinearLayout.VERTICAL }
        for (v in listOf(hours, minutes)) {
            v.textSize = 100f; v.typeface = Fonts.thin; v.includeFontPadding = false; v.letterSpacing = -0.03f
        }
        hours.setTextColor(t.text)
        minutes.setTextColor(t.accent)
        minutes.glow(t, 26f)
        clockCol.addView(hours)
        clockCol.addView(minutes, lp(WRAP, WRAP).margins(top = -dp(14)))
        clockCol.addView(View(c).apply {
            background = roundRect(t.accent, c.dpf(1f))
            if (!t.light) elevation = 0f
        }, lp(dp(46), dp(2)).margins(top = dp(16), left = dp(4)))
        info.textSize = 10f; info.typeface = Fonts.mono; info.letterSpacing = 0.12f; info.setTextColor(t.textDim)
        clockCol.addView(info, lp(WRAP, WRAP).margins(top = dp(10), left = dp(4)))
        clockCol.setOnClickListener { host.editWeather() }
        addView(clockCol, LayoutParams(WRAP, WRAP).apply { topMargin = dp(30); leftMargin = dp(2) })

        list.orientation = LinearLayout.VERTICAL
        list.gravity = Gravity.END
        addView(list, LayoutParams(WRAP, WRAP, Gravity.END or Gravity.BOTTOM).apply { bottomMargin = dp(58); rightMargin = dp(26) })

        addView(LetterBar(c, t) { host.openDrawer(letter = it) },
            LayoutParams(dp(18), MATCH, Gravity.END).apply { topMargin = dp(110); bottomMargin = dp(70) })

        val bottom = LinearLayout(c).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        status.textSize = 9.5f; status.typeface = Fonts.mono; status.letterSpacing = 0.12f; status.setTextColor(t.textDim)
        bottom.addView(status, lp(0, WRAP).weighted())
        bottom.addView(GlyphView(c, Glyph.SEARCH, t.accent).apply {
            setPadding(dp(10), dp(10), dp(10), dp(10))
            background = roundRect(0, c.dpf(20f), t.accent.withAlpha(150), dp(1))
            setOnClickListener { host.openDrawer(search = true) }
        }, lp(dp(40), dp(40)))
        addView(bottom, LayoutParams(MATCH, WRAP, Gravity.BOTTOM).apply { bottomMargin = dp(6) })
    }

    fun update(s: HomeState) {
        val c = context
        val parts = clockText(c).split(':')
        hours.text = parts.getOrNull(0)?.padStart(2, '0') ?: ""
        minutes.text = parts.getOrNull(1) ?: ""
        val w = Weather.cached(c)
        info.text = (SimpleDateFormat("EE · d MMM", Locale.GERMAN).format(Date()).replace(".", "") +
            (if (w != null) " · ${w.tempC}°" else "")).uppercase()
        val open = Player.quests(c).count { !Player.isDone(c, it, s.steps.coerceAtLeast(0)) }
        status.text = buildList {
            if (s.battery >= 0) add("${s.battery}%")
            if (s.listening) add("${s.notices} neu")
            add(if (open == 0) "Quests erledigt" else "$open Quests")
        }.joinToString(" · ").uppercase()

        list.removeAllViews()
        val favs = s.favorites.take(7)
        if (favs.isEmpty()) {
            list.addView(c.text("Lange auf eine App drücken\n→ Zu Favoriten", 14f, t.textDim, Fonts.medium).apply { gravity = Gravity.END })
        }
        for (e in favs) {
            val tv = c.text(e.label, 27f, t.text, Fonts.medium).apply {
                gravity = Gravity.END
                maxLines = 1
                setPadding(dp(16), dp(3), 0, dp(3))
                setOnTouchListener { v, ev ->
                    when (ev.actionMasked) {
                        MotionEvent.ACTION_DOWN -> { (v as TextView).setTextColor(t.accent); v.glow(t, 16f) }
                        MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> { (v as TextView).setTextColor(t.text); v.setShadowLayer(0f, 0f, 0f, 0) }
                    }
                    false
                }
                setOnClickListener { host.launch(e, it) }
                setOnLongClickListener { host.appMenu(e, it); true }
            }
            list.addView(tv)
        }
    }
}

// ======================================================================== page C: gate grid

class GridPage(ctx: Context, private val host: HomeHost) : LinearLayout(ctx) {
    private lateinit var t: AscendTheme
    private val clock = TextView(ctx)
    private val date = TextView(ctx)
    private lateinit var wxIcon: GlyphView
    private val wxTemp = TextView(ctx)
    private val agenda = LinearLayout(ctx)
    private lateinit var ring: RingView
    private val ringLabel = TextView(ctx)
    private val music = FrameLayout(ctx)
    private val grid = LinearLayout(ctx)

    init { orientation = VERTICAL }

    fun build(theme: AscendTheme) {
        t = theme
        removeAllViews()
        val c = context
        // Clock + weather tile
        val ct = c.panel(t).apply { orientation = HORIZONTAL; gravity = Gravity.BOTTOM; setPadding(dp(18), dp(14), dp(18), dp(14)) }
        val cl = LinearLayout(c).apply { orientation = VERTICAL }
        clock.textSize = 52f; clock.typeface = Fonts.thin; clock.setTextColor(t.text); clock.includeFontPadding = false
        cl.addView(clock)
        date.textSize = 9.5f; date.typeface = Fonts.mono; date.letterSpacing = 0.12f; date.setTextColor(t.textDim)
        cl.addView(date, lp(WRAP, WRAP).margins(top = dp(4)))
        ct.addView(cl, lp(0, WRAP).weighted())
        val wx = LinearLayout(c).apply { orientation = VERTICAL; gravity = Gravity.END; setOnClickListener { host.editWeather() } }
        wxIcon = GlyphView(c, Glyph.CLOUD, t.accent)
        wx.addView(wxIcon, lp(dp(30), dp(30)))
        wxTemp.textSize = 22f; wxTemp.typeface = Fonts.medium; wxTemp.setTextColor(t.text)
        wx.addView(wxTemp)
        ct.addView(wx)
        addView(ct, lp(MATCH, WRAP).margins(top = dp(14)))

        // Agenda + battery
        val row = LinearLayout(c).apply { orientation = HORIZONTAL }
        val ag = c.panel(t).apply { setPadding(dp(16), dp(13), dp(14), dp(12)); setOnClickListener { host.openCalendar() } }
        ag.addView(c.hudLabel("Heute", t, 10.5f))
        agenda.orientation = VERTICAL
        ag.addView(agenda, lp(MATCH, WRAP))
        row.addView(ag, lp(0, MATCH).weighted(1.25f))
        val bt = c.panel(t).apply { gravity = Gravity.CENTER; setPadding(dp(8), dp(10), dp(8), dp(10)) }
        ring = RingView(c, t)
        bt.addView(ring, lp(dp(76), dp(76)))
        ringLabel.textSize = 9f; ringLabel.typeface = Fonts.mono; ringLabel.letterSpacing = 0.12f; ringLabel.setTextColor(t.textDim)
        ringLabel.gravity = Gravity.CENTER
        bt.addView(ringLabel, lp(WRAP, WRAP).margins(top = dp(2)))
        row.addView(bt, lp(0, MATCH).weighted(1f).margins(left = dp(12)))
        addView(row, lp(MATCH, WRAP).margins(top = dp(12)))

        addView(music, lp(MATCH, WRAP).margins(top = dp(12)))

        grid.orientation = VERTICAL
        addView(grid, lp(MATCH, WRAP).margins(top = dp(18)))

        addView(View(c), lp(MATCH, 0).weighted())
        val search = c.panel(t).apply {
            orientation = HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(16), dp(12), dp(16), dp(12))
            setOnClickListener { host.openDrawer(search = true) }
        }
        search.addView(GlyphView(c, Glyph.SEARCH, t.accent), lp(dp(18), dp(18)))
        search.addView(c.text("Apps, Kontakte, Web …", 15f, t.textDim, Fonts.medium), lp(WRAP, WRAP).margins(left = dp(12)))
        addView(search, lp(MATCH, WRAP).margins(bottom = dp(4)))
    }

    fun update(s: HomeState) {
        val c = context
        clock.text = clockText(c)
        date.text = SimpleDateFormat("EE · d MMMM", Locale.GERMAN).format(Date()).replace(".", "").uppercase()
        val w = Weather.cached(c)
        wxIcon.set(Weather.glyph(w?.code ?: 2), t.accent)
        wxTemp.text = if (w != null) "${w.tempC}°" else "--°"

        agenda.removeAllViews()
        if (!Agenda.permitted(c)) {
            agenda.addView(c.text("Kalender verbinden", 14f, t.accent, Fonts.medium), lp(WRAP, WRAP).margins(top = dp(8)))
            (agenda.parent as View).setOnClickListener { host.askCalendar() }
        } else {
            (agenda.parent as View).setOnClickListener { host.openCalendar() }
            val items = Agenda.today(c)
            if (items.isEmpty()) agenda.addView(c.text("Keine Termine", 14f, t.textDim, Fonts.medium), lp(WRAP, WRAP).margins(top = dp(8)))
            items.forEachIndexed { i, it ->
                val r = LinearLayout(c).apply { orientation = HORIZONTAL }
                r.addView(c.text(it.time, 14f, if (i == 0) t.accent else t.textDim, Fonts.medium), lp(dp(40), WRAP))
                r.addView(c.text(it.title, 14f, t.text, Fonts.medium).apply { maxLines = 1; ellipsize = TextUtils.TruncateAt.END },
                    lp(0, WRAP).weighted())
                agenda.addView(r, lp(MATCH, WRAP).margins(top = dp(7)))
            }
        }

        ring.value = (s.battery.coerceAtLeast(0)) / 100f
        ring.label = if (s.battery >= 0) "${s.battery}%" else "—"
        ringLabel.text = if (s.charging) "LÄDT" else "ENERGIE"

        updateMusic()

        grid.removeAllViews()
        val apps = s.favorites.drop(5).take(8)
        if (apps.isEmpty()) {
            grid.addView(c.text("Mehr als 5 Favoriten erscheinen hier. Lange auf eine App drücken → Zu Favoriten.",
                13f, t.textDim).apply { gravity = Gravity.CENTER; setPadding(dp(16), 0, dp(16), 0) })
        }
        apps.chunked(4).forEach { rowApps ->
            val r = LinearLayout(c).apply { orientation = HORIZONTAL }
            for (i in 0 until 4) {
                val e = rowApps.getOrNull(i)
                val cell = LinearLayout(c).apply { orientation = VERTICAL; gravity = Gravity.CENTER_HORIZONTAL }
                if (e != null) {
                    cell.addView(ImageView(c).apply {
                        setImageDrawable(IconStyler.style(c, e.id, e.icon, t, 1, dp(52)))
                    }, lp(dp(52), dp(52)))
                    cell.addView(c.text(e.label, 11f, t.textDim, Fonts.medium).apply {
                        maxLines = 1; ellipsize = TextUtils.TruncateAt.END; gravity = Gravity.CENTER
                    }, lp(MATCH, WRAP).margins(top = dp(5), left = dp(2), right = dp(2)))
                    cell.setOnClickListener { host.launch(e, it) }
                    cell.setOnLongClickListener { host.appMenu(e, it); true }
                }
                r.addView(cell, lp(0, WRAP).weighted())
            }
            grid.addView(r, lp(MATCH, WRAP).margins(top = dp(10)))
        }
    }

    fun updateMusic() {
        val c = context
        music.removeAllViews()
        val ctrl = Media.active(c) ?: return
        val meta = ctrl.metadata ?: return
        val title = meta.getString(MediaMetadata.METADATA_KEY_TITLE) ?: return
        val artist = meta.getString(MediaMetadata.METADATA_KEY_ARTIST) ?: ""
        val art = meta.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART) ?: meta.getBitmap(MediaMetadata.METADATA_KEY_ART)
        val playing = ctrl.playbackState?.state == PlaybackState.STATE_PLAYING
        val p = c.panel(t).apply {
            orientation = HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(14), dp(12), dp(10), dp(12))
            setOnClickListener { ctrl.sessionActivity?.let { pi -> try { pi.send() } catch (_: Exception) { } } }
        }
        val cover = ImageView(c).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            background = roundRect(t.accent.withAlpha(90), c.dpf(10f))
            clipToOutline = true
            if (art != null) setImageDrawable(BitmapDrawable(resources, art))
            else { setImageDrawable(null); }
        }
        p.addView(cover, lp(dp(46), dp(46)))
        val col = LinearLayout(c).apply { orientation = VERTICAL }
        col.addView(c.text(title, 15f, t.text, Fonts.medium).apply { maxLines = 1; ellipsize = TextUtils.TruncateAt.END })
        if (artist.isNotBlank()) col.addView(c.text(artist, 12.5f, t.textDim).apply { maxLines = 1; ellipsize = TextUtils.TruncateAt.END })
        val dur = meta.getLong(MediaMetadata.METADATA_KEY_DURATION)
        val pos = ctrl.playbackState?.position ?: 0L
        if (dur > 0) col.addView(SegBar(c, t, 30).apply { value = pos.toFloat() / dur }, lp(MATCH, dp(3)).margins(top = dp(6)))
        p.addView(col, lp(0, WRAP).weighted().margins(left = dp(12)))
        fun btn(g: Glyph, color: Int, a: () -> Unit) = GlyphView(c, g, color).apply {
            setPadding(dp(7), dp(7), dp(7), dp(7))
            setOnClickListener { a(); postDelayed({ updateMusic() }, 350) }
            p.addView(this, lp(dp(36), dp(36)))
        }
        btn(Glyph.PREV, t.text) { ctrl.transportControls.skipToPrevious() }
        btn(if (playing) Glyph.PAUSE else Glyph.PLAY, t.accent) {
            if (playing) ctrl.transportControls.pause() else ctrl.transportControls.play()
        }
        btn(Glyph.NEXT, t.text) { ctrl.transportControls.skipToNext() }
        music.addView(p)
    }
}
