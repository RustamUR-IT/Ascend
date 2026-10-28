package com.ascend.ui

import android.app.Activity
import android.app.NotificationManager
import android.app.StatusBarManager
import android.app.WallpaperManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.Icon
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.inputmethod.InputMethodManager
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast

/** Ascend's control centre: pick a theme, switch modules on, grant permissions, preview effects. */
class MainActivity : Activity() {
    private lateinit var t: AscendTheme
    private lateinit var scroll: ScrollView
    private lateinit var content: LinearLayout

    private val prefListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key == Prefs.THEME) recreate()
    }

    private class Btn(val label: String, val primary: Boolean, val action: () -> Unit)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        t = Prefs.theme(this)
        scroll = ScrollView(this).apply {
            setBackgroundColor(t.bg)
            isFillViewport = true
            isVerticalScrollBarEnabled = false
        }
        content = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        scroll.addView(content)
        val frame = android.widget.FrameLayout(this)
        frame.addView(scroll, android.widget.FrameLayout.LayoutParams(MATCH, MATCH))
        val scrim = View(this).apply { setBackgroundColor(t.bg.withAlpha(0xF2)) }
        frame.addView(scrim, android.widget.FrameLayout.LayoutParams(MATCH, 0))
        setContentView(frame)
        window.decorView.setBackgroundColor(t.bg)
        edgeToEdge(window, t.light)
        frame.setOnApplyWindowInsetsListener { _, ins ->
            val (top, bottom) = barInsets(ins)
            scrim.layoutParams = scrim.layoutParams.apply { height = top }
            content.setPadding(dp(18), top + dp(22), dp(18), bottom + dp(30))
            ins
        }
        Prefs.sp(this).registerOnSharedPreferenceChangeListener(prefListener)
    }

    override fun onDestroy() {
        Prefs.sp(this).unregisterOnSharedPreferenceChangeListener(prefListener)
        super.onDestroy()
    }

    override fun onResume() {
        super.onResume()
        build()
    }

    // ---------------------------------------------------------------- status checks

    private fun isDefaultHome(): Boolean {
        val i = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
        return packageManager.resolveActivity(i, PackageManager.MATCH_DEFAULT_ONLY)?.activityInfo?.packageName == packageName
    }

    private fun imm() = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
    private fun kbEnabled() = imm().enabledInputMethodList.any { it.packageName == packageName }
    private fun kbSelected() =
        Settings.Secure.getString(contentResolver, Settings.Secure.DEFAULT_INPUT_METHOD)?.startsWith("$packageName/") == true

    private fun notifAccess(): Boolean {
        if (Build.VERSION.SDK_INT >= 27) {
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            return nm.isNotificationListenerAccessGranted(ComponentName(this, NotifyListener::class.java))
        }
        return Settings.Secure.getString(contentResolver, "enabled_notification_listeners")?.contains(packageName) == true
    }

    private fun overlay() = Settings.canDrawOverlays(this)
    private fun accessibility() = ShadeService.instance != null ||
        Settings.Secure.getString(contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)?.contains("$packageName/") == true

    private fun ourWallpaper() = try {
        WallpaperManager.getInstance(this).wallpaperInfo?.packageName == packageName
    } catch (_: Exception) { false }

    // ---------------------------------------------------------------- page

    private fun build() {
        val y = scroll.scrollY
        content.removeAllViews()

        val home = isDefaultHome()
        val kb = kbEnabled() && kbSelected()
        val notif = notifAccess() && (overlay() || accessibility())
        val shade = accessibility()
        val wall = ourWallpaper()
        val online = listOf(home, kb, notif, shade, wall).count { it }

        // Header
        content.addView(text("ASCEND", 40f, t.text, Fonts.thin, 0.32f))
        content.addView(hudLabel("System interface control", t, 11f), lp(WRAP, WRAP).margins(top = dp(6), left = dp(3)))
        val statusRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        statusRow.addView(SegBar(this, t, 5).apply { value = online / 5f }, lp(dp(120), dp(7)))
        statusRow.addView(text("$online / 5 MODULES ONLINE", 10.5f, t.textDim, Fonts.mono, 0.12f), lp(WRAP, WRAP).margins(left = dp(12)))
        content.addView(statusRow, lp(MATCH, WRAP).margins(top = dp(18), left = dp(3)))

        crashCard()

        themes()

        section("Modules")

        card(
            "Home screen", home,
            "Three pages: Monarch minimal, Status window (level, XP, daily quests) and Gate grid (weather, calendar, music). " +
                "Swipe sideways between pages, up for all apps, down for the shade. Double-tap empty space to lock, long-press it to open this screen."
        ) {
            buttons(Btn(if (home) "Change home app" else "Set as home app", !home) { open(Intent(Settings.ACTION_HOME_SETTINGS)) })
            choice("Icon style", listOf("Line", "Orb", "Original"), Prefs.ICON_STYLE, 0)
        }

        card(
            "Keyboard", kb,
            "Appears in every app you type in, including WhatsApp and Chrome. Long-press letters for ä ö ü ß é and more, " +
                "long-press space to switch keyboards, long-press ☺ to jump to your previous keyboard (for GIFs and voice typing)."
        ) {
            val enabled = kbEnabled()
            buttons(
                Btn(if (enabled) "Enabled ✓" else "1 · Enable", !enabled) { open(Intent(Settings.ACTION_INPUT_METHOD_SETTINGS)) },
                Btn("2 · Switch to it", enabled && !kbSelected()) { imm().showInputMethodPicker() }
            )
            choice("Layout", listOf("QWERTY", "QWERTZ"), Prefs.KB_LAYOUT, 1)
            toggle("Word suggestions", "German and English, learns the words you type. Off in password fields.", Prefs.KB_SUGGEST)
            toggle("Number row", null, Prefs.KB_NUMROW)
            toggle("Vibrate on key press", "Follows your system vibration setting", Prefs.KB_HAPTIC)
            buttons(Btn("Forget learned words", false) {
                Suggest.forgetLearned(this@MainActivity)
                toast("Learned words cleared")
            })
        }

        card(
            "Notifications", notif,
            "New notifications slide in as themed notices with an edge glow. To avoid seeing two pop-ups, " +
                "turn off \"Pop on screen\" for apps like WhatsApp in Settings → Notifications."
        ) {
            req("Notification access", notifAccess())
            req("Display over other apps (or the Shade module)", overlay() || accessibility())
            buttons(
                Btn("Notification access", !notifAccess()) { openNotifAccess() },
                Btn("Overlay permission", !overlay() && !accessibility()) {
                    open(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
                }
            )
            toggle("Themed pop-ups", null, Prefs.POPUPS)
            toggle("Edge glow", "The screen edge lights up for new notifications", Prefs.EDGE_GLOW)
            toggle("Charging glow", "Edge glow when you plug in (needs the Shade module)", Prefs.CHARGE_GLOW)
            buttons(
                Btn("Preview notice", false) {
                    if (Overlay.host(this@MainActivity) == null) toast("Grant the overlay permission first")
                    else PopupManager.show(this@MainActivity, Notice.sample(this@MainActivity))
                },
                Btn("Preview glow", false) {
                    if (Overlay.host(this@MainActivity) == null) toast("Grant the overlay permission first")
                    else EdgeGlow.flash(this@MainActivity)
                }
            )
        }

        card(
            "Shade & volume", shade,
            "Swipe down from the top edge for a themed shade with toggles, brightness, media controls and notifications. " +
                "Also replaces the volume pop-up and powers the HUD frame and unlock animation. " +
                "This uses Android's accessibility permission; Ascend does not read what's on your screen."
        ) {
            req("Accessibility service", accessibility())
            req("Modify system settings (brightness, rotation)", Settings.System.canWrite(this@MainActivity))
            buttons(
                Btn("Accessibility", !accessibility()) { open(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) },
                Btn("Brightness access", !Settings.System.canWrite(this@MainActivity)) {
                    open(Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS, Uri.parse("package:$packageName")))
                }
            )
            if (!accessibility()) {
                addView(text(
                    "If the Ascend switch in Accessibility is greyed out: open App info below, tap ⋮ (top right) → " +
                        "\"Allow restricted settings\", then try again.",
                    12.5f, t.textDim
                ).apply { setLineSpacing(0f, 1.2f) }, lp(MATCH, WRAP).margins(top = dp(12)))
                buttons(Btn("App info", false) {
                    open(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", packageName, null)))
                })
            }
            toggle("Custom shade", null, Prefs.SHADE)
            toggle(
                "Glass effect",
                if (Overlay.canBlur(this@MainActivity)) "Blurs the screen behind the shade"
                else "Needs Android 12+ with window blur on (Developer options → Allow window-level blurs)",
                Prefs.GLASS
            )
            choice("Swipe zone on the status bar", listOf("Left half", "Right half", "Full width"), Prefs.SHADE_ZONE, 1)
            addView(text("Swiping the other half still opens Nothing's own panel.", 12f, t.textDim),
                lp(MATCH, WRAP).margins(top = dp(6)))
            toggle("Volume panel", "Volume keys show the Ascend slider (calls and camera are left alone)", Prefs.VOLUME)
            toggle("HUD frame", "Corner brackets around the whole screen", Prefs.HUD)
            toggle("Unlock animation", "\"System online\" flash when you unlock", Prefs.UNLOCK)
            buttons(Btn("Open shade", false) {
                val s = ShadeService.instance
                if (s == null) toast("Turn on the Ascend accessibility service first") else s.openShade()
            })
        }

        card(
            "Live wallpaper", wall,
            "Animated particle field in your theme colours. Android takes its system accent colour from the wallpaper, " +
                "so quick settings, Chrome's toolbar and Google apps pick up your theme too."
        ) {
            buttons(Btn(if (wall) "Wallpaper active ✓" else "Set wallpaper", !wall) {
                open(Intent(WallpaperManager.ACTION_CHANGE_LIVE_WALLPAPER).putExtra(
                    WallpaperManager.EXTRA_LIVE_WALLPAPER_COMPONENT, ComponentName(this@MainActivity, AscendWallpaper::class.java)
                ))
            })
        }

        card(
            "Quick settings tile", null,
            "Adds an Ascend tile to Nothing's quick settings. Tap it to cycle themes from anywhere."
        ) {
            if (Build.VERSION.SDK_INT >= 33) {
                buttons(Btn("Add tile", true) { requestTile() })
            } else {
                addView(text("Open quick settings → edit (pencil) and drag the Ascend tile in.", 12.5f, t.textDim),
                    lp(MATCH, WRAP).margins(top = dp(12)))
            }
        }

        section("Everywhere else")
        tips()

        content.addView(text("Ascend 1.2 · no root · only weather goes online", 10f, t.textDim, Fonts.mono, 0.08f).apply {
            gravity = Gravity.CENTER
        }, lp(MATCH, WRAP).margins(top = dp(30)))

        scroll.post { scroll.scrollTo(0, y) }
    }

    /** Shown only after a crash: lets the user send the error text to whoever fixes the app. */
    private fun crashCard() {
        val log = CrashLog.read(this) ?: return
        card("Last crash", null, "Ascend crashed. Share this report so it can be fixed.") {
            addView(text(log.lineSequence().drop(4).take(6).joinToString("\n"), 11f, t.textDim, Fonts.regular).apply {
                typeface = android.graphics.Typeface.MONOSPACE
                setPadding(dp(12), dp(10), dp(12), dp(10))
                background = roundRect(t.bg, dpf(10f), t.accent.withAlpha(0x40), dp(1))
            }, lp(MATCH, WRAP).margins(top = dp(12)))
            buttons(
                Btn("Share report", true) {
                    val send = Intent(Intent.ACTION_SEND).setType("text/plain")
                        .putExtra(Intent.EXTRA_SUBJECT, "Ascend crash report")
                        .putExtra(Intent.EXTRA_TEXT, log)
                    open(Intent.createChooser(send, "Share crash report"))
                },
                Btn("Dismiss", false) { CrashLog.clear(this@MainActivity); build() }
            )
        }
    }

    private fun themes() {
        section("Themes")
        val hs = HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false; clipToPadding = false }
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        for (th in Themes.all) {
            val sel = th.id == t.id
            val card = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(16), dp(16), dp(16), dp(16))
                background = roundRect(th.bg, dpf(16f), if (sel) th.accent else th.textDim.withAlpha(0x55), dp(if (sel) 2 else 1))
                setOnClickListener { Prefs.put(this@MainActivity, Prefs.THEME, th.id) }
            }
            val sw = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            for (col in listOf(th.accent, th.text, th.surfaceHi)) {
                sw.addView(View(this).apply {
                    background = roundRect(col, dpf(9f), th.textDim.withAlpha(0x55), dp(1))
                }, lp(dp(18), dp(18)).margins(right = dp(6)))
            }
            card.addView(sw)
            card.addView(View(this).apply {
                background = GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT, intArrayOf(th.accent, th.accent.withAlpha(0)))
            }, lp(MATCH, dp(2)).margins(top = dp(16)))
            card.addView(text(th.name, 15.5f, th.text, Fonts.medium), lp(WRAP, WRAP).margins(top = dp(12)))
            card.addView(text(th.tagline, 11f, th.textDim), lp(WRAP, WRAP).margins(top = dp(4)))
            card.addView(text(if (sel) "◆ ACTIVE" else "SELECT", 10f, if (sel) th.accent else th.textDim, Fonts.mono, 0.15f),
                lp(WRAP, WRAP).margins(top = dp(14)))
            row.addView(card, lp(dp(156), WRAP).margins(right = dp(10)))
        }
        hs.addView(row)
        content.addView(hs, lp(MATCH, WRAP).margins(top = dp(12)))
    }

    private fun tips() {
        val c = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = HudDrawable(t, resources.displayMetrics.density)
            setPadding(dp(20), dp(18), dp(20), dp(18))
        }
        c.addView(text(
            "Apps draw their own insides, so these system settings finish the look:",
            13f, t.textDim
        ).apply { setLineSpacing(0f, 1.2f) })
        val tips = listOf(
            "Settings → Display → Dark theme: On (skip for Monarch Light)",
            "Developer options → Override force-dark: On, for apps without a dark mode",
            "WhatsApp → Settings → Chats → Theme: Dark, then Default chat theme: purple",
            "Chrome → Settings → Theme: Dark; Chrome follows the wallpaper accent",
            "Nothing launcher icons, fonts and clock: set in Settings → Customisation"
        )
        for (tip in tips) {
            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            row.addView(text("◆", 9f, t.accent), lp(dp(18), WRAP).margins(top = dp(3)))
            row.addView(text(tip, 13f, t.text).apply { setLineSpacing(0f, 1.2f) }, lp(0, WRAP).weighted())
            c.addView(row, lp(MATCH, WRAP).margins(top = dp(10)))
        }
        content.addView(c, lp(MATCH, WRAP).margins(top = dp(12)))
    }

    // ---------------------------------------------------------------- building blocks

    private fun section(title: String) {
        content.addView(hudLabel(title, t, 11f), lp(MATCH, WRAP).margins(top = dp(30), left = dp(3)))
    }

    private fun card(title: String, online: Boolean?, desc: String, body: LinearLayout.() -> Unit) {
        val c = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = HudDrawable(t, resources.displayMetrics.density)
            setPadding(dp(20), dp(18), dp(20), dp(20))
        }
        val head = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        head.addView(text(title, 17.5f, t.text, Fonts.medium), lp(0, WRAP).weighted())
        if (online != null) head.addView(chip(if (online) "ONLINE" else "OFFLINE", online))
        c.addView(head)
        c.addView(text(desc, 13f, t.textDim).apply { setLineSpacing(0f, 1.25f) }, lp(MATCH, WRAP).margins(top = dp(8)))
        c.body()
        content.addView(c, lp(MATCH, WRAP).margins(top = dp(14)))
    }

    private fun chip(label: String, on: Boolean): TextView = text(label, 9.5f, if (on) t.accent else t.textDim, Fonts.mono, 0.15f).apply {
        setPadding(dp(10), dp(5), dp(10), dp(5))
        background = roundRect(
            if (on) t.accent.withAlpha(0x26) else Color.TRANSPARENT, dpf(20f),
            if (on) t.accent else t.textDim.withAlpha(0x66), dp(1)
        )
    }

    private fun LinearLayout.buttons(vararg bs: Btn) {
        val row = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
        bs.forEachIndexed { i, b ->
            val v = text(b.label.uppercase(), 11f, if (b.primary) t.onAccent else t.accent, Fonts.mono, 0.1f).apply {
                gravity = Gravity.CENTER
                setPadding(dp(8), dp(13), dp(8), dp(13))
                background = if (b.primary) roundRect(t.accent, dpf(10f))
                else roundRect(Color.TRANSPARENT, dpf(10f), t.accent.withAlpha(0x90), dp(1))
                setOnClickListener { b.action() }
            }
            row.addView(v, lp(0, WRAP).weighted().margins(left = if (i > 0) dp(8) else 0))
        }
        addView(row, lp(MATCH, WRAP).margins(top = dp(14)))
    }

    private fun LinearLayout.toggle(label: String, sub: String?, key: String) {
        val row = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        val col = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        col.addView(text(label, 14.5f, t.text))
        if (sub != null) col.addView(text(sub, 12f, t.textDim).apply { setLineSpacing(0f, 1.15f) }, lp(MATCH, WRAP).margins(top = dp(3)))
        row.addView(col, lp(0, WRAP).weighted())
        @Suppress("DEPRECATION")
        val sw = Switch(context).apply {
            isChecked = Prefs.on(this@MainActivity, key)
            val states = arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf())
            thumbTintList = ColorStateList(states, intArrayOf(t.accent, t.textDim))
            trackTintList = ColorStateList(states, intArrayOf(t.accent.withAlpha(0x70), t.surfaceHi))
            setOnCheckedChangeListener { _, v -> Prefs.put(this@MainActivity, key, v) }
        }
        row.addView(sw, lp(WRAP, WRAP).margins(left = dp(12)))
        addView(row, lp(MATCH, WRAP).margins(top = dp(16)))
    }

    private fun LinearLayout.choice(label: String, options: List<String>, key: String, def: Int) {
        addView(text(label.uppercase(), 10f, t.textDim, Fonts.mono, 0.14f), lp(MATCH, WRAP).margins(top = dp(18)))
        val row = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
        val cur = Prefs.int(this@MainActivity, key, def)
        options.forEachIndexed { i, o ->
            val sel = i == cur
            val v = text(o, 12.5f, if (sel) t.accent else t.text).apply {
                gravity = Gravity.CENTER
                setPadding(dp(4), dp(11), dp(4), dp(11))
                background = roundRect(
                    if (sel) t.accent.withAlpha(0x26) else Color.TRANSPARENT, dpf(10f),
                    if (sel) t.accent else t.textDim.withAlpha(0x55), dp(1)
                )
                setOnClickListener { Prefs.put(this@MainActivity, key, i); build() }
            }
            row.addView(v, lp(0, WRAP).weighted().margins(left = if (i > 0) dp(8) else 0))
        }
        addView(row, lp(MATCH, WRAP).margins(top = dp(8)))
    }

    private fun LinearLayout.req(label: String, ok: Boolean) {
        val row = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        row.addView(text(if (ok) "✓" else "✗", 13f, if (ok) t.accent else t.textDim, Fonts.mono), lp(dp(22), WRAP))
        row.addView(text(label, 13f, if (ok) t.text else t.textDim), lp(0, WRAP).weighted())
        addView(row, lp(MATCH, WRAP).margins(top = dp(10)))
    }

    // ---------------------------------------------------------------- intents

    private fun open(i: Intent) {
        try {
            startActivity(i)
        } catch (_: Exception) {
            try { startActivity(Intent(Settings.ACTION_SETTINGS)) } catch (_: Exception) { }
        }
    }

    private fun openNotifAccess() {
        if (Build.VERSION.SDK_INT >= 30) {
            try {
                startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_DETAIL_SETTINGS).putExtra(
                    Settings.EXTRA_NOTIFICATION_LISTENER_COMPONENT_NAME,
                    ComponentName(this, NotifyListener::class.java).flattenToString()
                ))
                return
            } catch (_: Exception) { }
        }
        open(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
    }

    private fun requestTile() {
        if (Build.VERSION.SDK_INT < 33) return
        try {
            val sbm = getSystemService(StatusBarManager::class.java)
            sbm.requestAddTileService(
                ComponentName(this, ThemeTile::class.java), "Ascend",
                Icon.createWithResource(this, R.drawable.ic_tile), mainExecutor
            ) { result ->
                runOnUiThread {
                    when (result) {
                        StatusBarManager.TILE_ADD_REQUEST_RESULT_TILE_ADDED -> toast("Tile added")
                        StatusBarManager.TILE_ADD_REQUEST_RESULT_TILE_ALREADY_ADDED -> toast("Tile is already in quick settings")
                        else -> {}
                    }
                }
            }
        } catch (_: Exception) {
            toast("Open quick settings → edit and drag the Ascend tile in")
        }
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
}
