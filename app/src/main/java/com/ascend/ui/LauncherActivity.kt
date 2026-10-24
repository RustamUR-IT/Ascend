package com.ascend.ui

import android.app.Activity
import android.app.ActivityOptions
import android.app.WallpaperManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Rect
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.RippleDrawable
import android.net.Uri
import android.os.BatteryManager
import android.os.Bundle
import android.provider.Settings
import android.text.Editable
import android.text.InputType
import android.text.TextUtils
import android.text.TextWatcher
import android.text.format.DateFormat
import android.view.GestureDetector
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.animation.DecelerateInterpolator
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.BaseAdapter
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.GridView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.PopupMenu
import android.widget.TextView
import android.widget.Toast
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import kotlin.math.abs

/** The Ascend home screen: clock, status window, dock and app drawer. */
class LauncherActivity : Activity() {
    private lateinit var t: AscendTheme
    private lateinit var root: FrameLayout
    private lateinit var home: LinearLayout
    private lateinit var drawer: LinearLayout
    private lateinit var clock: TextView
    private lateinit var date: TextView
    private lateinit var energyBar: SegBar
    private lateinit var energyVal: TextView
    private lateinit var dayBar: SegBar
    private lateinit var dayVal: TextView
    private lateinit var alertsVal: TextView
    private lateinit var dockRow: LinearLayout
    private lateinit var search: EditText
    private lateinit var drawerTitle: TextView
    private lateinit var grid: GridView
    private lateinit var homeGestures: GestureDetector
    private lateinit var drawerGestures: GestureDetector

    private var apps: List<AppEntry> = emptyList()
    private var shown: List<AppEntry> = emptyList()
    private var drawerOpen = false
    private val adapter = AppAdapter()

