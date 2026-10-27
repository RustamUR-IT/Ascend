package com.ascend.ui

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Outline
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.SweepGradient
import android.media.AudioManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.text.TextUtils
import android.text.format.DateUtils
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewOutlineProvider
import android.view.WindowManager
import android.view.animation.DecelerateInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * Where floating windows go. With the accessibility module on, Ascend draws "accessibility overlays",
 * which sit above the status bar. Otherwise it falls back to ordinary "display over other apps" windows.
 */
object Overlay {
    fun host(ctx: Context): Pair<Context, Int>? {
        ShadeService.instance?.let { return it to WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY }
        if (Settings.canDrawOverlays(ctx)) {
            return ctx.applicationContext to WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        }
        return null
    }

    /** True when the phone can blur what's behind an overlay window (Android 12+, if the system allows it). */
    fun canBlur(ctx: Context): Boolean = Build.VERSION.SDK_INT >= 31 && try {
        (ctx.getSystemService(Context.WINDOW_SERVICE) as WindowManager).isCrossWindowBlurEnabled
    } catch (_: Exception) { false }

    fun params(type: Int, w: Int, h: Int, touchable: Boolean, focusable: Boolean = false, full: Boolean = false):
        WindowManager.LayoutParams {
        var flags = WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED
        if (!focusable) flags = flags or WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
        if (!touchable) flags = flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
        if (full) flags = flags or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
        val p = WindowManager.LayoutParams(w, h, type, flags, PixelFormat.TRANSLUCENT)
        if (Build.VERSION.SDK_INT >= 30) {
            p.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
        } else if (Build.VERSION.SDK_INT >= 28) {
            p.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        }
        // Android blocks touches under non-touchable app overlays that are more than 80% opaque.
        if (!touchable && type == WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY) p.alpha = 0.8f
        return p
    }

    fun add(host: Context, v: View, p: WindowManager.LayoutParams): Boolean = try {
        (host.getSystemService(Context.WINDOW_SERVICE) as WindowManager).addView(v, p)
        true
    } catch (_: Exception) { false }

    fun remove(host: Context, v: View) {
        try { (host.getSystemService(Context.WINDOW_SERVICE) as WindowManager).removeViewImmediate(v) } catch (_: Exception) { }
    }
}

// ======================================================================== notification popups

object PopupManager {
    private val queue = ArrayDeque<Notice>()
    private var showing: View? = null
    private var host: Context? = null
    private var app: Context? = null
    private val h = Handler(Looper.getMainLooper())
    private val autoHide = Runnable { dismiss() }

    fun show(ctx: Context, n: Notice) {
        h.post {
            app = ctx.applicationContext
            queue.addLast(n)
            while (queue.size > 4) queue.removeFirst()
            if (showing == null) next()
        }
    }

