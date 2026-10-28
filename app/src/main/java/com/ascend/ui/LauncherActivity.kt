package com.ascend.ui

import android.Manifest
import android.app.Activity
import android.app.ActivityOptions
import android.app.AlertDialog
import android.content.BroadcastReceiver
import android.content.ComponentName
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
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.text.Editable
import android.text.InputType
import android.text.TextUtils
import android.text.TextWatcher
import android.view.GestureDetector
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.animation.DecelerateInterpolator
import android.view.animation.OvershootInterpolator
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
import kotlin.math.abs

/**
 * The Ascend home screen: three swipeable pages (Monarch minimal · Status window · Gate grid),
 * a shared dock and the app drawer.
 */
class LauncherActivity : Activity(), HomeHost {
    private lateinit var t: AscendTheme
    private lateinit var root: FrameLayout
    private lateinit var home: LinearLayout
    private lateinit var pager: Pager
    private lateinit var pageMinimal: MinimalPage
    private lateinit var pageStatus: StatusPage
    private lateinit var pageGrid: GridPage
    private lateinit var dots: LinearLayout
    private lateinit var dockRow: LinearLayout
    private lateinit var drawer: LinearLayout
    private lateinit var search: EditText
    private lateinit var drawerTitle: TextView
    private lateinit var grid: GridView
    private lateinit var homeGestures: GestureDetector
    private lateinit var drawerGestures: GestureDetector

    private val h = Handler(Looper.getMainLooper())
    private var apps: List<AppEntry> = emptyList()
    private var shown: List<AppEntry> = emptyList()
    private var letter: String? = null
    private var drawerOpen = false
    private var lastLevel = -1
    private val adapter = AppAdapter()
    private val refreshSoon = Runnable { refresh() }