    private val prefListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        when (key) {
            Prefs.THEME, Prefs.ICON_STYLE -> { IconStyler.clear(); recreate() }
            Prefs.DOCK -> buildDock()
        }
    }
    private val tick = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) = updateClock()
    }
    private val battery = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) = updateBattery(i)
    }
    private val packages = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) = loadApps()
    }
    private val notifCb: () -> Unit = { updateAlerts() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        t = Prefs.theme(this)
        root = FrameLayout(this)
        if (!usingOurWallpaper()) root.setBackgroundColor(t.bg.withAlpha(0x99))
        home = buildHome()
        drawer = buildDrawer()
        root.addView(home, FrameLayout.LayoutParams(MATCH, MATCH))
        root.addView(drawer, FrameLayout.LayoutParams(MATCH, MATCH))
        setContentView(root)
        edgeToEdge(window, t.light)
        root.setOnApplyWindowInsetsListener { _, ins ->
            val (top, bottom) = barInsets(ins)
            home.setPadding(dp(22), top + dp(30), dp(22), bottom + dp(10))
            drawer.setPadding(dp(14), top + dp(14), dp(14), bottom)
            ins
        }
        setupGestures()

        Prefs.sp(this).registerOnSharedPreferenceChangeListener(prefListener)
        registerSys(tick, IntentFilter().apply {
            addAction(Intent.ACTION_TIME_TICK); addAction(Intent.ACTION_TIME_CHANGED); addAction(Intent.ACTION_TIMEZONE_CHANGED)
        })
        registerSys(battery, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        registerSys(packages, IntentFilter().apply {
            addAction(Intent.ACTION_PACKAGE_ADDED); addAction(Intent.ACTION_PACKAGE_REMOVED)
            addAction(Intent.ACTION_PACKAGE_CHANGED); addDataScheme("package")
        })
        loadApps()
    }

    override fun onResume() {
        super.onResume()
        updateClock()
        updateAlerts()
        NotifyListener.addListener(notifCb)
    }

    override fun onPause() {
        super.onPause()
        NotifyListener.removeListener(notifCb)
    }

    override fun onDestroy() {
        Prefs.sp(this).unregisterOnSharedPreferenceChangeListener(prefListener)
        unregisterSafe(tick); unregisterSafe(battery); unregisterSafe(packages)
        super.onDestroy()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        if (drawerOpen) closeDrawer(true)
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (drawerOpen) closeDrawer(true)
    }

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        if (!drawerOpen) homeGestures.onTouchEvent(ev)
        return super.dispatchTouchEvent(ev)
    }

    // ---------------------------------------------------------------- home

    private fun buildHome(): LinearLayout {
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        clock = text("", 78f, t.text, Fonts.thin).apply { letterSpacing = -0.02f }
        date = hudLabel("", t, 12f)
        col.addView(clock)
        col.addView(date, lp(WRAP, WRAP).margins(top = dp(6), left = dp(4)))

        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = HudDrawable(t, resources.displayMetrics.density)
            setPadding(dp(20), dp(18), dp(20), dp(18))
        }
        val head = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        head.addView(hudLabel("◆ Status window", t), lp(0, WRAP).weighted())
        head.addView(text(t.name.uppercase(), 10f, t.textDim, Fonts.mono, 0.15f))
        panel.addView(head)

        energyBar = SegBar(this, t); energyVal = text("--", 11.5f, t.text, Fonts.mono)
        dayBar = SegBar(this, t); dayVal = text("--", 11.5f, t.text, Fonts.mono)
        alertsVal = text("--", 11.5f, t.accent, Fonts.mono)
        panel.addView(statRow("ENERGY", energyBar, energyVal), lp(MATCH, WRAP).margins(top = dp(16)))
        panel.addView(statRow("DAY", dayBar, dayVal), lp(MATCH, WRAP).margins(top = dp(12)))
        panel.addView(statRow("ALERTS", null, alertsVal), lp(MATCH, WRAP).margins(top = dp(12)))
        col.addView(panel, lp(MATCH, WRAP).margins(top = dp(28)))

        col.addView(View(this), lp(MATCH, 0).weighted())

        dockRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            background = HudDrawable(t, resources.displayMetrics.density, radiusDp = 22f)
            setPadding(dp(10), dp(8), dp(10), dp(8))
        }
        col.addView(dockRow, lp(MATCH, WRAP))
        val hint = text("▲  APPS", 10f, t.textDim, Fonts.mono, 0.3f).apply {
            gravity = Gravity.CENTER
            setPadding(0, dp(14), 0, dp(6))
            setOnClickListener { openDrawer() }
        }
        col.addView(hint, lp(MATCH, WRAP))
        return col
    }

    private fun statRow(label: String, bar: SegBar?, value: TextView): View {
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        row.addView(text(label, 10.5f, t.textDim, Fonts.mono, 0.12f), lp(dp(72), WRAP))
        if (bar != null) row.addView(bar, lp(0, dp(8)).weighted()) else row.addView(View(this), lp(0, 1).weighted())
        value.gravity = Gravity.END
        row.addView(value, lp(dp(56), WRAP))
        return row
    }

    private fun buildDock() {
        dockRow.removeAllViews()
        val entries = Prefs.dock(this).mapNotNull { id -> apps.firstOrNull { it.id == id } }
        if (entries.isEmpty()) {
            val msg = if (apps.isEmpty()) "LOADING…" else "LONG-PRESS AN APP IN THE DRAWER TO PIN IT HERE"
            dockRow.addView(text(msg, 10f, t.textDim, Fonts.mono, 0.08f).apply {
                gravity = Gravity.CENTER; setPadding(dp(8), dp(16), dp(8), dp(16))
            }, lp(MATCH, WRAP))
            return
        }
        for (e in entries) {
            val iv = ImageView(this).apply {
                setImageDrawable(e.styled ?: e.icon)
                setPadding(dp(9), dp(9), dp(9), dp(9))
                background = RippleDrawable(ColorStateList.valueOf(t.accent.withAlpha(70)), null, null)
                setOnClickListener { launch(e, it) }
                setOnLongClickListener { appMenu(e, it); true }
            }
            dockRow.addView(iv, lp(0, dp(64)).weighted())
        }
    }

    private fun updateClock() {
        val now = Date()
        val fmt = if (DateFormat.is24HourFormat(this)) "HH:mm" else "h:mm"
        clock.text = SimpleDateFormat(fmt, Locale.getDefault()).format(now)
        date.text = SimpleDateFormat("EEEE · d MMMM", Locale.ENGLISH).format(now).uppercase()
        val cal = Calendar.getInstance()
        val mins = cal.get(Calendar.HOUR_OF_DAY) * 60 + cal.get(Calendar.MINUTE)
        val frac = mins / 1440f
        dayBar.value = frac
        dayVal.text = "${(frac * 100).toInt()}%"
    }

    private fun updateBattery(i: Intent) {
        val level = i.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val scale = i.getIntExtra(BatteryManager.EXTRA_SCALE, 100).coerceAtLeast(1)
        if (level < 0) return
        val pct = level * 100 / scale
        val status = i.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
        val charging = status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL
        energyBar.value = pct / 100f
        energyVal.text = if (charging) "+$pct%" else "$pct%"
    }

    private fun updateAlerts() {
        alertsVal.text = if (NotifyListener.instance == null) "N/A" else NotifyListener.count().toString()
    }

    // ---------------------------------------------------------------- drawer

    private fun buildDrawer(): LinearLayout {
        val d = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(t.bg.withAlpha(0xF4))
            visibility = View.GONE
        }
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = HudDrawable(t, resources.displayMetrics.density, radiusDp = 14f, brackets = false)
            setPadding(dp(16), dp(2), dp(8), dp(2))
        }
        bar.addView(GlyphView(this, Glyph.SEARCH, t.accent), lp(dp(20), dp(20)))
        search = EditText(this).apply {
            background = null
            hint = "Search apps"
            setHintTextColor(t.textDim)
            setTextColor(t.text)
            textSize = 15f
            isSingleLine = true
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            imeOptions = EditorInfo.IME_ACTION_GO or EditorInfo.IME_FLAG_NO_EXTRACT_UI
            setPadding(dp(12), dp(14), dp(12), dp(14))
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun afterTextChanged(s: Editable?) = filter(s?.toString() ?: "")
            })
            setOnEditorActionListener { v, _, _ -> shown.firstOrNull()?.let { launch(it, v) }; true }
        }
        bar.addView(search, lp(0, WRAP).weighted())
        val close = GlyphView(this, Glyph.CLOSE, t.textDim).apply {
            setPadding(dp(8), dp(8), dp(8), dp(8))
            setOnClickListener { closeDrawer(true) }
        }
        bar.addView(close, lp(dp(40), dp(40)))
        d.addView(bar, lp(MATCH, WRAP))

        drawerTitle = hudLabel("All apps", t, 10.5f)
        d.addView(drawerTitle, lp(MATCH, WRAP).margins(top = dp(18), left = dp(6)))

        grid = GridView(this).apply {
            numColumns = 4
            verticalSpacing = dp(20)
            stretchMode = GridView.STRETCH_COLUMN_WIDTH
            selector = ColorDrawable(Color.TRANSPARENT)
            isVerticalScrollBarEnabled = false
            overScrollMode = View.OVER_SCROLL_NEVER
            clipToPadding = false
            setPadding(0, dp(14), 0, dp(28))
            adapter = this@LauncherActivity.adapter
            setOnItemClickListener { _, v, pos, _ -> shown.getOrNull(pos)?.let { launch(it, v) } }
            setOnItemLongClickListener { _, v, pos, _ -> shown.getOrNull(pos)?.let { appMenu(it, v) }; true }
            setOnTouchListener { _, ev -> drawerGestures.onTouchEvent(ev); false }
        }
        d.addView(grid, lp(MATCH, 0).weighted())
        return d
    }

    private fun filter(q: String) {
        shown = if (q.isBlank()) apps else apps.filter { it.label.contains(q.trim(), ignoreCase = true) }
        drawerTitle.text = if (q.isBlank()) "ALL APPS · ${apps.size}" else "RESULTS · ${shown.size}"
        adapter.notifyDataSetChanged()
    }

    private fun loadApps() {
        Thread {
            val list = try { AppRepo.load(this) } catch (_: Exception) { emptyList() }
            runOnUiThread {
                if (isDestroyed) return@runOnUiThread
                apps = list
                filter(if (::search.isInitialized) search.text.toString() else "")
                buildDock()
            }
        }.start()
    }

    private fun openDrawer() {
        if (drawerOpen) return
        drawerOpen = true
        drawer.visibility = View.VISIBLE
        drawer.alpha = 0f
        drawer.translationY = dpf(90f)
        drawer.animate().alpha(1f).translationY(0f).setDuration(240).setInterpolator(DecelerateInterpolator()).start()
        home.animate().alpha(0f).setDuration(160).start()
    }

    private fun closeDrawer(animate: Boolean) {
        if (!drawerOpen) return
        drawerOpen = false
        (getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager).hideSoftInputFromWindow(search.windowToken, 0)
        search.setText("")
        if (animate) {
            drawer.animate().alpha(0f).translationY(dpf(90f)).setDuration(190)
                .withEndAction { drawer.visibility = View.GONE }.start()
            home.animate().alpha(1f).setDuration(220).start()
        } else {
            drawer.visibility = View.GONE
            home.alpha = 1f
        }
        grid.setSelection(0)
    }

    // ---------------------------------------------------------------- actions

    private fun launch(e: AppEntry, v: View) {
        val i = Intent(Intent.ACTION_MAIN)
            .addCategory(Intent.CATEGORY_LAUNCHER)
            .setComponent(e.component)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
        try {
            startActivity(i, ActivityOptions.makeScaleUpAnimation(v, 0, 0, v.width, v.height).toBundle())
        } catch (ex: Exception) {
            Toast.makeText(this, "Couldn't open ${e.label}", Toast.LENGTH_SHORT).show()
        }
        if (drawerOpen) root.postDelayed({ closeDrawer(false) }, 400)
    }

    private fun appMenu(e: AppEntry, v: View) {
        v.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
        val pinned = Prefs.dock(this).contains(e.id)
        PopupMenu(this, v).apply {
            menu.add(0, 1, 0, if (pinned) "Unpin from dock" else "Pin to dock")
            menu.add(0, 2, 1, "App info")
            menu.add(0, 3, 2, "Uninstall")
            setOnMenuItemClickListener { item ->
                val pkgUri = Uri.fromParts("package", e.component.packageName, null)
                when (item.itemId) {
                    1 -> togglePin(e)
                    2 -> safeStart(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, pkgUri))
                    3 -> safeStart(Intent(Intent.ACTION_DELETE, pkgUri))
                }
                true
            }
            show()
        }
    }

    private fun togglePin(e: AppEntry) {
        val list = Prefs.dock(this)
        if (list.contains(e.id)) list.remove(e.id)
        else {
            if (list.size >= 5) {
                Toast.makeText(this, "The dock holds 5 apps. Unpin one first.", Toast.LENGTH_SHORT).show()
                return
            }
            list.add(e.id)
        }
        Prefs.setDock(this, list)
    }

    private fun safeStart(i: Intent) {
        try { startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } catch (_: Exception) { }
    }

    private fun openShade() {
        val svc = ShadeService.instance
        if (svc != null) {
            if (Prefs.on(this, Prefs.SHADE)) svc.openShade()
            else svc.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_NOTIFICATIONS)
            return
        }
        // Fallback: ask the system to pull down its own notification panel.
        try {
            val sbm = getSystemService("statusbar")
            sbm?.javaClass?.getMethod("expandNotificationsPanel")?.invoke(sbm)
        } catch (_: Exception) { }
    }

    private fun usingOurWallpaper(): Boolean = try {
        WallpaperManager.getInstance(this).wallpaperInfo?.packageName == packageName
    } catch (_: Exception) { false }

    private fun inside(v: View, e: MotionEvent): Boolean {
        val r = Rect()
        if (!v.getGlobalVisibleRect(r)) return false
        return r.contains(e.rawX.toInt(), e.rawY.toInt())
    }

    private fun setupGestures() {
        homeGestures = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onFling(e1: MotionEvent?, e2: MotionEvent, vx: Float, vy: Float): Boolean {
                val dy = e2.y - (e1?.y ?: e2.y)
                if (abs(vy) < 900f || abs(dy) < dpf(60f) || abs(vx) > abs(vy)) return false
                if (dy < 0) openDrawer() else openShade()
                return true
            }

            override fun onDoubleTap(e: MotionEvent): Boolean {
                val svc = ShadeService.instance ?: return false
                if (inside(dockRow, e)) return false
                svc.lock()
                return true
            }

            override fun onLongPress(e: MotionEvent) {
                if (inside(dockRow, e)) return
                root.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                startActivity(Intent(this@LauncherActivity, MainActivity::class.java))
            }
        })
        drawerGestures = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onFling(e1: MotionEvent?, e2: MotionEvent, vx: Float, vy: Float): Boolean {
                if (vy > 1200f && !grid.canScrollVertically(-1)) { closeDrawer(true); return true }
                return false
            }
        })
    }

    private inner class AppAdapter : BaseAdapter() {
        override fun getCount() = shown.size
        override fun getItem(position: Int): Any = shown[position]
        override fun getItemId(position: Int) = position.toLong()

        override fun getView(position: Int, convertView: View?, parent: ViewGroup?): View {
            val cell = (convertView as? LinearLayout) ?: LinearLayout(this@LauncherActivity).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER_HORIZONTAL
                addView(ImageView(context), LinearLayout.LayoutParams(dp(52), dp(52)))
                addView(text("", 11.5f, t.text).apply {
                    maxLines = 1
                    ellipsize = TextUtils.TruncateAt.END
                    gravity = Gravity.CENTER
                }, lp(MATCH, WRAP).margins(top = dp(7), left = dp(2), right = dp(2)))
            }
            val e = shown[position]
            (cell.getChildAt(0) as ImageView).setImageDrawable(e.styled ?: e.icon)
            (cell.getChildAt(1) as TextView).text = e.label
            return cell
        }
    }
}
