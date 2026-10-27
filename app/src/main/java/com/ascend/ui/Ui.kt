package com.ascend.ui

import android.app.ActivityOptions
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.IntentFilter
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.widget.LinearLayout
import android.widget.TextView
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

const val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
const val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT

fun Context.dp(v: Float): Int = (v * resources.displayMetrics.density + 0.5f).toInt()
fun Context.dp(v: Int): Int = dp(v.toFloat())
fun Context.dpf(v: Float): Float = v * resources.displayMetrics.density
fun View.dp(v: Int): Int = context.dp(v)
fun View.dp(v: Float): Int = context.dp(v)

/**
 * App-wide typefaces. [init] swaps in the bundled fonts (Orbitron for HUD labels, Rajdhani for
 * headings, keys and the clock); until then, and if loading fails, system fonts are used.
 */
object Fonts {
    var mono: Typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)      // HUD labels → Orbitron
        private set
    var thin: Typeface = Typeface.create("sans-serif-thin", Typeface.NORMAL)     // big clocks → Rajdhani Medium
        private set
    var medium: Typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL) // titles, keys → Rajdhani SemiBold
        private set
    val light: Typeface = Typeface.create("sans-serif-light", Typeface.NORMAL)
    val regular: Typeface = Typeface.create("sans-serif", Typeface.NORMAL)        // body text stays system

    @Volatile private var loaded = false

    fun init(ctx: Context) {
        if (loaded) return
        loaded = true
        fun load(name: String): Typeface? = try {
            Typeface.createFromAsset(ctx.assets, "fonts/$name.ttf")
        } catch (_: Exception) { null }
        load("orbitron_semibold")?.let { mono = it }
        load("rajdhani_medium")?.let { thin = it }
        load("rajdhani_semibold")?.let { medium = it }
    }
}

fun Context.text(
    s: CharSequence, sp: Float, color: Int,
    face: Typeface = Fonts.regular, spacing: Float = 0f
): TextView = TextView(this).apply {
    text = s
    textSize = sp
    setTextColor(color)
    typeface = face
    letterSpacing = spacing
    includeFontPadding = false
}

/** Small uppercase monospaced label in the accent colour – the "HUD" voice of the app. */
fun Context.hudLabel(s: String, t: AscendTheme, sp: Float = 11f): TextView =
    text(s.uppercase(), sp * 0.9f, t.accent, Fonts.mono, 0.1f)

fun lp(w: Int, h: Int) = LinearLayout.LayoutParams(w, h)

fun LinearLayout.LayoutParams.margins(left: Int = 0, top: Int = 0, right: Int = 0, bottom: Int = 0) =
    apply { setMargins(left, top, right, bottom) }

fun LinearLayout.LayoutParams.weighted(w: Float = 1f) = apply { weight = w }

fun roundRect(fill: Int, radius: Float, stroke: Int = 0, strokeW: Int = 0): GradientDrawable =
    GradientDrawable().apply {
        setColor(fill)
        cornerRadius = radius
        if (strokeW > 0) setStroke(strokeW, stroke)
    }

fun Context.statusBarHeight(): Int {
    val id = resources.getIdentifier("status_bar_height", "dimen", "android")
    return if (id > 0) resources.getDimensionPixelSize(id) else dp(28)
}

fun Context.navBarHeight(): Int {
    val id = resources.getIdentifier("navigation_bar_height", "dimen", "android")
    return if (id > 0) resources.getDimensionPixelSize(id) else 0
}

fun Context.registerSys(r: BroadcastReceiver, f: IntentFilter) {
    if (Build.VERSION.SDK_INT >= 33) registerReceiver(r, f, Context.RECEIVER_EXPORTED)
    else registerReceiver(r, f)
}

fun Context.unregisterSafe(r: BroadcastReceiver) {
    try { unregisterReceiver(r) } catch (_: Exception) { }
}

/** Draw behind the status and navigation bars, with icon colours matching the theme. */
@Suppress("DEPRECATION")
fun edgeToEdge(w: Window, light: Boolean) {
    w.statusBarColor = Color.TRANSPARENT
    w.navigationBarColor = Color.TRANSPARENT
    if (Build.VERSION.SDK_INT >= 29) {
        w.isNavigationBarContrastEnforced = false
        w.isStatusBarContrastEnforced = false
    }
    if (Build.VERSION.SDK_INT >= 30) {
        w.setDecorFitsSystemWindows(false)
        val mask = WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS or
            WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS
        w.insetsController?.setSystemBarsAppearance(if (light) mask else 0, mask)
    } else {
        var f = View.SYSTEM_UI_FLAG_LAYOUT_STABLE or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or
            View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
        if (light) f = f or View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR or View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR
        w.decorView.systemUiVisibility = f
    }
}

/** Returns (top, bottom) insets; bottom includes the on-screen keyboard when it is open. */
@Suppress("DEPRECATION")
fun barInsets(ins: WindowInsets): Pair<Int, Int> {
    return if (Build.VERSION.SDK_INT >= 30) {
        val bars = ins.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
        val ime = ins.getInsets(WindowInsets.Type.ime())
        bars.top to max(bars.bottom, ime.bottom)
    } else {
        ins.systemWindowInsetTop to ins.systemWindowInsetBottom
    }
}