    private val prefListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        when (key) {
            Prefs.THEME, Prefs.ICON_STYLE -> { IconStyler.clear(); recreate() }
            Prefs.DOCK -> refresh()
        }
    }
    private val tick = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) = refresh()
    }
    private val packages = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) = loadApps()
    }
    private val notifCb: () -> Unit = { scheduleRefresh() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Fonts.init(this)
        t = Prefs.theme(this)
        root = FrameLayout(this)
        home = buildHome()
        drawer = buildDrawer()
        root.addView(home, FrameLayout.LayoutParams(MATCH, MATCH))
        root.addView(drawer, FrameLayout.LayoutParams(MATCH, MATCH))
        setContentView(root)
        edgeToEdge(window, t.light)
        root.setOnApplyWindowInsetsListener { _, ins ->
            val (top, bottom) = barInsets(ins)
            home.setPadding(dp(18), top + dp(6), dp(18), bottom + dp(8))
            drawer.setPadding(dp(14), top + dp(14), dp(14), bottom)
            ins
        }
        setupGestures()
        Prefs.sp(this).registerOnSharedPreferenceChangeListener(prefListener)
        registerSys(tick, IntentFilter().apply {
            addAction(Intent.ACTION_TIME_TICK); addAction(Intent.ACTION_TIME_CHANGED)
            addAction(Intent.ACTION_TIMEZONE_CHANGED); addAction(Intent.ACTION_BATTERY_CHANGED)
            addAction(Intent.ACTION_DATE_CHANGED)
        })
        registerSys(packages, IntentFilter().apply {
            addAction(Intent.ACTION_PACKAGE_ADDED); addAction(Intent.ACTION_PACKAGE_REMOVED)
            addAction(Intent.ACTION_PACKAGE_CHANGED); addDataScheme("package")
        })
        lastLevel = Player.level(Player.xp(this)).level
        loadApps()
    }

    override fun onResume() {
        super.onResume()
        refresh()
        NotifyListener.addListener(notifCb)
        Steps.start(this) { scheduleRefresh() }
        Weather.refresh(this) { refresh() }
    }

    override fun onPause() {
        super.onPause()
        NotifyListener.removeListener(notifCb)
        Steps.stop(this)
    }

    override fun onDestroy() {
        Prefs.sp(this).unregisterOnSharedPreferenceChangeListener(prefListener)
        unregisterSafe(tick); unregisterSafe(packages)
        h.removeCallbacksAndMessages(null)
        super.onDestroy()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        // Pressing Home while already home: close the drawer, otherwise return to the main page.
        if (drawerOpen) closeDrawer(true) else pager.show(1, true)
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (drawerOpen) closeDrawer(true) else pager.show(1, true)
    }

    private var gesturesBlocked = false

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        // Scrubbing the A–Z strip must not count as a swipe up/down.
        if (ev.actionMasked == MotionEvent.ACTION_DOWN) gesturesBlocked = letterBarAt(root, ev.rawX.toInt(), ev.rawY.toInt())
        if (!drawerOpen && !gesturesBlocked) homeGestures.onTouchEvent(ev)
        return super.dispatchTouchEvent(ev)
    }

    private fun letterBarAt(v: View, x: Int, y: Int): Boolean {
        val r = Rect()
        if (v.visibility != View.VISIBLE || !v.getGlobalVisibleRect(r) || !r.contains(x, y)) return false
        if (v is LetterBar) return true
        if (v is ViewGroup) for (i in 0 until v.childCount) if (letterBarAt(v.getChildAt(i), x, y)) return true
        return false
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 1) Steps.start(this) { scheduleRefresh() }
        refresh()
    }

    // ---------------------------------------------------------------- building

    private fun buildHome(): LinearLayout {
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        pager = Pager(this) { updateDots(it) }
        pageMinimal = MinimalPage(this, this).apply { build(t) }
        pageStatus = StatusPage(this, this).apply { build(t); onQuestToggled = { refresh() } }
        pageGrid = GridPage(this, this).apply { build(t) }
        for (p in listOf<View>(pageMinimal, pageStatus, pageGrid)) pager.addView(p, FrameLayout.LayoutParams(MATCH, MATCH))
        col.addView(pager, lp(MATCH, 0).weighted())

        dots = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER }
        for (i in 0 until 3) {
            dots.addView(View(this), lp(dp(7), dp(7)).margins(left = dp(5), right = dp(5)))
        }
        col.addView(dots, lp(MATCH, dp(26)))

        dockRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            background = HudDrawable(t, resources.displayMetrics.density, radiusDp = 20f)
            setPadding(dp(10), dp(6), dp(10), dp(6))
        }
        col.addView(dockRow, lp(MATCH, WRAP))
        pager.post { pager.show(1, false) }
        return col
    }

    private fun updateDots(current: Int) {
        for (i in 0 until dots.childCount) {
            val on = i == current
            dots.getChildAt(i).apply {
                background = roundRect(if (on) t.accent else 0, dpf(1.5f), if (on) t.accent else t.textDim.withAlpha(150), dp(1))
                rotation = 45f
                animate().scaleX(if (on) 1.25f else 0.85f).scaleY(if (on) 1.25f else 0.85f).setDuration(160).start()
            }
        }
    }

    private fun buildDock(favs: List<AppEntry>) {
        dockRow.removeAllViews()
        val entries = favs.take(5)
        if (entries.isEmpty()) {
            val msg = if (apps.isEmpty()) "LÄDT …" else "LANGE AUF EINE APP DRÜCKEN → ZU FAVORITEN"
            dockRow.addView(text(msg, 9.5f, t.textDim, Fonts.mono, 0.06f).apply {
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
            dockRow.addView(iv, lp(0, dp(62)).weighted())
        }
    }

    // ---------------------------------------------------------------- refresh

    private fun scheduleRefresh() {
        h.removeCallbacks(refreshSoon)
        h.postDelayed(refreshSoon, 700)
    }

    private fun refresh() {
        if (isDestroyed) return
        Player.settle(this, Steps.today(this))
        val s = homeState(this, apps)
        pageMinimal.update(s)
        pageStatus.update(s)
        pageGrid.update(s)
        buildDock(s.favorites)
        val now = Player.level(Player.xp(this)).level
        if (lastLevel in 1 until now) levelUp(now)
        lastLevel = now
    }

    /** Full-screen "LEVEL UP" flash when the player level rises. */
    private fun levelUp(level: Int) {
        val overlay = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setBackgroundColor(t.bg.withAlpha(215))
            isClickable = true
            alpha = 0f
        }
        overlay.addView(hudLabel("◆ System", t, 12f))
        overlay.addView(text("LEVEL UP", 46f, t.accent, Fonts.medium, 0.12f).apply {
            if (!t.light) setShadowLayer(dpf(22f), 0f, 0f, t.accent)
        }, lp(WRAP, WRAP).margins(top = dp(12)))
        overlay.addView(text("LV. $level", 24f, t.text, Fonts.medium), lp(WRAP, WRAP).margins(top = dp(6)))
        root.addView(overlay, FrameLayout.LayoutParams(MATCH, MATCH))
        overlay.getChildAt(1).apply { scaleX = 0.6f; scaleY = 0.6f; animate().scaleX(1f).scaleY(1f).setDuration(520).setInterpolator(OvershootInterpolator()).start() }
        overlay.animate().alpha(1f).setDuration(200).withEndAction {
            overlay.animate().alpha(0f).setStartDelay(1600).setDuration(400).withEndAction { root.removeView(overlay) }.start()
        }.start()
        overlay.setOnClickListener { root.removeView(overlay) }
        root.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
    }

    private fun loadApps() {
        Thread {
            val list = try { AppRepo.load(this) } catch (_: Exception) { emptyList() }
            runOnUiThread {
                if (isDestroyed) return@runOnUiThread
                apps = list
                filter()
                refresh()
            }
        }.start()
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
            hint = "Apps suchen · Enter für Websuche"
            setHintTextColor(t.textDim)
            setTextColor(t.text)
            textSize = 16f
            typeface = Fonts.medium
            isSingleLine = true
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            imeOptions = EditorInfo.IME_ACTION_GO or EditorInfo.IME_FLAG_NO_EXTRACT_UI
            setPadding(dp(12), dp(14), dp(12), dp(14))
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun afterTextChanged(s: Editable?) { if (!s.isNullOrEmpty()) letter = null; filter() }
            })
            setOnEditorActionListener { v, _, _ ->
                val first = shown.firstOrNull()
                val q = text.toString().trim()
                if (first != null) launch(first, v)
                else if (q.isNotEmpty()) safeStart(Intent(Intent.ACTION_WEB_SEARCH).putExtra("query", q))
                true
            }
        }
        bar.addView(search, lp(0, WRAP).weighted())
        bar.addView(GlyphView(this, Glyph.CLOSE, t.textDim).apply {
            setPadding(dp(8), dp(8), dp(8), dp(8))
            setOnClickListener { closeDrawer(true) }
        }, lp(dp(40), dp(40)))
        d.addView(bar, lp(MATCH, WRAP))

        drawerTitle = hudLabel("Alle Apps", t, 10.5f)
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

    private fun filter() {
        val q = if (::search.isInitialized) search.text.toString().trim() else ""
        val l = letter
        shown = when {
            l != null -> apps.filter { it.label.uppercase().startsWith(l) }
            q.isBlank() -> apps
            else -> apps.filter { it.label.contains(q, ignoreCase = true) }
        }
        if (::drawerTitle.isInitialized) drawerTitle.text = when {
            l != null -> "APPS · $l · ${shown.size}"
            q.isBlank() -> "ALLE APPS · ${apps.size}"
            shown.isEmpty() -> "KEINE APP · ENTER SUCHT IM WEB"
            else -> "TREFFER · ${shown.size}"
        }
        adapter.notifyDataSetChanged()
    }

    override fun openDrawer(letter: String?, search: Boolean) {
        this.letter = letter
        this.search.setText("")
        filter()
        if (!drawerOpen) {
            drawerOpen = true
            drawer.visibility = View.VISIBLE
            drawer.alpha = 0f
            drawer.translationY = dpf(90f)
            drawer.animate().alpha(1f).translationY(0f).setDuration(240).setInterpolator(DecelerateInterpolator()).start()
            home.animate().alpha(0f).setDuration(160).start()
        }
        if (search) {
            this.search.requestFocus()
            this.search.postDelayed({
                (getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager).showSoftInput(this.search, 0)
            }, 120)
        }
    }

    private fun closeDrawer(animate: Boolean) {
        if (!drawerOpen) return
        drawerOpen = false
        (getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager).hideSoftInputFromWindow(search.windowToken, 0)
        search.setText("")
        letter = null
        if (animate) {
            drawer.animate().alpha(0f).translationY(dpf(90f)).setDuration(190)
                .withEndAction { drawer.visibility = View.GONE }.start()
            home.animate().alpha(1f).setDuration(220).start()
        } else {
            drawer.visibility = View.GONE
            home.alpha = 1f
        }
        filter()
        grid.setSelection(0)
    }

    // ---------------------------------------------------------------- HomeHost

    override fun launch(e: AppEntry, v: View) {
        val i = Intent(Intent.ACTION_MAIN)
            .addCategory(Intent.CATEGORY_LAUNCHER)
            .setComponent(e.component)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
        try {
            startActivity(i, ActivityOptions.makeScaleUpAnimation(v, 0, 0, v.width, v.height).toBundle())
        } catch (ex: Exception) {
            Toast.makeText(this, "${e.label} lässt sich nicht öffnen", Toast.LENGTH_SHORT).show()
        }
        if (drawerOpen) root.postDelayed({ closeDrawer(false) }, 400)
    }

    override fun appMenu(e: AppEntry, v: View) {
        v.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
        val favs = Prefs.dock(this)
        val pinned = favs.contains(e.id)
        PopupMenu(this, v).apply {
            menu.add(0, 1, 0, if (pinned) "Aus Favoriten entfernen" else "Zu Favoriten")
            if (pinned && favs.indexOf(e.id) > 0) menu.add(0, 4, 1, "Nach vorne")
            menu.add(0, 2, 2, "App-Info")
            menu.add(0, 3, 3, "Deinstallieren")
            setOnMenuItemClickListener { item ->
                val pkgUri = Uri.fromParts("package", e.component.packageName, null)
                when (item.itemId) {
                    1 -> togglePin(e)
                    4 -> { favs.remove(e.id); favs.add(0, e.id); Prefs.setDock(this@LauncherActivity, favs) }
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
            if (list.size >= 13) {
                Toast.makeText(this, "Maximal 13 Favoriten. Entferne zuerst einen.", Toast.LENGTH_SHORT).show()
                return
            }
            list.add(e.id)
        }
        Prefs.setDock(this, list)
    }

    override fun openShade() {
        val svc = ShadeService.instance
        if (svc != null) {
            if (Prefs.on(this, Prefs.SHADE)) svc.openShade()
            else svc.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_NOTIFICATIONS)
            return
        }
        try {
            val sbm = getSystemService("statusbar")
            sbm?.javaClass?.getMethod("expandNotificationsPanel")?.invoke(sbm)
        } catch (_: Exception) { }
    }

    private fun dialogTheme() =
        if (t.light) android.R.style.Theme_DeviceDefault_Light_Dialog_Alert else android.R.style.Theme_DeviceDefault_Dialog_Alert

    private fun field(value: String, hintText: String, type: Int = InputType.TYPE_CLASS_TEXT): EditText = EditText(this).apply {
        setText(value)
        hint = hintText
        inputType = type
        setSelection(text.length)
    }

    private fun formBox(vararg views: View): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(22), dp(8), dp(22), 0)
        views.forEach { addView(it, lp(MATCH, WRAP).margins(top = dp(6))) }
    }

    override fun editQuests() {
        val goal = field(Player.stepGoal(this).takeIf { it > 0 }?.toString() ?: "", "Schrittziel (leer = keine Schritt-Quest)",
            InputType.TYPE_CLASS_NUMBER)
        val list = field(Player.customTitles(this).joinToString("\n"), "Eine Quest pro Zeile (max. 5)",
            InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES).apply {
            isSingleLine = false; minLines = 3; gravity = Gravity.TOP
        }
        val hintText = TextView(this).apply {
            text = "Jede erledigte Quest gibt 50 XP. Sind alle erledigt, gibt es 100 XP Bonus und die Serie wächst."
            textSize = 13f
            setPadding(dp(4), dp(10), dp(4), 0)
        }
        AlertDialog.Builder(this, dialogTheme())
            .setTitle("Tägliche Quests")
            .setView(formBox(goal, list, hintText))
            .setPositiveButton("Speichern") { _, _ ->
                Player.setQuests(this, goal.text.toString().toIntOrNull() ?: 0, list.text.toString().lines())
                refresh()
            }
            .setNegativeButton("Abbrechen", null)
            .show()
    }

    override fun editName() {
        val name = field(Player.name(this), "Name", InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_WORDS)
        val lv = Player.level(Player.xp(this))
        AlertDialog.Builder(this, dialogTheme())
            .setTitle("Status")
            .setMessage("Level ${lv.level} · Rang ${lv.rank} · %,d XP gesamt".format(java.util.Locale.GERMAN, Player.xp(this)))
            .setView(formBox(name))
            .setPositiveButton("Speichern") { _, _ -> Player.setName(this, name.text.toString()); refresh() }
            .setNeutralButton("Einstellungen") { _, _ -> startActivity(Intent(this, MainActivity::class.java)) }
            .setNegativeButton("Abbrechen", null)
            .show()
    }

    override fun editWeather() {
        val city = field(Weather.city(this), "Stadt", InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_WORDS)
        AlertDialog.Builder(this, dialogTheme())
            .setTitle("Wetter-Ort")
            .setMessage("Das Wetter kommt von Open-Meteo. Dabei wird nur der Ortsname übermittelt.")
            .setView(formBox(city))
            .setPositiveButton("Speichern") { _, _ ->
                Weather.setCity(this, city.text.toString())
                Weather.refresh(this) { refresh() }
                refresh()
            }
            .setNegativeButton("Abbrechen", null)
            .show()
    }

    override fun askSteps() {
        if (!Steps.available(this)) {
            Toast.makeText(this, "Dieses Handy hat keinen Schrittzähler", Toast.LENGTH_SHORT).show(); return
        }
        if (Build.VERSION.SDK_INT >= 29) requestPermissions(arrayOf(Manifest.permission.ACTIVITY_RECOGNITION), 1)
    }

    override fun askCalendar() = requestPermissions(arrayOf(Manifest.permission.READ_CALENDAR), 2)

    override fun openCalendar() {
        safeStart(Intent(Intent.ACTION_VIEW, Uri.parse("content://com.android.calendar/time/${System.currentTimeMillis()}")))
    }

    override fun askNotificationAccess() {
        if (Build.VERSION.SDK_INT >= 30) {
            try {
                startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_DETAIL_SETTINGS).putExtra(
                    Settings.EXTRA_NOTIFICATION_LISTENER_COMPONENT_NAME,
                    ComponentName(this, NotifyListener::class.java).flattenToString()
                ))
                return
            } catch (_: Exception) { }
        }
        safeStart(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
    }

    private fun safeStart(i: Intent) {
        try { startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } catch (_: Exception) {
            Toast.makeText(this, "Keine passende App gefunden", Toast.LENGTH_SHORT).show()
        }
    }

    // ---------------------------------------------------------------- gestures

    /** True if (x, y) lands on something tappable, so long-press there shouldn't open settings. */
    private fun tappableAt(v: View, x: Int, y: Int): Boolean {
        val r = Rect()
        if (v.visibility != View.VISIBLE || !v.getGlobalVisibleRect(r) || !r.contains(x, y)) return false
        if (v !== root && v !== home && v !== pager && (v.isClickable || v.isLongClickable)) return true
        if (v is ViewGroup) for (i in v.childCount - 1 downTo 0) if (tappableAt(v.getChildAt(i), x, y)) return true
        return false
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
                if (tappableAt(root, e.rawX.toInt(), e.rawY.toInt())) return false
                svc.lock()
                return true
            }

            override fun onLongPress(e: MotionEvent) {
                if (tappableAt(root, e.rawX.toInt(), e.rawY.toInt())) return
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
                addView(text("", 12f, t.text, Fonts.medium).apply {
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
