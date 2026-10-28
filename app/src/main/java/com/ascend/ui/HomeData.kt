package com.ascend.ui

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.CalendarContract
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

private fun today(): String = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())

// ======================================================================== steps

/**
 * Daily step count from the phone's hardware step counter. The counter only gives "steps since boot",
 * so Ascend remembers the counter value at the start of each day.
 */
object Steps {
    private const val DATE = "steps_date"
    private const val BASE = "steps_base"
    private const val LAST = "steps_last"
    private const val TODAY = "steps_today"
    private var listener: SensorEventListener? = null

    fun permitted(ctx: Context): Boolean = Build.VERSION.SDK_INT < 29 ||
        ctx.checkSelfPermission(android.Manifest.permission.ACTIVITY_RECOGNITION) == PackageManager.PERMISSION_GRANTED

    fun available(ctx: Context): Boolean =
        (ctx.getSystemService(Context.SENSOR_SERVICE) as SensorManager).getDefaultSensor(Sensor.TYPE_STEP_COUNTER) != null

    /** Steps today, or -1 if not known (no permission or no sensor). */
    fun today(ctx: Context): Int {
        if (!permitted(ctx) || !available(ctx)) return -1
        val sp = Prefs.sp(ctx)
        return if (sp.getString(DATE, "") == today()) sp.getInt(TODAY, 0) else 0
    }

    fun start(ctx: Context, onChange: () -> Unit) {
        stop(ctx)
        if (!permitted(ctx)) return
        val sm = ctx.getSystemService(Context.SENSOR_SERVICE) as SensorManager
        val sensor = sm.getDefaultSensor(Sensor.TYPE_STEP_COUNTER) ?: return
        val l = object : SensorEventListener {
            override fun onSensorChanged(e: SensorEvent) { record(ctx, e.values[0].toLong()); onChange() }
            override fun onAccuracyChanged(s: Sensor?, a: Int) {}
        }
        listener = l
        sm.registerListener(l, sensor, SensorManager.SENSOR_DELAY_UI)
    }

    fun stop(ctx: Context) {
        listener?.let { (ctx.getSystemService(Context.SENSOR_SERVICE) as SensorManager).unregisterListener(it) }
        listener = null
    }

    private fun record(ctx: Context, counter: Long) {
        val sp = Prefs.sp(ctx)
        val d = today()
        var base = sp.getLong(BASE, counter)
        val last = sp.getLong(LAST, -1L)
        if (sp.getString(DATE, "") != d) {
            // New day: steps walked since the last reading count towards today.
            base = if (last in 0..counter) last else counter
        } else if (last >= 0 && counter < last) {
            // Phone restarted, so the counter began again at zero: keep today's progress.
            base = counter - (last - base)
        }
        val steps = (counter - base).coerceAtLeast(0).toInt()
        sp.edit().putString(DATE, d).putLong(BASE, base).putLong(LAST, counter).putInt(TODAY, steps).apply()
    }
}

// ======================================================================== quests, XP and level

data class Quest(val id: Int, val title: String, val stepsGoal: Int = 0) {
    val auto get() = stepsGoal > 0
}

/**
 * Daily quests and the player level. XP: +50 per finished quest, +1 per 100 steps (max 100 a day),
 * +100 when every quest of the day is done. A day with every quest done extends the streak.
 */
object Player {
    private const val NAME = "player_name"
    private const val QUESTS = "quests"
    private const val STEP_GOAL = "quest_steps_goal"
    private const val DONE = "quests_done"          // "yyyy-MM-dd|1,3"
    private const val XP = "xp_total"
    private const val STEP_XP = "xp_steps"          // "yyyy-MM-dd|n" XP already given for steps today
    private const val BONUS = "xp_bonus_day"
    private const val STREAK = "streak"
    private const val STREAK_DAY = "streak_day"

    fun name(ctx: Context): String = Prefs.sp(ctx).getString(NAME, null)?.takeIf { it.isNotBlank() } ?: "Player"
    fun setName(ctx: Context, n: String) = Prefs.put(ctx, NAME, n.trim().take(18))

    fun stepGoal(ctx: Context): Int = Prefs.int(ctx, STEP_GOAL, 10000)

    fun customTitles(ctx: Context): List<String> =
        (Prefs.sp(ctx).getString(QUESTS, null) ?: "1 Stunde lernen\nKein Handy nach 22 Uhr")
            .lines().map { it.trim() }.filter { it.isNotEmpty() }.take(5)

    fun setQuests(ctx: Context, stepGoal: Int, titles: List<String>) {
        Prefs.sp(ctx).edit()
            .putInt(STEP_GOAL, stepGoal.coerceIn(0, 100000))
            .putString(QUESTS, titles.joinToString("\n"))
            .apply()
    }

