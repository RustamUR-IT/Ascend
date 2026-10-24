package com.ascend.ui

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffColorFilter
import android.graphics.Rect
import android.graphics.drawable.AdaptiveIconDrawable
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.os.Build
import java.util.concurrent.ConcurrentHashMap

data class AppEntry(val label: String, val component: ComponentName, val icon: Drawable) {
    val id: String get() = component.flattenToShortString()
    @Volatile var styled: Drawable? = null
}

object AppRepo {
    /** Loads every launchable app and pre-renders its themed icon (call off the main thread). */
    fun load(ctx: Context): List<AppEntry> {
        val pm = ctx.packageManager
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val t = Prefs.theme(ctx)
        val style = Prefs.int(ctx, Prefs.ICON_STYLE, 0)
        val size = ctx.dp(52)
        return pm.queryIntentActivities(intent, 0)
            .map { ri ->
                AppEntry(
                    ri.loadLabel(pm).toString(),
                    ComponentName(ri.activityInfo.packageName, ri.activityInfo.name),
                    ri.loadIcon(pm)
                )
            }
            .distinctBy { it.id }
            .sortedBy { it.label.lowercase() }
            .onEach { e ->
                e.styled = try { IconStyler.style(ctx, e.id, e.icon, t, style, size) } catch (_: Exception) { e.icon }
            }
    }
}

/**
 * Turns any app icon into a theme-matched icon.
 * Style 0 "Line": the app's monochrome glyph (Android 13+ themed icon layer) in the theme's text colour.
 * Style 1 "Orb": the glyph in the accent colour inside a dark ringed circle.
 * Style 2: original icon.
 * Apps without a monochrome layer fall back to a tinted, desaturated version of their icon.
 */
object IconStyler {
    private val cache = ConcurrentHashMap<String, Drawable>()

    fun clear() = cache.clear()

    fun style(ctx: Context, key: String, src: Drawable, t: AscendTheme, style: Int, sizePx: Int): Drawable {
        if (style == 2) return src
        val k = "$key|${t.id}|$style|$sizePx"
        cache[k]?.let { return it }

        val bmp = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val glyphColor = if (style == 0) t.text else t.accent
        var inset = 0
        if (style == 1) {
            val p = Paint(Paint.ANTI_ALIAS_FLAG)
            val half = sizePx / 2f
            p.color = t.surfaceHi
            c.drawCircle(half, half, half - ctx.dpf(1.5f), p)
            p.style = Paint.Style.STROKE
            p.strokeWidth = ctx.dpf(1.3f)
            p.color = t.accent.withAlpha(170)
            c.drawCircle(half, half, half - ctx.dpf(1.5f), p)
            inset = (sizePx * 0.2f).toInt()
        }
        val area = Rect(inset, inset, sizePx - inset, sizePx - inset)

        val mono: Drawable? = if (Build.VERSION.SDK_INT >= 33 && src is AdaptiveIconDrawable) src.monochrome else null
        if (mono != null) {
            val m = mono.constantState?.newDrawable()?.mutate() ?: mono.mutate()
            // Adaptive layers are 108dp with the visible art in the middle 72dp, so scale up by 1.5×.
            val ext = area.width() / 4
            m.setBounds(area.left - ext, area.top - ext, area.right + ext, area.bottom + ext)
            m.colorFilter = PorterDuffColorFilter(glyphColor, PorterDuff.Mode.SRC_IN)
            c.save()
            c.clipRect(area)
            m.draw(c)
            c.restore()
        } else {
            val d = src.constantState?.newDrawable()?.mutate() ?: src.mutate()
            val pad = if (style == 1) 0 else (sizePx * 0.05f).toInt()
            d.setBounds(area.left + pad, area.top + pad, area.right - pad, area.bottom - pad)
            d.colorFilter = ColorMatrixColorFilter(tintMatrix(glyphColor))
            d.draw(c)
        }
        val out: Drawable = BitmapDrawable(ctx.resources, bmp)
        cache[k] = out
        return out
    }

    /** Greyscale the icon, then map brightness onto the target colour. */
    private fun tintMatrix(color: Int): ColorMatrix {
        val r = android.graphics.Color.red(color) / 255f
        val g = android.graphics.Color.green(color) / 255f
        val b = android.graphics.Color.blue(color) / 255f
        val lr = 0.299f; val lg = 0.587f; val lb = 0.114f
        return ColorMatrix(
            floatArrayOf(
                lr * r, lg * r, lb * r, 0f, 0f,
                lr * g, lg * g, lb * g, 0f, 0f,
                lr * b, lg * b, lb * b, 0f, 0f,
                0f, 0f, 0f, 1f, 0f
            )
        )
    }
}
