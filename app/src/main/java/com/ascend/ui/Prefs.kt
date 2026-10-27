package com.ascend.ui

import android.content.Context
import android.content.SharedPreferences
import android.graphics.Color

/** A full colour scheme. Every Ascend surface (launcher, keyboard, shade, popups, wallpaper) reads from this. */
data class AscendTheme(
    val id: String,
    val name: String,
    val tagline: String,
    val bg: Int,
    val surface: Int,
    val surfaceHi: Int,
    val accent: Int,
    val accentSoft: Int,
    val text: Int,
    val textDim: Int,
    val light: Boolean = false
) {
    /** Colour for text drawn on top of the accent colour. */
    val onAccent: Int get() = if (luminance(accent) > 0.6f) Color.BLACK else Color.WHITE
}

fun luminance(c: Int): Float =
    (0.299f * Color.red(c) + 0.587f * Color.green(c) + 0.114f * Color.blue(c)) / 255f

fun Int.withAlpha(a: Int): Int = (this and 0x00FFFFFF) or (a.coerceIn(0, 255) shl 24)

object Themes {
    private fun c(s: String) = Color.parseColor(s)

    val all = listOf(
        AscendTheme(
            "monarch", "Monarch", "Black · violet · white",
            bg = c("#050308"), surface = c("#100B19"), surfaceHi = c("#1B1430"),
            accent = c("#A855F7"), accentSoft = c("#6D28D9"),
            text = c("#F5F3FF"), textDim = c("#9C93B5")
        ),
        AscendTheme(
            "monarch_light", "Monarch Light", "White · violet · black",
            bg = c("#F8F6FD"), surface = c("#FFFFFF"), surfaceHi = c("#EEE8FB"),
            accent = c("#7C3AED"), accentSoft = c("#C4B5FD"),
            text = c("#0B0612"), textDim = c("#5E5673"), light = true
        ),
        AscendTheme(
            "awakened", "Awakened", "Black · electric blue · white",
            bg = c("#02060C"), surface = c("#08121F"), surfaceHi = c("#0E1D31"),
            accent = c("#38BDF8"), accentSoft = c("#0369A1"),
            text = c("#F0F9FF"), textDim = c("#88A1B8")
        ),
        AscendTheme(
            "red_gate", "Red Gate", "Black · crimson · white",
            bg = c("#080203"), surface = c("#16080A"), surfaceHi = c("#241014"),
            accent = c("#F43F5E"), accentSoft = c("#9F1239"),
            text = c("#FFF1F2"), textDim = c("#B5929A")
        ),
        AscendTheme(
            "shadow", "Shadow", "Black · graphite · white",
            bg = c("#000000"), surface = c("#0E0E10"), surfaceHi = c("#1A1A1D"),
            accent = c("#E5E7EB"), accentSoft = c("#52525B"),
            text = c("#FFFFFF"), textDim = c("#8E8E96")
        )
    )

    fun byId(id: String?): AscendTheme = all.firstOrNull { it.id == id } ?: all[0]
}

object Prefs {
    const val THEME = "theme"
    const val POPUPS = "popups"
    const val EDGE_GLOW = "edge_glow"
    const val CHARGE_GLOW = "charge_glow"
    const val HUD = "hud_frame"
    const val VOLUME = "volume_panel"
    const val SHADE = "shade"
    const val SHADE_ZONE = "shade_zone"   // 0 left half, 1 right half, 2 full width
    const val UNLOCK = "unlock_splash"
    const val ICON_STYLE = "icon_style"   // 0 line, 1 orb, 2 original
    const val KB_LAYOUT = "kb_layout"     // 0 QWERTY, 1 QWERTZ
    const val KB_HAPTIC = "kb_haptic"
    const val KB_NUMROW = "kb_numrow"
    const val KB_SUGGEST = "kb_suggest"
    const val GLASS = "glass"
    const val DOCK = "dock"

    /** Defaults for boolean settings. */
    private val defaults = mapOf(
        POPUPS to true, EDGE_GLOW to true, CHARGE_GLOW to true, HUD to false,
        VOLUME to true, SHADE to true, UNLOCK to true, KB_HAPTIC to true, KB_NUMROW to true,
        KB_SUGGEST to true, GLASS to true
    )

    fun sp(ctx: Context): SharedPreferences =
        ctx.applicationContext.getSharedPreferences("ascend", Context.MODE_PRIVATE)

    fun theme(ctx: Context): AscendTheme = Themes.byId(sp(ctx).getString(THEME, null))

    fun on(ctx: Context, key: String): Boolean = sp(ctx).getBoolean(key, defaults[key] ?: false)
    fun int(ctx: Context, key: String, def: Int = 0): Int = sp(ctx).getInt(key, def)

    fun put(ctx: Context, key: String, v: Boolean) = sp(ctx).edit().putBoolean(key, v).apply()
    fun put(ctx: Context, key: String, v: Int) = sp(ctx).edit().putInt(key, v).apply()
    fun put(ctx: Context, key: String, v: String) = sp(ctx).edit().putString(key, v).apply()

    fun dock(ctx: Context): MutableList<String> =
        (sp(ctx).getString(DOCK, "") ?: "").split('|').filter { it.isNotBlank() }.toMutableList()

    fun setDock(ctx: Context, ids: List<String>) = put(ctx, DOCK, ids.joinToString("|"))

    fun cycleTheme(ctx: Context): AscendTheme {
        val i = Themes.all.indexOf(theme(ctx))
        val next = Themes.all[(i + 1) % Themes.all.size]
        put(ctx, THEME, next.id)
        return next
    }
}