    fun quests(ctx: Context): List<Quest> {
        val out = ArrayList<Quest>()
        val goal = stepGoal(ctx)
        if (goal > 0) out += Quest(0, "%,d Schritte".format(Locale.GERMAN, goal), goal)
        customTitles(ctx).forEachIndexed { i, s -> out += Quest(i + 1, s) }
        return out
    }

    private fun doneIds(ctx: Context): MutableSet<Int> {
        val raw = Prefs.sp(ctx).getString(DONE, "") ?: ""
        val (d, ids) = raw.split('|').let { (it.getOrNull(0) ?: "") to (it.getOrNull(1) ?: "") }
        if (d != today()) return mutableSetOf()
        return ids.split(',').mapNotNull { it.toIntOrNull() }.toMutableSet()
    }

    fun isDone(ctx: Context, q: Quest, steps: Int): Boolean =
        if (q.auto) steps >= q.stepsGoal else q.id in doneIds(ctx)

    fun toggle(ctx: Context, q: Quest) {
        if (q.auto) return
        val ids = doneIds(ctx)
        val nowDone = if (q.id in ids) { ids.remove(q.id); false } else { ids.add(q.id); true }
        Prefs.sp(ctx).edit()
            .putString(DONE, today() + "|" + ids.joinToString(","))
            .putLong(XP, (xp(ctx) + if (nowDone) 50 else -50).coerceAtLeast(0))
            .apply()
    }

    fun xp(ctx: Context): Long = Prefs.sp(ctx).getLong(XP, 0L)

    /** Give XP for today's steps and the all-quests bonus. Returns true if something changed. */
    fun settle(ctx: Context, steps: Int): Boolean {
        val sp = Prefs.sp(ctx)
        val d = today()
        var changed = false
        var xp = xp(ctx)
        val e = sp.edit()
        if (steps >= 0) {
            val raw = (sp.getString(STEP_XP, "") ?: "").split('|')
            val given = if (raw.getOrNull(0) == d) raw.getOrNull(1)?.toIntOrNull() ?: 0 else 0
            val due = (steps / 100).coerceAtMost(100)
            if (due > given) { xp += due - given; e.putString(STEP_XP, "$d|$due"); changed = true }
        }
        val qs = quests(ctx)
        if (qs.isNotEmpty() && qs.all { isDone(ctx, it, steps.coerceAtLeast(0)) } && sp.getString(BONUS, "") != d) {
            xp += 100
            e.putString(BONUS, d)
            val yesterday = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(
                Calendar.getInstance().apply { add(Calendar.DAY_OF_YEAR, -1) }.time
            )
            val streak = if (sp.getString(STREAK_DAY, "") == yesterday) sp.getInt(STREAK, 0) + 1 else 1
            e.putInt(STREAK, streak).putString(STREAK_DAY, d)
            changed = true
        }
        if (changed) e.putLong(XP, xp).apply()
        return changed
    }

    /** Days in a row with every quest done (0 if the chain broke before yesterday). */
    fun streak(ctx: Context): Int {
        val sp = Prefs.sp(ctx)
        val day = sp.getString(STREAK_DAY, "") ?: ""
        val yesterday = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(
            Calendar.getInstance().apply { add(Calendar.DAY_OF_YEAR, -1) }.time
        )
        return if (day == today() || day == yesterday) sp.getInt(STREAK, 0) else 0
    }

    data class Level(val level: Int, val into: Long, val need: Long, val rank: String)

    fun level(xp: Long): Level {
        var lv = 1
        var rest = xp
        while (rest >= need(lv)) { rest -= need(lv); lv++ }
        val rank = when {
            lv >= 50 -> "S"; lv >= 35 -> "A"; lv >= 20 -> "B"; lv >= 10 -> "C"; lv >= 5 -> "D"; else -> "E"
        }
        return Level(lv, rest, need(lv), rank)
    }

    private fun need(lv: Int): Long = 300L + 120L * lv
}

// ======================================================================== weather

/** Current weather from Open-Meteo (free, no account). Only the city name / coordinates are sent. */
object Weather {
    data class Now(val tempC: Int, val code: Int, val place: String)

    private const val CITY = "weather_city"
    private const val CACHE = "weather_cache"     // "time|temp|code|place"
    private val main = Handler(Looper.getMainLooper())
    @Volatile private var fetching = false

    fun city(ctx: Context): String = Prefs.sp(ctx).getString(CITY, null)?.takeIf { it.isNotBlank() } ?: "Wien"
    fun setCity(ctx: Context, c: String) { Prefs.put(ctx, CITY, c.trim()); Prefs.put(ctx, CACHE, "") }

    fun cached(ctx: Context): Now? {
        val p = (Prefs.sp(ctx).getString(CACHE, "") ?: "").split('|')
        if (p.size < 4) return null
        return Now(p[1].toIntOrNull() ?: return null, p[2].toIntOrNull() ?: 0, p[3])
    }