/** Fire a notification's PendingIntent from an overlay tap, allowed to open its activity. */
fun PendingIntent.sendFromOverlay(ctx: Context) {
    try {
        if (Build.VERSION.SDK_INT >= 34) {
            @Suppress("DEPRECATION")
            val opts = ActivityOptions.makeBasic()
                .setPendingIntentBackgroundActivityStartMode(ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOWED)
            send(ctx, 0, null, null, null, null, opts.toBundle())
        } else {
            send()
        }
    } catch (_: Exception) { }
}

/** Panel background: translucent fill, thin accent outline, soft glow and HUD corner brackets. */
class HudDrawable(
    private val t: AscendTheme,
    private val density: Float,
    private val radiusDp: Float = 12f,
    private val brackets: Boolean = true,
    fillColor: Int = t.surface.withAlpha(238),
    strokeAlpha: Int = 90
) : Drawable() {
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL; color = fillColor }
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = density; color = t.accent.withAlpha(strokeAlpha)
    }
    private val glow = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; color = t.accent.withAlpha(if (t.light) 10 else 18)
    }
    private val br = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = 2f * density; color = t.accent; strokeCap = Paint.Cap.SQUARE
    }
    private val rf = RectF()
    private val path = Path()

    override fun draw(c: Canvas) {
        val b = bounds
        val inset = 3f * density
        rf.set(b.left + inset, b.top + inset, b.right - inset, b.bottom - inset)
        val r = radiusDp * density
        for (i in 1..2) {
            glow.strokeWidth = i * 2.5f * density
            c.drawRoundRect(rf, r, r, glow)
        }
        c.drawRoundRect(rf, r, r, fill)
        c.drawRoundRect(rf, r, r, stroke)
        if (brackets) {
            val len = min(16f * density, min(rf.width(), rf.height()) / 4f)
            val o = 1.5f * density
            val l = rf.left - o
            val tp = rf.top - o
            val rr = rf.right + o
            val bt = rf.bottom + o
            path.reset()
            path.moveTo(l, tp + len); path.lineTo(l, tp); path.lineTo(l + len, tp)
            path.moveTo(rr - len, tp); path.lineTo(rr, tp); path.lineTo(rr, tp + len)
            path.moveTo(rr, bt - len); path.lineTo(rr, bt); path.lineTo(rr - len, bt)
            path.moveTo(l + len, bt); path.lineTo(l, bt); path.lineTo(l, bt - len)
            c.drawPath(path, br)
        }
    }

    override fun setAlpha(alpha: Int) {}
    override fun setColorFilter(colorFilter: ColorFilter?) {}
    @Deprecated("Deprecated in Java")
    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
}

/** Segmented progress bar, e.g. battery level in the status window. */
class SegBar(ctx: Context, private val t: AscendTheme, private val segments: Int = 12) : View(ctx) {
    var value: Float = 0f
        set(v) { field = v.coerceIn(0f, 1f); invalidate() }
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val r = RectF()

    override fun onDraw(c: Canvas) {
        val gap = context.dpf(2.5f)
        val w = (width - gap * (segments - 1)) / segments
        val lit = (value * segments + 0.001f).toInt()
        for (i in 0 until segments) {
            val x = i * (w + gap)
            r.set(x, 0f, x + w, height.toFloat())
            p.color = if (i < lit) t.accent else t.surfaceHi
            c.drawRoundRect(r, context.dpf(1.5f), context.dpf(1.5f), p)
        }
    }
}

enum class Glyph { WIFI, BLUETOOTH, TORCH, DND, ROTATE, SCREENSHOT, LOCK, POWER, GEAR, CLOSE, PLAY, PAUSE, PREV, NEXT, SEARCH, BRIGHT, GRID }

