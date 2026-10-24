package com.ascend.ui

import android.accessibilityservice.AccessibilityService
import android.app.NotificationManager
import android.bluetooth.BluetoothManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.res.ColorStateList
import android.graphics.Bitmap
import android.graphics.drawable.BitmapDrawable
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.net.Uri
import android.net.wifi.WifiManager
import android.os.BatteryManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.text.TextUtils
import android.view.GestureDetector
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.animation.DecelerateInterpolator
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.pow
import kotlin.math.roundToInt

/** The pull-down panel: clock, toggles, brightness, media controls and notifications. */
class ShadeView(private val svc: ShadeService, private val onClose: () -> Unit) : FrameLayout(svc) {
    private val t = Prefs.theme(svc)
    private val h = Handler(Looper.getMainLooper())
    private val scroll = ScrollView(svc)
    private val panel = LinearLayout(svc)
    private val tiles = ArrayList<QsTile>()
    private val mediaBox = LinearLayout(svc)
    private val notifBox = LinearLayout(svc)
    private val camera = svc.getSystemService(Context.CAMERA_SERVICE) as CameraManager
    private var torchId: String? = null
    private var torchOn = false
    private var autoChip: TextView? = null
    private val notifCb: () -> Unit = { refreshNotifs(); refreshMedia() }

    private val torchCb = object : CameraManager.TorchCallback() {
        override fun onTorchModeChanged(cameraId: String, enabled: Boolean) {
            if (cameraId == torchId) { torchOn = enabled; tiles.forEach { it.refresh() } }
        }
    }

    init {
        setBackgroundColor(t.bg.withAlpha(if (t.light) 0xF0 else 0xEC))
        isFocusableInTouchMode = true

        val outer = LinearLayout(svc).apply { orientation = LinearLayout.VERTICAL }
        panel.orientation = LinearLayout.VERTICAL
        panel.setPadding(dp(16), svc.statusBarHeight() + dp(14), dp(16), dp(8))
        outer.addView(panel, lp(MATCH, WRAP))
        // Tapping the empty space under the content closes the shade.
        outer.addView(View(svc).apply { setOnClickListener { onClose() } }, lp(MATCH, 0).weighted())
        val handle = svc.text("▲  CLOSE", 10f, t.textDim, Fonts.mono, 0.3f).apply {
            gravity = Gravity.CENTER
            setPadding(0, dp(14), 0, svc.navBarHeight() + dp(14))
            setOnClickListener { onClose() }
        }
        outer.addView(handle, lp(MATCH, WRAP))

        scroll.isFillViewport = true
        scroll.isVerticalScrollBarEnabled = false
        scroll.overScrollMode = View.OVER_SCROLL_NEVER
        scroll.addView(outer, LayoutParams(MATCH, WRAP))
        addView(scroll, LayoutParams(MATCH, MATCH))

        val swipe = GestureDetector(svc, object : GestureDetector.SimpleOnGestureListener() {
            override fun onFling(e1: MotionEvent?, e2: MotionEvent, vx: Float, vy: Float): Boolean {
                if (vy < -1500f && !scroll.canScrollVertically(1)) { onClose(); return true }
                return false
            }
        })
        scroll.setOnTouchListener { _, e -> swipe.onTouchEvent(e); false }

        findTorch()
        buildHeader()
        buildTiles()
        buildBrightness()
        mediaBox.orientation = LinearLayout.VERTICAL
        panel.addView(mediaBox, lp(MATCH, WRAP))
        notifBox.orientation = LinearLayout.VERTICAL
        panel.addView(notifBox, lp(MATCH, WRAP))
        buildFooter()
        refreshMedia()
        refreshNotifs()
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        NotifyListener.addListener(notifCb)
        try { camera.registerTorchCallback(torchCb, h) } catch (_: Exception) { }
        requestFocus()
    }