    fun refresh(ctx: Context, onDone: () -> Unit) {
        val p = (Prefs.sp(ctx).getString(CACHE, "") ?: "").split('|')
        val age = System.currentTimeMillis() - (p.getOrNull(0)?.toLongOrNull() ?: 0L)
        if (age < 30 * 60 * 1000L || fetching) return
        fetching = true
        val app = ctx.applicationContext
        val city = city(app)
        Thread {
            try {
                val q = URLEncoder.encode(city, "UTF-8")
                val geo = JSONObject(get("https://geocoding-api.open-meteo.com/v1/search?name=$q&count=1&language=de"))
                val r = geo.getJSONArray("results").getJSONObject(0)
                val lat = r.getDouble("latitude")
                val lon = r.getDouble("longitude")
                val place = r.optString("name", city)
                val w = JSONObject(get(
                    "https://api.open-meteo.com/v1/forecast?latitude=$lat&longitude=$lon&current=temperature_2m,weather_code"
                )).getJSONObject("current")
                val temp = Math.round(w.getDouble("temperature_2m")).toInt()
                val code = w.getInt("weather_code")
                Prefs.put(app, CACHE, "${System.currentTimeMillis()}|$temp|$code|$place")
                main.post(onDone)
            } catch (_: Exception) {
            } finally { fetching = false }
        }.start()
    }

    private fun get(url: String): String {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 8000
        c.readTimeout = 8000
        return c.inputStream.bufferedReader().use { it.readText() }.also { c.disconnect() }
    }

    fun describe(code: Int): String = when (code) {
        0 -> "Klar"
        1, 2 -> "Leicht bewölkt"
        3 -> "Bewölkt"
        45, 48 -> "Nebel"
        in 51..57 -> "Niesel"
        in 61..67, in 80..82 -> "Regen"
        in 71..77, 85, 86 -> "Schnee"
        in 95..99 -> "Gewitter"
        else -> "—"
    }

    fun glyph(code: Int): Glyph = when (code) {
        0 -> Glyph.SUN
        1, 2, 3 -> Glyph.CLOUD
        45, 48 -> Glyph.FOG
        in 51..67, in 80..82 -> Glyph.RAIN
        in 71..77, 85, 86 -> Glyph.SNOW
        in 95..99 -> Glyph.STORM
        else -> Glyph.CLOUD
    }
}

// ======================================================================== calendar

object Agenda {
    data class Item(val time: String, val title: String, val begin: Long)

    fun permitted(ctx: Context) =
        ctx.checkSelfPermission(android.Manifest.permission.READ_CALENDAR) == PackageManager.PERMISSION_GRANTED

    /** Today's remaining events (all-day ones first), at most [limit]. */
    fun today(ctx: Context, limit: Int = 3): List<Item> {
        if (!permitted(ctx)) return emptyList()
        val cal = Calendar.getInstance()
        val now = cal.timeInMillis
        cal.set(Calendar.HOUR_OF_DAY, 0); cal.set(Calendar.MINUTE, 0); cal.set(Calendar.SECOND, 0)
        val start = cal.timeInMillis
        val end = start + 24L * 3600 * 1000
        val uri = CalendarContract.Instances.CONTENT_URI.buildUpon()
            .appendPath(start.toString()).appendPath(end.toString()).build()
        val out = ArrayList<Item>()
        val fmt = SimpleDateFormat("HH:mm", Locale.GERMAN)
        try {
            ctx.contentResolver.query(
                uri,
                arrayOf(CalendarContract.Instances.TITLE, CalendarContract.Instances.BEGIN,
                    CalendarContract.Instances.END, CalendarContract.Instances.ALL_DAY),
                null, null, "${CalendarContract.Instances.ALL_DAY} DESC, ${CalendarContract.Instances.BEGIN} ASC"
            )?.use { c ->
                while (c.moveToNext() && out.size < limit) {
                    val title = c.getString(0) ?: continue
                    val begin = c.getLong(1)
                    val endT = c.getLong(2)
                    val allDay = c.getInt(3) == 1
                    if (!allDay && endT < now) continue
                    out += Item(if (allDay) "Tag" else fmt.format(Date(begin)), title, begin)
                }
            }
        } catch (_: Exception) { }
        return out
    }
}

// ======================================================================== media

object Media {
    /** The app that is playing (or last played) media; needs notification access. */
    fun active(ctx: Context): MediaController? = try {
        if (NotifyListener.instance == null) null else {
            val msm = ctx.getSystemService(Context.MEDIA_SESSION_SERVICE) as MediaSessionManager
            val sessions = msm.getActiveSessions(ComponentName(ctx, NotifyListener::class.java))
            sessions.firstOrNull { it.playbackState?.state == PlaybackState.STATE_PLAYING }
                ?: sessions.firstOrNull { it.metadata != null }
        }
    } catch (_: Exception) { null }
}
