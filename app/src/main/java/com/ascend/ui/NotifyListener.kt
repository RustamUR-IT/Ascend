package com.ascend.ui

import android.app.KeyguardManager
import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.graphics.drawable.Drawable
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification

/** A notification flattened into what Ascend's popups and shade need to draw it. */
data class Notice(
    val key: String,
    val pkg: String,
    val app: String,
    val icon: Drawable?,
    val title: String,
    val text: String,
    val time: Long,
    val intent: PendingIntent?,
    val clearable: Boolean,
    val autoCancel: Boolean
) {
    companion object {
        fun from(ctx: Context, sbn: StatusBarNotification): Notice {
            val n = sbn.notification
            val ex = n.extras
            val pm = ctx.packageManager
            val app = try {
                pm.getApplicationLabel(pm.getApplicationInfo(sbn.packageName, 0)).toString()
            } catch (_: Exception) { sbn.packageName }
            val icon: Drawable? = try { n.getLargeIcon()?.loadDrawable(ctx) } catch (_: Exception) { null }
                ?: try { pm.getApplicationIcon(sbn.packageName) } catch (_: Exception) { null }
            val title = ex.getCharSequence(Notification.EXTRA_TITLE)?.toString()
                ?: ex.getCharSequence(Notification.EXTRA_CONVERSATION_TITLE)?.toString()
                ?: app
            val text = (ex.getCharSequence(Notification.EXTRA_BIG_TEXT)
                ?: ex.getCharSequence(Notification.EXTRA_TEXT))?.toString() ?: ""
            return Notice(
                sbn.key, sbn.packageName, app, icon, title, text, sbn.postTime, n.contentIntent,
                sbn.isClearable, (n.flags and Notification.FLAG_AUTO_CANCEL) != 0
            )
        }

        fun sample(ctx: Context): Notice = Notice(
            "ascend-sample", ctx.packageName, "Ascend",
            try { ctx.packageManager.getApplicationIcon(ctx.packageName) } catch (_: Exception) { null },
            "Daily quest available",
            "Theme engine online. All modules are reporting in.",
            System.currentTimeMillis(), null, clearable = false, autoCancel = false
        )
    }
}

/**
 * Reads incoming notifications (with the user's permission) so Ascend can show them as themed
 * popups and inside its own shade. Nothing is stored or sent anywhere.
 */
class NotifyListener : NotificationListenerService() {

    companion object {
        @Volatile var instance: NotifyListener? = null
        private val callbacks = LinkedHashSet<() -> Unit>()
        private val main = Handler(Looper.getMainLooper())

        fun addListener(cb: () -> Unit) { main.post { callbacks.add(cb) } }
        fun removeListener(cb: () -> Unit) { main.post { callbacks.remove(cb) } }

        private fun changed() {
            main.post { callbacks.toList().forEach { it() } }
        }

        private fun visible(s: StatusBarNotification): Boolean =
            (s.notification.flags and Notification.FLAG_GROUP_SUMMARY) == 0

        fun notices(ctx: Context): List<Notice> = try {
            instance?.activeNotifications
                ?.filter { visible(it) }
                ?.sortedByDescending { it.postTime }
                ?.map { Notice.from(ctx, it) }
                ?: emptyList()
        } catch (_: Exception) { emptyList() }

        fun count(): Int = try {
            instance?.activeNotifications?.count { visible(it) && !it.isOngoing } ?: 0
        } catch (_: Exception) { 0 }

        fun dismiss(key: String) {
            try { instance?.cancelNotification(key) } catch (_: Exception) { }
        }

        fun dismissAll() {
            try { instance?.cancelAllNotifications() } catch (_: Exception) { }
        }
    }

    private val alerted = HashMap<String, Long>()

    override fun onListenerConnected() {
        instance = this
        changed()
    }

    override fun onListenerDisconnected() {
        if (instance === this) instance = null
        changed()
    }

    override fun onDestroy() {
        if (instance === this) instance = null
        super.onDestroy()
    }

    override fun onNotificationPosted(sbn: StatusBarNotification, rankingMap: RankingMap?) {
        changed()
        if (!shouldAlert(sbn, rankingMap)) return
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        if (!pm.isInteractive) return
        val km = getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager
        if (Prefs.on(this, Prefs.EDGE_GLOW)) EdgeGlow.flash(this)
        if (Prefs.on(this, Prefs.POPUPS) && !km.isKeyguardLocked) PopupManager.show(this, Notice.from(this, sbn))
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification?) {
        changed()
    }

    private fun shouldAlert(sbn: StatusBarNotification, rankingMap: RankingMap?): Boolean {
        if (sbn.packageName == packageName) return false
        if (sbn.isOngoing) return false
        val n = sbn.notification
        if ((n.flags and Notification.FLAG_GROUP_SUMMARY) != 0) return false
        if ((n.flags and Notification.FLAG_ONLY_ALERT_ONCE) != 0 && alerted.containsKey(sbn.key)) return false
        if (rankingMap != null) {
            val r = Ranking()
            if (rankingMap.getRanking(sbn.key, r)) {
                if (r.importance < NotificationManager.IMPORTANCE_DEFAULT) return false
                if (!r.matchesInterruptionFilter()) return false   // respect Do Not Disturb
            }
        }
        val ex = n.extras
        if (ex.getCharSequence(Notification.EXTRA_TITLE).isNullOrBlank() &&
            ex.getCharSequence(Notification.EXTRA_TEXT).isNullOrBlank()) return false
        if (alerted.size > 300) alerted.clear()
        alerted[sbn.key] = System.currentTimeMillis()
        return true
    }
}