    override fun onDetachedFromWindow() {
        NotifyListener.removeListener(notifCb)
        try { camera.unregisterTorchCallback(torchCb) } catch (_: Exception) { }
        super.onDetachedFromWindow()
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.keyCode == KeyEvent.KEYCODE_BACK) {
            if (event.action == KeyEvent.ACTION_UP) onClose()
            return true
        }
        return super.dispatchKeyEvent(event)
    }

    fun animateIn() {
        alpha = 0f
        scroll.translationY = -dp(70).toFloat()
        animate().alpha(1f).setDuration(200).start()
        scroll.animate().translationY(0f).setDuration(280).setInterpolator(DecelerateInterpolator()).start()
    }

    fun animateOut(end: () -> Unit) {
        scroll.animate().translationY(-dp(60).toFloat()).setDuration(180).start()
        animate().alpha(0f).setDuration(190).withEndAction(end).start()
    }

    // ---------------------------------------------------------------- header

    private fun buildHeader() {
        val row = LinearLayout(svc).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.BOTTOM }
        val left = LinearLayout(svc).apply { orientation = LinearLayout.VERTICAL }
        val now = Date()
        val fmt = if (android.text.format.DateFormat.is24HourFormat(svc)) "HH:mm" else "h:mm"
        left.addView(svc.text(SimpleDateFormat(fmt, Locale.getDefault()).format(now), 46f, t.text, Fonts.thin))
        left.addView(
            svc.hudLabel(SimpleDateFormat("EEE · d MMM", Locale.ENGLISH).format(now), t, 11f),
            lp(WRAP, WRAP).margins(top = dp(4), left = dp(2))
        )
        row.addView(left, lp(0, WRAP).weighted())

        val right = LinearLayout(svc).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.END }
        val batt = svc.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val level = batt?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = (batt?.getIntExtra(BatteryManager.EXTRA_SCALE, 100) ?: 100).coerceAtLeast(1)
        val status = batt?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
        val charging = status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL
        val pct = if (level >= 0) "${level * 100 / scale}%" else "--"
        val gear = GlyphView(svc, Glyph.GEAR, t.textDim).apply {
            setPadding(dp(6), dp(6), dp(6), dp(6))
            setOnClickListener { onClose(); start(Intent(svc, MainActivity::class.java)) }
        }
        right.addView(gear, lp(dp(34), dp(34)))
        right.addView(svc.text((if (charging) "⚡ " else "") + "ENERGY $pct", 11f, t.accent, Fonts.mono, 0.12f),
            lp(WRAP, WRAP).margins(top = dp(8)))
        row.addView(right, lp(WRAP, WRAP))
        panel.addView(row, lp(MATCH, WRAP))
    }

    // ---------------------------------------------------------------- tiles

    private inner class QsTile(
        private val glyph: Glyph, label: String,
        private val state: () -> Boolean, private val act: () -> Unit
    ) : LinearLayout(svc) {
        private val g = GlyphView(svc, glyph, t.text)
        private val l: TextView = svc.text(label.uppercase(), 9f, t.textDim, Fonts.mono, 0.1f).apply {
            maxLines = 1; ellipsize = TextUtils.TruncateAt.END; gravity = Gravity.CENTER
        }

        init {
            orientation = VERTICAL
            gravity = Gravity.CENTER
            setPadding(dp(4), dp(14), dp(4), dp(12))
            addView(g, LayoutParams(dp(24), dp(24)))
            addView(l, LayoutParams(MATCH, WRAP).apply { topMargin = dp(9) })
            setOnClickListener { act(); h.postDelayed({ refresh() }, 300) }
            refresh()
        }

        fun refresh() {
            val on = try { state() } catch (_: Exception) { false }
            background = roundRect(
                if (on) t.accent.withAlpha(0x3A) else t.surface.withAlpha(0xD8),
                svc.dpf(14f),
                if (on) t.accent else t.accent.withAlpha(0x2E), dp(1)
            )
            g.set(glyph, if (on) t.accent else t.text)
            l.setTextColor(if (on) t.accent else t.textDim)
        }
    }

    private fun buildTiles() {
        val wifi = svc.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        val bt = (svc.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter
        val defs = listOf(
            QsTile(Glyph.WIFI, "Wi-Fi", { wifi.isWifiEnabled }) {
                onClose()
                start(Intent(if (Build.VERSION.SDK_INT >= 29) Settings.Panel.ACTION_INTERNET_CONNECTIVITY else Settings.ACTION_WIFI_SETTINGS))
            },
            QsTile(Glyph.BLUETOOTH, "Bluetooth", { bt?.isEnabled == true }) {
                onClose(); start(Intent(Settings.ACTION_BLUETOOTH_SETTINGS))
            },
            QsTile(Glyph.TORCH, "Torch", { torchOn }) { toggleTorch() },
            QsTile(Glyph.DND, "Focus", { dndOn() }) { toggleDnd() },
            QsTile(Glyph.ROTATE, "Rotate", {
                Settings.System.getInt(svc.contentResolver, Settings.System.ACCELEROMETER_ROTATION, 0) == 1
            }) { toggleRotate() },
            QsTile(Glyph.SCREENSHOT, "Capture", { false }) {
                onClose(); h.postDelayed({ svc.screenshot() }, 550)
            },
            QsTile(Glyph.LOCK, "Lock", { false }) { onClose(); h.postDelayed({ svc.lock() }, 250) },
            QsTile(Glyph.POWER, "Power", { false }) {
                onClose(); svc.performGlobalAction(AccessibilityService.GLOBAL_ACTION_POWER_DIALOG)
            }
        )
        tiles.addAll(defs)
        for (r in 0 until 2) {
            val row = LinearLayout(svc).apply { orientation = LinearLayout.HORIZONTAL }
            for (c in 0 until 4) {
                row.addView(defs[r * 4 + c], lp(0, WRAP).weighted().margins(
                    left = if (c == 0) 0 else dp(5), right = if (c == 3) 0 else dp(5)
                ))
            }
            panel.addView(row, lp(MATCH, WRAP).margins(top = dp(if (r == 0) 22 else 10)))
        }
    }

    private fun findTorch() {
        torchId = try {
            camera.cameraIdList.firstOrNull {
                camera.getCameraCharacteristics(it).get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
            }
        } catch (_: Exception) { null }
    }

    private fun toggleTorch() {
        val id = torchId ?: return toast("No flashlight found")
        try { camera.setTorchMode(id, !torchOn) } catch (_: Exception) { toast("Flashlight is busy") }
    }

    private fun dndOn(): Boolean {
        val f = NotifyListener.instance?.currentInterruptionFilter ?: return false
        return f != NotificationManager.INTERRUPTION_FILTER_ALL && f != NotificationManager.INTERRUPTION_FILTER_UNKNOWN
    }

    private fun toggleDnd() {
        val l = NotifyListener.instance ?: return toast("Give Ascend notification access first")
        try {
            l.requestInterruptionFilter(
                if (dndOn()) NotificationManager.INTERRUPTION_FILTER_ALL else NotificationManager.INTERRUPTION_FILTER_PRIORITY
            )
        } catch (_: Exception) { toast("Couldn't change Do Not Disturb") }
    }

    private fun toggleRotate() {
        if (!Settings.System.canWrite(svc)) return askWriteSettings()
        val cur = Settings.System.getInt(svc.contentResolver, Settings.System.ACCELEROMETER_ROTATION, 0)
        Settings.System.putInt(svc.contentResolver, Settings.System.ACCELEROMETER_ROTATION, if (cur == 1) 0 else 1)
    }

    private fun askWriteSettings() {
        toast("Allow Ascend to modify system settings")
        onClose()
        start(Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS, Uri.parse("package:${svc.packageName}")))
    }

    // ---------------------------------------------------------------- brightness

    private fun buildBrightness() {
        val row = LinearLayout(svc).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = roundRect(t.surface.withAlpha(0xD8), svc.dpf(14f), t.accent.withAlpha(0x2E), dp(1))
            setPadding(dp(14), dp(6), dp(8), dp(6))
        }
        row.addView(GlyphView(svc, Glyph.BRIGHT, t.accent), lp(dp(22), dp(22)))
        val cr = svc.contentResolver
        val cur = Settings.System.getInt(cr, Settings.System.SCREEN_BRIGHTNESS, 128)
        val seek = SeekBar(svc).apply {
            max = 1000
            progress = ((cur / 255f).toDouble().pow(1 / 2.2) * 1000).roundToInt()
            progressTintList = ColorStateList.valueOf(t.accent)
            progressBackgroundTintList = ColorStateList.valueOf(t.surfaceHi)
            thumbTintList = ColorStateList.valueOf(t.accent)
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: SeekBar, p: Int, fromUser: Boolean) {
                    if (!fromUser || !Settings.System.canWrite(svc)) return
                    val b = (255 * (p / 1000.0).pow(2.2)).roundToInt().coerceIn(1, 255)
                    Settings.System.putInt(cr, Settings.System.SCREEN_BRIGHTNESS_MODE, Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL)
                    Settings.System.putInt(cr, Settings.System.SCREEN_BRIGHTNESS, b)
                }
                override fun onStartTrackingTouch(sb: SeekBar) { if (!Settings.System.canWrite(svc)) askWriteSettings() }
                override fun onStopTrackingTouch(sb: SeekBar) { autoChip?.let { styleAuto(it) } }
            })
        }
        row.addView(seek, lp(0, WRAP).weighted())
        val auto = svc.text("AUTO", 10f, t.textDim, Fonts.mono, 0.12f).apply {
            setPadding(dp(10), dp(7), dp(10), dp(7))
            setOnClickListener {
                if (!Settings.System.canWrite(svc)) return@setOnClickListener askWriteSettings()
                val isAuto = Settings.System.getInt(cr, Settings.System.SCREEN_BRIGHTNESS_MODE, 0) ==
                    Settings.System.SCREEN_BRIGHTNESS_MODE_AUTOMATIC
                Settings.System.putInt(
                    cr, Settings.System.SCREEN_BRIGHTNESS_MODE,
                    if (isAuto) Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL else Settings.System.SCREEN_BRIGHTNESS_MODE_AUTOMATIC
                )
                styleAuto(this)
            }
        }
        autoChip = auto
        styleAuto(auto)
        row.addView(auto)
        panel.addView(row, lp(MATCH, WRAP).margins(top = dp(10)))
    }

    private fun styleAuto(v: TextView) {
        val isAuto = Settings.System.getInt(svc.contentResolver, Settings.System.SCREEN_BRIGHTNESS_MODE, 0) ==
            Settings.System.SCREEN_BRIGHTNESS_MODE_AUTOMATIC
        v.setTextColor(if (isAuto) t.accent else t.textDim)
        v.background = roundRect(
            if (isAuto) t.accent.withAlpha(0x30) else 0, svc.dpf(10f),
            if (isAuto) t.accent else t.textDim.withAlpha(0x60), dp(1)
        )
    }

    // ---------------------------------------------------------------- media

    private fun refreshMedia() {
        mediaBox.removeAllViews()
        val ctrl = activeController() ?: return
        val meta = ctrl.metadata ?: return
        val title = meta.getString(MediaMetadata.METADATA_KEY_TITLE) ?: return
        val artist = meta.getString(MediaMetadata.METADATA_KEY_ARTIST) ?: ""
        val art: Bitmap? = meta.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART) ?: meta.getBitmap(MediaMetadata.METADATA_KEY_ART)
        val playing = ctrl.playbackState?.state == PlaybackState.STATE_PLAYING

        val card = LinearLayout(svc).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = HudDrawable(t, resources.displayMetrics.density)
            setPadding(dp(16), dp(14), dp(12), dp(14))
            setOnClickListener { ctrl.sessionActivity?.let { onClose(); it.sendFromOverlay(svc) } }
        }
        if (art != null) {
            card.addView(
                PopupManager.roundIcon(svc, BitmapDrawable(resources, art)).apply {
                    outlineProvider = android.view.ViewOutlineProvider.BACKGROUND
                    background = roundRect(t.surfaceHi, svc.dpf(10f))
                },
                lp(dp(54), dp(54)).margins(right = dp(14))
            )
        }
        val col = LinearLayout(svc).apply { orientation = LinearLayout.VERTICAL }
        col.addView(svc.hudLabel("Now playing", t, 9.5f))
        col.addView(svc.text(title, 15f, t.text, Fonts.medium).apply { maxLines = 1; ellipsize = TextUtils.TruncateAt.END },
            lp(MATCH, WRAP).margins(top = dp(6)))
        if (artist.isNotBlank()) col.addView(svc.text(artist, 12.5f, t.textDim).apply {
            maxLines = 1; ellipsize = TextUtils.TruncateAt.END
        }, lp(MATCH, WRAP).margins(top = dp(3)))
        card.addView(col, lp(0, WRAP).weighted())

        fun ctl(g: Glyph, size: Int, a: () -> Unit) = GlyphView(svc, g, t.text).apply {
            setPadding(dp(7), dp(7), dp(7), dp(7))
            setOnClickListener { a(); h.postDelayed({ refreshMedia() }, 300) }
            card.addView(this, lp(dp(size), dp(size)))
        }
        ctl(Glyph.PREV, 36) { ctrl.transportControls.skipToPrevious() }
        ctl(if (playing) Glyph.PAUSE else Glyph.PLAY, 40) {
            if (playing) ctrl.transportControls.pause() else ctrl.transportControls.play()
        }.set(if (playing) Glyph.PAUSE else Glyph.PLAY, t.accent)
        ctl(Glyph.NEXT, 36) { ctrl.transportControls.skipToNext() }

        mediaBox.addView(card, lp(MATCH, WRAP).margins(top = dp(12)))
    }

    private fun activeController(): MediaController? = try {
        val msm = svc.getSystemService(Context.MEDIA_SESSION_SERVICE) as MediaSessionManager
        val sessions = msm.getActiveSessions(ComponentName(svc, NotifyListener::class.java))
        sessions.firstOrNull { it.playbackState?.state == PlaybackState.STATE_PLAYING }
            ?: sessions.firstOrNull { it.metadata != null }
    } catch (_: Exception) { null }

    // ---------------------------------------------------------------- notifications

    private fun refreshNotifs() {
        notifBox.removeAllViews()
        val listening = NotifyListener.instance != null
        val list = NotifyListener.notices(svc)
        val head = LinearLayout(svc).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        head.addView(svc.hudLabel("◆ Notifications · ${list.size}", t, 10.5f), lp(0, WRAP).weighted())
        if (list.any { it.clearable }) {
            head.addView(svc.text("CLEAR ALL", 10f, t.accent, Fonts.mono, 0.14f).apply {
                setPadding(dp(10), dp(8), dp(4), dp(8))
                setOnClickListener { NotifyListener.dismissAll(); h.postDelayed({ refreshNotifs() }, 250) }
            })
        }
        notifBox.addView(head, lp(MATCH, WRAP).margins(top = dp(22), left = dp(4)))

        if (!listening) {
            notifBox.addView(svc.text("Give Ascend notification access to see notifications here.", 13f, t.textDim)
                .apply { setPadding(dp(4), dp(10), dp(4), 0) })
            return
        }
        if (list.isEmpty()) {
            notifBox.addView(svc.text("No new notices.", 13f, t.textDim).apply { setPadding(dp(4), dp(10), dp(4), 0) })
            return
        }
        for (n in list.take(25)) notifBox.addView(noticeRow(n), lp(MATCH, WRAP).margins(top = dp(8)))
    }

    private fun noticeRow(n: Notice): View {
        val row = LinearLayout(svc).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = roundRect(t.surface.withAlpha(0xE0), svc.dpf(14f), t.accent.withAlpha(0x26), dp(1))
            setPadding(dp(14), dp(12), dp(6), dp(12))
            setOnClickListener {
                onClose()
                n.intent?.sendFromOverlay(svc)
                if (n.autoCancel) NotifyListener.dismiss(n.key)
            }
        }
        if (n.icon != null) row.addView(PopupManager.roundIcon(svc, n.icon), lp(dp(38), dp(38)).margins(right = dp(12)))
        val col = LinearLayout(svc).apply { orientation = LinearLayout.VERTICAL }
        col.addView(svc.text("${n.app.uppercase()} · ${PopupManager.relTime(n.time)}", 9.5f, t.accent, Fonts.mono, 0.08f)
            .apply { maxLines = 1; ellipsize = TextUtils.TruncateAt.END })
        col.addView(svc.text(n.title, 14.5f, t.text, Fonts.medium).apply { maxLines = 1; ellipsize = TextUtils.TruncateAt.END },
            lp(MATCH, WRAP).margins(top = dp(5)))
        if (n.text.isNotBlank()) col.addView(svc.text(n.text, 13f, t.textDim).apply {
            maxLines = 3; ellipsize = TextUtils.TruncateAt.END; setLineSpacing(0f, 1.12f)
        }, lp(MATCH, WRAP).margins(top = dp(3)))
        row.addView(col, lp(0, WRAP).weighted())
        if (n.clearable) {
            row.addView(GlyphView(svc, Glyph.CLOSE, t.textDim).apply {
                setPadding(dp(9), dp(9), dp(9), dp(9))
                setOnClickListener { NotifyListener.dismiss(n.key); h.postDelayed({ refreshNotifs() }, 200) }
            }, lp(dp(36), dp(36)))
        }
        return row
    }

    // ---------------------------------------------------------------- footer

    private fun buildFooter() {
        val row = LinearLayout(svc).apply { orientation = LinearLayout.HORIZONTAL }
        fun btn(label: String, a: () -> Unit) {
            row.addView(svc.text(label, 10f, t.accent, Fonts.mono, 0.12f).apply {
                gravity = Gravity.CENTER
                setPadding(dp(6), dp(12), dp(6), dp(12))
                background = roundRect(0, svc.dpf(10f), t.accent.withAlpha(0x70), dp(1))
                setOnClickListener { a() }
            }, lp(0, WRAP).weighted().margins(left = dp(4), right = dp(4)))
        }
        btn("SYSTEM PANEL") {
            onClose(); svc.performGlobalAction(AccessibilityService.GLOBAL_ACTION_QUICK_SETTINGS)
        }
        btn("THEME ▸") {
            Prefs.cycleTheme(svc)
            svc.closeShade { h.postDelayed({ svc.openShade() }, 120) }
        }
        panel.addView(row, lp(MATCH, WRAP).margins(top = dp(22)))
    }

    // ---------------------------------------------------------------- utils

    private fun start(i: Intent) {
        try { svc.startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } catch (_: Exception) { }
    }

    private fun toast(msg: String) = Toast.makeText(svc, msg, Toast.LENGTH_SHORT).show()
}
