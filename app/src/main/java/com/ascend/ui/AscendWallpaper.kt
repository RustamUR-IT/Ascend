package com.ascend.ui

import android.app.WallpaperColors
import android.content.SharedPreferences
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.Shader
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.service.wallpaper.WallpaperService
import android.view.MotionEvent
import android.view.SurfaceHolder
import java.util.Random
import kotlin.math.sin

/**
 * Live wallpaper: dark field, soft glow, a receding grid and rising particles in the theme colours.
 * It also reports the theme's accent as the wallpaper colour, so Android's Material You tint follows it.
 */
class AscendWallpaper : WallpaperService() {
    override fun onCreateEngine(): Engine = Eng()

    inner class Eng : Engine(), SharedPreferences.OnSharedPreferenceChangeListener {
        private val h = Handler(Looper.getMainLooper())
        private var t = Prefs.theme(this@AscendWallpaper)
        private val d = resources.displayMetrics.density
        private var w = 0
        private var ht = 0
        private var visible = false
        private val rnd = Random()
        private val start = SystemClock.uptimeMillis()
        private val draw = Runnable { frame() }

        private inner class P(var x: Float, var y: Float, var v: Float, var r: Float, val ph: Float)
        private val particles = ArrayList<P>()
        private val ripples = ArrayList<FloatArray>()

        private val glow = Paint()
        private val dot = Paint(Paint.ANTI_ALIAS_FLAG)
        private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply { strokeWidth = d }
        private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 2f * d }

        override fun onCreate(surfaceHolder: SurfaceHolder) {
            super.onCreate(surfaceHolder)
            setTouchEventsEnabled(true)
            Prefs.sp(this@AscendWallpaper).registerOnSharedPreferenceChangeListener(this)
        }

        override fun onDestroy() {
            Prefs.sp(this@AscendWallpaper).unregisterOnSharedPreferenceChangeListener(this)
            h.removeCallbacks(draw)
            super.onDestroy()
        }

        override fun onVisibilityChanged(v: Boolean) {
            visible = v
            if (v) h.post(draw) else h.removeCallbacks(draw)
        }

        override fun onSurfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
            super.onSurfaceChanged(holder, format, width, height)
            w = width
            ht = height
            seed()
            shaders()
            frame()
        }

        override fun onSurfaceDestroyed(holder: SurfaceHolder) {
            visible = false
            h.removeCallbacks(draw)
            super.onSurfaceDestroyed(holder)
        }

        override fun onTouchEvent(event: MotionEvent) {
            if (event.actionMasked == MotionEvent.ACTION_DOWN && ripples.size < 6) {
                ripples.add(floatArrayOf(event.x, event.y, SystemClock.uptimeMillis().toFloat()))
            }
        }

        override fun onSharedPreferenceChanged(sp: SharedPreferences?, key: String?) {
            if (key != Prefs.THEME) return
            t = Prefs.theme(this@AscendWallpaper)
            shaders()
            if (Build.VERSION.SDK_INT >= 27) notifyColorsChanged()
            frame()
        }

        override fun onComputeColors(): WallpaperColors? {
            if (Build.VERSION.SDK_INT < 27) return null
            if (Build.VERSION.SDK_INT >= 31) {
                val hints = if (t.light) WallpaperColors.HINT_SUPPORTS_DARK_TEXT else WallpaperColors.HINT_SUPPORTS_DARK_THEME
                return WallpaperColors(Color.valueOf(t.accent), Color.valueOf(t.bg), Color.valueOf(t.accentSoft), hints)
            }
            return WallpaperColors(Color.valueOf(t.accent), Color.valueOf(t.bg), Color.valueOf(t.accentSoft))
        }

        private fun seed() {
            particles.clear()
            repeat(48) { particles.add(newParticle(rnd.nextFloat() * ht)) }
        }

        private fun newParticle(y: Float) = P(
            rnd.nextFloat() * w, y,
            (0.25f + rnd.nextFloat() * 0.9f) * d,
            (0.8f + rnd.nextFloat() * 2.2f) * d,
            rnd.nextFloat() * 6.28f
        )

        private fun shaders() {
            if (w == 0) return
            glow.shader = RadialGradient(
                w * 0.5f, ht * 0.3f, w * 1.0f,
                intArrayOf(t.accentSoft.withAlpha(if (t.light) 80 else 120), t.accent.withAlpha(28), Color.TRANSPARENT),
                floatArrayOf(0f, 0.45f, 1f), Shader.TileMode.CLAMP
            )
        }

        private fun frame() {
            if (w == 0) return
            val holder = surfaceHolder
            var c: Canvas? = null
            try {
                c = holder.lockHardwareCanvas()
                if (c != null) render(c)
            } catch (_: Exception) {
            } finally {
                if (c != null) try { holder.unlockCanvasAndPost(c) } catch (_: Exception) { }
            }
            h.removeCallbacks(draw)
            if (visible) h.postDelayed(draw, 33)
        }

        private fun render(c: Canvas) {
            val now = SystemClock.uptimeMillis()
            val time = (now - start) / 1000f
            val wf = w.toFloat()
            val hf = ht.toFloat()
            c.drawColor(t.bg)
            c.drawRect(0f, 0f, wf, hf, glow)

            // Receding grid in the lower part of the screen.
            val horizon = hf * 0.64f
            val step = 46f * d
            val scroll = (time * 14f * d) % step
            var y = horizon + step - scroll
            while (y < hf) {
                val a = ((y - horizon) / (hf - horizon)).coerceIn(0f, 1f)
                line.color = t.accent.withAlpha((a * 46).toInt())
                c.drawLine(0f, y, wf, y, line)
                y += step
            }
            for (k in -9..9) {
                line.color = t.accent.withAlpha(22)
                c.drawLine(wf / 2f + k * step * 0.35f, horizon, wf / 2f + k * step * 1.7f, hf, line)
            }
            line.color = t.accent.withAlpha(60)
            c.drawLine(0f, horizon, wf, horizon, line)

            // Slow scan line.
            val sy = (time * 50f * d) % (hf + 200f * d) - 100f * d
            line.color = t.accent.withAlpha(16)
            c.drawRect(0f, sy, wf, sy + 2f * d, line)

            // Rising particles.
            for (p in particles) {
                p.y -= p.v
                p.x += sin(time * 0.6f + p.ph) * 0.25f * d
                if (p.y < -10f * d) {
                    val n = newParticle(hf + 10f * d)
                    p.x = n.x; p.y = n.y; p.v = n.v; p.r = n.r
                }
                val tw = 0.55f + 0.45f * sin(time * 2f + p.ph)
                dot.color = t.accent.withAlpha((38 * tw).toInt())
                c.drawCircle(p.x, p.y, p.r * 3.4f, dot)
                dot.color = (if (p.r > 2.3f * d) t.text else t.accent).withAlpha((215 * tw).toInt())
                c.drawCircle(p.x, p.y, p.r, dot)
            }

            // Touch ripples.
            val it = ripples.iterator()
            while (it.hasNext()) {
                val r = it.next()
                val age = (now - r[2]) / 1100f
                if (age >= 1f) { it.remove(); continue }
                ring.color = t.accent.withAlpha(((1f - age) * 170).toInt())
                c.drawCircle(r[0], r[1], age * wf * 0.55f, ring)
            }
        }
    }
}