    private fun next() {
        val appCtx = app ?: return
        val n = queue.removeFirstOrNull() ?: return
        val (hostCtx, type) = Overlay.host(appCtx) ?: run { queue.clear(); return }
        val t = Prefs.theme(appCtx)
        val card = buildCard(hostCtx, t, n)
        val wrap = FrameLayout(hostCtx).apply {
            setPadding(hostCtx.dp(10), hostCtx.dp(4), hostCtx.dp(10), hostCtx.dp(4))
            addView(card, FrameLayout.LayoutParams(MATCH, WRAP))
        }
        val p = Overlay.params(type, MATCH, WRAP, touchable = true)
        p.gravity = Gravity.TOP
        p.y = hostCtx.statusBarHeight() + hostCtx.dp(4)
        if (!Overlay.add(hostCtx, wrap, p)) { queue.clear(); return }
        showing = wrap
        host = hostCtx
        card.translationY = -hostCtx.dpf(130f)
        card.alpha = 0f
        card.animate().translationY(0f).alpha(1f).setDuration(280).setInterpolator(DecelerateInterpolator()).start()

        var downY = 0f
        var moved = false
        card.setOnTouchListener { _, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> { downY = e.rawY; moved = false; h.removeCallbacks(autoHide) }
                MotionEvent.ACTION_MOVE -> {
                    val dy = e.rawY - downY
                    if (abs(dy) > hostCtx.dp(8)) moved = true
                    if (dy < 0) card.translationY = dy
                }
                MotionEvent.ACTION_UP -> {
                    val dy = e.rawY - downY
                    if (!moved) { open(appCtx, n); dismiss() }
                    else if (dy < -hostCtx.dp(36)) dismiss()
                    else { card.animate().translationY(0f).setDuration(150).start(); h.postDelayed(autoHide, 3000) }
                }
                MotionEvent.ACTION_CANCEL -> h.postDelayed(autoHide, 3000)
            }
            true
        }
        h.postDelayed(autoHide, 4800)
    }

    private fun open(ctx: Context, n: Notice) {
        n.intent?.sendFromOverlay(ctx)
        if (n.autoCancel) NotifyListener.dismiss(n.key)
    }

    private fun dismiss() {
        h.removeCallbacks(autoHide)
        val v = showing ?: return
        val hostCtx = host ?: return
        val card = (v as FrameLayout).getChildAt(0)
        card.animate().translationY(-hostCtx.dpf(130f)).alpha(0f).setDuration(220).withEndAction {
            Overlay.remove(hostCtx, v)
            showing = null
            h.postDelayed({ next() }, 120)
        }.start()
    }

    fun buildCard(c: Context, t: AscendTheme, n: Notice): View {
        val card = LinearLayout(c).apply {
            orientation = LinearLayout.VERTICAL
            background = HudDrawable(t, c.resources.displayMetrics.density, fillColor = t.surface.withAlpha(248))
            setPadding(c.dp(18), c.dp(14), c.dp(18), c.dp(16))
        }
        val head = LinearLayout(c).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        head.addView(c.hudLabel("◆ Notice · ${n.app}", t, 10f).apply {
            maxLines = 1; ellipsize = TextUtils.TruncateAt.END
        }, lp(0, WRAP).weighted())
        head.addView(c.text("NOW", 9.5f, t.textDim, Fonts.mono, 0.15f))
        card.addView(head)

        val body = LinearLayout(c).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        if (n.icon != null) body.addView(roundIcon(c, n.icon), lp(c.dp(42), c.dp(42)).margins(right = c.dp(14)))
        val col = LinearLayout(c).apply { orientation = LinearLayout.VERTICAL }
        col.addView(c.text(n.title, 15.5f, t.text, Fonts.medium).apply { maxLines = 1; ellipsize = TextUtils.TruncateAt.END })
        if (n.text.isNotBlank()) {
            col.addView(c.text(n.text, 13.5f, t.textDim).apply {
                maxLines = 2; ellipsize = TextUtils.TruncateAt.END; setLineSpacing(0f, 1.15f)
            }, lp(MATCH, WRAP).margins(top = c.dp(4)))
        }
        body.addView(col, lp(0, WRAP).weighted())
        card.addView(body, lp(MATCH, WRAP).margins(top = c.dp(12)))
        return card
    }

    fun roundIcon(c: Context, d: android.graphics.drawable.Drawable): ImageView = ImageView(c).apply {
        setImageDrawable(d)
        scaleType = ImageView.ScaleType.CENTER_CROP
        outlineProvider = object : ViewOutlineProvider() {
            override fun getOutline(view: View, outline: Outline) = outline.setOval(0, 0, view.width, view.height)
        }
        clipToOutline = true
    }

    fun relTime(time: Long): String =
        DateUtils.getRelativeTimeSpanString(time, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS).toString()
}

// ======================================================================== edge glow

object EdgeGlow {
    private var active = false
    private val h = Handler(Looper.getMainLooper())

    fun flash(ctx: Context, durationMs: Long = 1700) {
        h.post {
            if (active) return@post
            val (hostCtx, type) = Overlay.host(ctx.applicationContext) ?: return@post
            val v = GlowView(hostCtx, Prefs.theme(ctx))
            val p = Overlay.params(type, MATCH, MATCH, touchable = false, full = true)
            if (!Overlay.add(hostCtx, v, p)) return@post
            active = true
            ValueAnimator.ofFloat(0f, 1f).apply {
                duration = durationMs
                addUpdateListener { v.progress = it.animatedValue as Float; v.invalidate() }
                addListener(object : AnimatorListenerAdapter() {
                    override fun onAnimationEnd(animation: Animator) {
                        Overlay.remove(hostCtx, v)
                        active = false
                    }
                })
                start()
            }
        }
    }