/** Simple line icons drawn in code on a 24×24 grid, so they take the theme colour exactly. */
class GlyphView(ctx: Context, private var glyph: Glyph, private var color: Int) : View(ctx) {
    private val p = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND
    }
    private val path = Path()
    private val r = RectF()

    fun set(g: Glyph, col: Int) { glyph = g; color = col; invalidate() }

    override fun onDraw(c: Canvas) {
        val s = min(width, height) / 24f
        c.save()
        c.translate((width - 24f * s) / 2f, (height - 24f * s) / 2f)
        c.scale(s, s)
        p.color = color
        p.strokeWidth = 1.8f
        p.style = Paint.Style.STROKE
        path.reset()
        when (glyph) {
            Glyph.WIFI -> {
                for (rad in floatArrayOf(4.5f, 9f, 13.5f)) {
                    r.set(12f - rad, 20f - rad, 12f + rad, 20f + rad)
                    c.drawArc(r, 225f, 90f, false, p)
                }
                p.style = Paint.Style.FILL; c.drawCircle(12f, 20f, 1.6f, p)
            }
            Glyph.BLUETOOTH -> {
                path.moveTo(7f, 7f); path.lineTo(17f, 16.5f); path.lineTo(12f, 21.5f)
                path.lineTo(12f, 2.5f); path.lineTo(17f, 7.5f); path.lineTo(7f, 17f)
                c.drawPath(path, p)
            }
            Glyph.TORCH -> {
                path.moveTo(13f, 2.5f); path.lineTo(5.5f, 13.5f); path.lineTo(11.5f, 13.5f)
                path.lineTo(10.5f, 21.5f); path.lineTo(18.5f, 10f); path.lineTo(12.5f, 10f); path.close()
                c.drawPath(path, p)
            }
            Glyph.DND -> {
                c.drawCircle(12f, 12f, 8.5f, p); c.drawLine(8f, 12f, 16f, 12f, p)
            }
            Glyph.ROTATE -> {
                r.set(5f, 5f, 19f, 19f); c.drawArc(r, 20f, 290f, false, p)
                c.drawLine(16.5f, 6.6f, 17.6f, 3.4f, p); c.drawLine(16.5f, 6.6f, 19.8f, 7.3f, p)
            }
            Glyph.SCREENSHOT -> {
                path.moveTo(4f, 9f); path.lineTo(4f, 4f); path.lineTo(9f, 4f)
                path.moveTo(15f, 4f); path.lineTo(20f, 4f); path.lineTo(20f, 9f)
                path.moveTo(20f, 15f); path.lineTo(20f, 20f); path.lineTo(15f, 20f)
                path.moveTo(9f, 20f); path.lineTo(4f, 20f); path.lineTo(4f, 15f)
                c.drawPath(path, p); c.drawCircle(12f, 12f, 2.6f, p)
            }
            Glyph.LOCK -> {
                r.set(5.5f, 11f, 18.5f, 21f); c.drawRoundRect(r, 2f, 2f, p)
                r.set(8f, 4f, 16f, 15f); c.drawArc(r, 180f, 180f, false, p)
                c.drawLine(8f, 9.5f, 8f, 11f, p); c.drawLine(16f, 9.5f, 16f, 11f, p)
            }
            Glyph.POWER -> {
                r.set(4.5f, 5f, 19.5f, 20f); c.drawArc(r, 300f, 300f, false, p)
                c.drawLine(12f, 3f, 12f, 11f, p)
            }
            Glyph.GEAR -> {
                c.drawCircle(12f, 12f, 3.2f, p); c.drawCircle(12f, 12f, 7f, p)
                for (i in 0 until 8) {
                    val a = Math.toRadians(i * 45.0)
                    c.drawLine(
                        12f + 7f * cos(a).toFloat(), 12f + 7f * sin(a).toFloat(),
                        12f + 9.8f * cos(a).toFloat(), 12f + 9.8f * sin(a).toFloat(), p
                    )
                }
            }
            Glyph.CLOSE -> { c.drawLine(6.5f, 6.5f, 17.5f, 17.5f, p); c.drawLine(17.5f, 6.5f, 6.5f, 17.5f, p) }
            Glyph.PLAY -> {
                p.style = Paint.Style.FILL
                path.moveTo(8f, 5f); path.lineTo(19f, 12f); path.lineTo(8f, 19f); path.close(); c.drawPath(path, p)
            }
            Glyph.PAUSE -> {
                p.style = Paint.Style.FILL
                c.drawRect(7f, 5f, 10.5f, 19f, p); c.drawRect(13.5f, 5f, 17f, 19f, p)
            }
            Glyph.PREV -> {
                p.style = Paint.Style.FILL
                path.moveTo(18f, 6f); path.lineTo(9f, 12f); path.lineTo(18f, 18f); path.close(); c.drawPath(path, p)
                c.drawRect(6f, 6f, 8f, 18f, p)
            }
            Glyph.NEXT -> {
                p.style = Paint.Style.FILL
                path.moveTo(6f, 6f); path.lineTo(15f, 12f); path.lineTo(6f, 18f); path.close(); c.drawPath(path, p)
                c.drawRect(16f, 6f, 18f, 18f, p)
            }
            Glyph.SEARCH -> { c.drawCircle(10.5f, 10.5f, 6f, p); c.drawLine(15f, 15f, 20f, 20f, p) }
            Glyph.BRIGHT -> {
                c.drawCircle(12f, 12f, 4f, p)
                for (i in 0 until 8) {
                    val a = Math.toRadians(i * 45.0)
                    c.drawLine(
                        12f + 6.5f * cos(a).toFloat(), 12f + 6.5f * sin(a).toFloat(),
                        12f + 9f * cos(a).toFloat(), 12f + 9f * sin(a).toFloat(), p
                    )
                }
            }
            Glyph.GRID -> {
                for ((x, y) in listOf(4f to 4f, 14f to 4f, 4f to 14f, 14f to 14f)) {
                    r.set(x, y, x + 6f, y + 6f); c.drawRoundRect(r, 1.5f, 1.5f, p)
                }
            }
        }
        c.restore()
    }
}
