package com.ascend.ui

import android.accessibilityservice.AccessibilityService
import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.content.res.Configuration
import android.media.AudioManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent

/**
 * The accessibility module. It doesn't read screen content; it uses accessibility powers to:
 *  - place a swipe zone over the status bar that opens the Ascend shade
 *  - catch the volume keys and show the Ascend volume panel
 *  - draw the HUD frame, unlock animation and charging glow above everything
 *  - lock the screen / take screenshots from the shade
 */
class ShadeService : AccessibilityService() {

    companion object {
        @Volatile var instance: ShadeService? = null
    }

    private val h = Handler(Looper.getMainLooper())
    private var trigger: View? = null
    private var hud: View? = null
    private var shade: ShadeView? = null
    private var splashing = false
    private var foregroundPkg: String = ""
    private lateinit var volume: VolumePanel

    private val prefListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        when (key) {
            Prefs.THEME -> { refreshHud(); refreshTrigger() }
            Prefs.SHADE, Prefs.SHADE_ZONE -> refreshTrigger()
            Prefs.HUD -> refreshHud()
        }
    }

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) {
            when (i.action) {
                Intent.ACTION_USER_PRESENT -> if (Prefs.on(c, Prefs.UNLOCK)) showSplash()
                Intent.ACTION_POWER_CONNECTED -> if (Prefs.on(c, Prefs.CHARGE_GLOW)) EdgeGlow.flash(c, 2600)
                Intent.ACTION_SCREEN_OFF -> { closeShade(); volume.dismiss() }
            }
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        volume = VolumePanel(this)
        Prefs.sp(this).registerOnSharedPreferenceChangeListener(prefListener)
        registerSys(receiver, IntentFilter().apply {
            addAction(Intent.ACTION_USER_PRESENT)
            addAction(Intent.ACTION_POWER_CONNECTED)
            addAction(Intent.ACTION_SCREEN_OFF)
        })
        refreshTrigger()
        refreshHud()
    }

    override fun onCreate() {
        super.onCreate()
        Fonts.init(this)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event?.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            event.packageName?.toString()?.let { if (it != packageName) foregroundPkg = it }
        }
    }

    override fun onInterrupt() {}

    override fun onKeyEvent(event: KeyEvent): Boolean {
        val code = event.keyCode
        if (code != KeyEvent.KEYCODE_VOLUME_UP && code != KeyEvent.KEYCODE_VOLUME_DOWN) return false
        if (!Prefs.on(this, Prefs.VOLUME)) return false
        // Leave calls, ringing phones and camera shutters to the system.
        val am = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        if (am.mode != AudioManager.MODE_NORMAL) return false
        if (foregroundPkg.contains("camera", ignoreCase = true)) return false
        if (event.action == KeyEvent.ACTION_DOWN) volume.adjust(code == KeyEvent.KEYCODE_VOLUME_UP)
        return true
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        refreshTrigger()
        refreshHud()
    }

    override fun onUnbind(intent: Intent?): Boolean {
        teardown()
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        teardown()
        super.onDestroy()
    }

    private fun teardown() {
        if (instance !== this) return
        instance = null
        Prefs.sp(this).unregisterOnSharedPreferenceChangeListener(prefListener)
        unregisterSafe(receiver)
        trigger?.let { Overlay.remove(this, it) }; trigger = null
        hud?.let { Overlay.remove(this, it) }; hud = null
        shade?.let { Overlay.remove(this, it) }; shade = null
        if (::volume.isInitialized) volume.dismiss()
    }

    // ---------------------------------------------------------------- public actions

    fun lock() {
        if (Build.VERSION.SDK_INT >= 28) performGlobalAction(GLOBAL_ACTION_LOCK_SCREEN)
    }

    fun screenshot() {
        if (Build.VERSION.SDK_INT >= 28) performGlobalAction(GLOBAL_ACTION_TAKE_SCREENSHOT)
    }

    fun openShade() {
        h.post {
            if (shade != null) return@post
            val blur = Prefs.on(this, Prefs.GLASS) && Overlay.canBlur(this)
            val v = ShadeView(this, blur) { closeShade() }
            val p = Overlay.params(
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY, MATCH, MATCH,
                touchable = true, focusable = true, full = true
            )
            if (blur && Build.VERSION.SDK_INT >= 31) {
                p.flags = p.flags or WindowManager.LayoutParams.FLAG_BLUR_BEHIND
                p.blurBehindRadius = dp(34)
            }
            if (!Overlay.add(this, v, p)) return@post
            shade = v
            v.animateIn()
        }
    }

    fun closeShade(then: (() -> Unit)? = null) {
        h.post {
            val v = shade ?: return@post
            shade = null
            v.animateOut {
                Overlay.remove(this, v)
                then?.invoke()
            }
        }
    }

    // ---------------------------------------------------------------- windows

    private fun refreshTrigger() {
        trigger?.let { Overlay.remove(this, it) }
        trigger = null
        if (!Prefs.on(this, Prefs.SHADE)) return
        val zone = Prefs.int(this, Prefs.SHADE_ZONE, 1)
        val width = if (zone == 2) MATCH else resources.displayMetrics.widthPixels / 2
        val v = View(this)
        val p = Overlay.params(
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY, width, statusBarHeight(),
            touchable = true, full = true
        )
        p.gravity = Gravity.TOP or (if (zone == 0) Gravity.START else Gravity.END)
        var downY = 0f
        var fired = false
        v.setOnTouchListener { _, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> { downY = e.rawY; fired = false }
                MotionEvent.ACTION_MOVE -> if (!fired && e.rawY - downY > dp(16)) { fired = true; openShade() }
            }
            true
        }
        if (Overlay.add(this, v, p)) trigger = v
    }

    private fun refreshHud() {
        hud?.let { Overlay.remove(this, it) }
        hud = null
        if (!Prefs.on(this, Prefs.HUD)) return
        val v = HudFrameView(this, Prefs.theme(this))
        val p = Overlay.params(
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY, MATCH, MATCH,
            touchable = false, full = true
        )
        if (Overlay.add(this, v, p)) hud = v
    }

    private fun showSplash() {
        if (splashing) return
        val v = SplashView(this, Prefs.theme(this))
        val p = Overlay.params(
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY, MATCH, MATCH,
            touchable = false, full = true
        )
        if (!Overlay.add(this, v, p)) return
        splashing = true
        ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 1300
            addUpdateListener { v.progress = it.animatedValue as Float; v.invalidate() }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    Overlay.remove(this@ShadeService, v)
                    splashing = false
                }
            })
            start()
        }
    }
}