    private class GlowView(ctx: Context, private val t: AscendTheme) : View(ctx) {
        var progress = 0f
        private val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
        private val r = RectF()
        private val m = Matrix()
        private var shader: SweepGradient? = null

        override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) {
            shader = SweepGradient(
                w / 2f, h / 2f,
                intArrayOf(t.accent, t.accentSoft, t.text, t.accent, t.accentSoft, t.accent),
                null
            )
        }

        override fun onDraw(c: Canvas) {
            val s = shader ?: return
            val a = sin(PI * progress).toFloat().coerceIn(0f, 1f)
            m.setRotate(progress * 300f, width / 2f, height / 2f)
            s.setLocalMatrix(m)
            p.shader = s
            val radius = context.dpf(38f)
            for ((wDp, alpha) in listOf(16f to 0.18f, 8f to 0.4f, 3f to 1f)) {
                val sw = context.dpf(wDp)
                p.strokeWidth = sw
                p.alpha = (255 * a * alpha).toInt()
                r.set(sw / 2f, sw / 2f, width - sw / 2f, height - sw / 2f)
                c.drawRoundRect(r, radius, radius, p)
            }
        }
    }
}

// ======================================================================== HUD frame & unlock splash

/** Corner brackets framing the whole screen. Touches pass straight through. */
class HudFrameView(ctx: Context, private val t: AscendTheme) : View(ctx) {
    private val p = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = ctx.dpf(2f); color = t.accent.withAlpha(170); strokeCap = Paint.Cap.ROUND
    }
    private val dot = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = t.accent.withAlpha(200) }

    override fun onDraw(c: Canvas) {
        val i = context.dpf(7f)
        val len = context.dpf(30f)
        val w = width.toFloat()
        val h = height.toFloat()
        c.drawLine(i, i + len, i, i, p); c.drawLine(i, i, i + len, i, p)
        c.drawLine(w - i - len, i, w - i, i, p); c.drawLine(w - i, i, w - i, i + len, p)
        c.drawLine(w - i, h - i - len, w - i, h - i, p); c.drawLine(w - i, h - i, w - i - len, h - i, p)
        c.drawLine(i + len, h - i, i, h - i, p); c.drawLine(i, h - i, i, h - i - len, p)
        c.drawCircle(w / 2f, h - i, context.dpf(2f), dot)
    }
}

/** Short "system online" flash after unlocking. */
class SplashView(ctx: Context, private val t: AscendTheme) : View(ctx) {
    var progress = 0f
    private val bg = Paint()
    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply { strokeWidth = ctx.dpf(1.5f) }
    private val title = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Fonts.mono; textSize = ctx.dpf(17f); letterSpacing = 0.45f; textAlign = Paint.Align.CENTER
    }
    private val sub = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Fonts.mono; textSize = ctx.dpf(10.5f); letterSpacing = 0.35f; textAlign = Paint.Align.CENTER
    }

    override fun onDraw(c: Canvas) {
        val p = progress
        val fadeOut = if (p > 0.72f) 1f - (p - 0.72f) / 0.28f else 1f
        val fadeIn = (p / 0.12f).coerceAtMost(1f)
        val a = (fadeIn * fadeOut).coerceIn(0f, 1f)
        bg.color = t.bg.withAlpha((200 * a).toInt())
        c.drawRect(0f, 0f, width.toFloat(), height.toFloat(), bg)
        val cx = width / 2f
        val cy = height / 2f
        val grow = (p / 0.35f).coerceAtMost(1f)
        val half = width * 0.36f * DecelerateInterpolator().getInterpolation(grow)
        line.color = t.accent.withAlpha((255 * a).toInt())
        c.drawLine(cx - half, cy - context.dpf(26f), cx + half, cy - context.dpf(26f), line)
        c.drawLine(cx - half, cy + context.dpf(30f), cx + half, cy + context.dpf(30f), line)
        val textA = ((p - 0.12f) / 0.2f).coerceIn(0f, 1f) * fadeOut
        title.color = t.text.withAlpha((255 * textA).toInt())
        sub.color = t.accent.withAlpha((230 * textA).toInt())
        c.drawText("SYSTEM ONLINE", cx, cy + context.dpf(5f), title)
        c.drawText("WELCOME BACK", cx, cy + context.dpf(20f), sub)
    }
}

// ======================================================================== volume panel

class VolumePanel(private val svc: ShadeService) {
    private val am = svc.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private var view: VolView? = null
    private val h = Handler(Looper.getMainLooper())
    private val hide = Runnable { dismiss() }
    private val stream = AudioManager.STREAM_MUSIC

    fun adjust(up: Boolean) {
        try {
            am.adjustStreamVolume(stream, if (up) AudioManager.ADJUST_RAISE else AudioManager.ADJUST_LOWER, 0)
        } catch (_: Exception) { }
        show()
    }

    private fun level(): Float {
        val max = am.getStreamMaxVolume(stream).coerceAtLeast(1)
        return am.getStreamVolume(stream).toFloat() / max
    }

    private fun show() {
        if (view == null) {
            val t = Prefs.theme(svc)
            val v = VolView(svc, t) { frac -> setFrac(frac) }
            val p = Overlay.params(WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY, svc.dp(70), svc.dp(270), touchable = true)
            p.gravity = Gravity.END or Gravity.CENTER_VERTICAL
            p.x = svc.dp(8)
            if (!Overlay.add(svc, v, p)) return
            v.alpha = 0f
            v.translationX = svc.dpf(40f)
            v.animate().alpha(1f).translationX(0f).setDuration(180).setInterpolator(DecelerateInterpolator()).start()
            view = v
        }
        view?.level = level()
        h.removeCallbacks(hide)
        h.postDelayed(hide, 2200)
    }

    private fun setFrac(frac: Float) {
        val max = am.getStreamMaxVolume(stream)
        try { am.setStreamVolume(stream, (frac * max).roundToInt(), 0) } catch (_: Exception) { }
        view?.level = level()
        h.removeCallbacks(hide)
        h.postDelayed(hide, 2200)
    }

    fun dismiss() {
        h.removeCallbacks(hide)
        val v = view ?: return
        view = null
        v.animate().alpha(0f).translationX(svc.dpf(40f)).setDuration(170).withEndAction { Overlay.remove(svc, v) }.start()
    }

    private class VolView(ctx: Context, private val t: AscendTheme, private val onDrag: (Float) -> Unit) : View(ctx) {
        var level = 0f
            set(v) { field = v.coerceIn(0f, 1f); invalidate() }
        private val seg = Paint(Paint.ANTI_ALIAS_FLAG)
        private val label = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            typeface = Fonts.mono; textAlign = Paint.Align.CENTER; letterSpacing = 0.12f
        }
        private val r = RectF()
        private val segments = 16

        init { background = HudDrawable(t, ctx.resources.displayMetrics.density, radiusDp = 18f) }

        private fun trackTop() = context.dpf(46f)
        private fun trackBottom() = height - context.dpf(38f)

        override fun onDraw(c: Canvas) {
            super.onDraw(c)
            val top = trackTop()
            val bottom = trackBottom()
            val gap = context.dpf(3f)
            val sh = (bottom - top - gap * (segments - 1)) / segments
            val lit = (level * segments + 0.001f).toInt()
            val half = context.dpf(9f)
            val cx = width / 2f
            for (i in 0 until segments) {
                val y = bottom - (i + 1) * sh - i * gap
                r.set(cx - half, y, cx + half, y + sh)
                seg.color = if (i < lit) t.accent else t.surfaceHi
                c.drawRoundRect(r, context.dpf(2f), context.dpf(2f), seg)
            }
            label.textSize = context.dpf(13f)
            label.color = t.text
            c.drawText("${(level * 100).roundToInt()}", cx, context.dpf(30f), label)
            label.textSize = context.dpf(8.5f)
            label.color = t.textDim
            c.drawText("MEDIA", cx, height - context.dpf(18f), label)
        }

        override fun onTouchEvent(e: MotionEvent): Boolean {
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE -> {
                    val frac = 1f - (e.y - trackTop()) / (trackBottom() - trackTop())
                    onDrag(frac.coerceIn(0f, 1f))
                }
            }
            return true
        }
    }
}
