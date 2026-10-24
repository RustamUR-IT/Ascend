package com.ascend.ui

import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.os.Build
import android.text.InputType
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.WindowInsets
import android.view.inputmethod.EditorInfo
import kotlin.math.ceil

/** Keyboard drawn entirely on a canvas so every pixel follows the Ascend theme. */
class KeyboardView(private val ime: KeyboardService) : View(ime) {

    private companion object {
        const val SHIFT = -1
        const val TO_SYM = -2
        const val TO_ABC = -3
        const val ENTER = -4
        const val DEL = -5
        const val SPACE = -6
        const val TO_SYM2 = -7
        const val EMOJI = -8
        const val PG_PREV = -9
        const val PG_NEXT = -10

        val EMOJIS = listOf(
            "😀", "😂", "🤣", "😊", "😍", "🥰", "😘", "😎", "🤔", "🙄", "😏", "😅", "😭", "😢", "😡", "🥺",
            "😴", "🤯", "😳", "🤩", "😇", "🙃", "😬", "🤗", "🤫", "😤", "🥲", "😮", "🤤", "😈", "💀", "👻",
            "👍", "👎", "👏", "🙏", "💪", "👊", "✌️", "🤝", "👋", "🤞", "👌", "🫡", "👀", "🧠", "🫶", "✍️",
            "❤️", "💜", "🖤", "🤍", "💙", "💔", "💯", "✨", "🔥", "⚡", "💥", "⭐", "🌙", "☀️", "🌈", "❄️",
            "🎉", "🎂", "🎁", "🏆", "🎮", "🎧", "📚", "💻", "📱", "⏰", "📍", "✅", "❌", "⚠️", "❓", "💤",
            "🍕", "🍔", "🍟", "🍜", "🍣", "☕", "🍺", "🥂", "🍫", "🍎", "🐶", "🐱", "🦊", "🐉", "🌹", "🌸"
        )
        const val EMOJI_PER_PAGE = 32
    }

    private class Key(val label: String, val code: Int = 0, val w: Float = 1f, val alt: String? = null) {
        val rect = RectF()
    }

    private enum class Mode { LETTERS, SYM, SYM2, EMOJI }

    private var t = Prefs.theme(ime)
    private var mode = Mode.LETTERS
    private var shift = 0              // 0 off, 1 next letter, 2 caps lock
    private var lastShiftTap = 0L
    private var emojiPage = 0
    private var rows: List<List<Key>> = emptyList()
    private var numRowActive = false
    private var bottomInset = 0

    private var cur: Key? = null
    private var curPointer = -1
    private var longFired = false

    private val keyPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER }
    private val hintPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.RIGHT }
    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG)

    private val longPress = Runnable { onLong() }
    private val repeatDelete = object : Runnable {
        override fun run() { ime.delete(); postDelayed(this, 55) }
    }

    init {
        rebuild()
    }

    // ---------------------------------------------------------------- state

    fun reset(info: EditorInfo) {
        t = Prefs.theme(context)
        val cls = info.inputType and InputType.TYPE_MASK_CLASS
        mode = if (cls == InputType.TYPE_CLASS_NUMBER || cls == InputType.TYPE_CLASS_PHONE ||
            cls == InputType.TYPE_CLASS_DATETIME) Mode.SYM else Mode.LETTERS
        shift = 0
        rebuild()
        updateAutoCaps()
        requestLayout()
    }

    fun updateAutoCaps() {
        if (mode != Mode.LETTERS || shift == 2) return
        val want = if (ime.capsMode()) 1 else 0
        if (want != shift) { shift = want; invalidate() }
    }

    private fun rebuild() {
        numRowActive = mode == Mode.LETTERS && Prefs.on(context, Prefs.KB_NUMROW)
        rows = when (mode) {
            Mode.LETTERS -> letterRows()
            Mode.SYM -> symRows()
            Mode.SYM2 -> sym2Rows()
            Mode.EMOJI -> emojiRows()
        }
        layoutKeys()
        invalidate()
    }

    private fun bottomRow(label: String, code: Int) = listOf(
        Key(label, code, 1.5f), Key("☺", EMOJI, 1f), Key(",", 0, 1f, "!"),
        Key(" ", SPACE, 4f), Key(".", 0, 1f, "?"), Key("↵", ENTER, 1.5f)
    )

    private fun letterRows(): List<List<Key>> {
        val qwertz = Prefs.int(context, Prefs.KB_LAYOUT, 0) == 1
        val r1 = if (qwertz) "qwertzuiop" else "qwertyuiop"
        val r2 = "asdfghjkl"
        val r3 = if (qwertz) "yxcvbnm" else "zxcvbnm"
        val digits = "1234567890"
        val alts = mapOf('a' to "ä", 'o' to "ö", 'u' to "ü", 's' to "ß", 'e' to "é", 'c' to "ç", 'n' to "ñ", 'i' to "í")
        val out = ArrayList<List<Key>>()
        if (numRowActive) out += digits.map { Key(it.toString()) }
        out += r1.mapIndexed { i, ch ->
            Key(ch.toString(), alt = alts[ch] ?: if (!numRowActive) digits[i].toString() else null)
        }
        out += r2.map { Key(it.toString(), alt = alts[it]) }
        out += listOf(Key("⇧", SHIFT, 1.5f)) + r3.map { Key(it.toString(), alt = alts[it]) } + Key("⌫", DEL, 1.5f)
        out += bottomRow("?123", TO_SYM)
        return out
    }

    private fun symRows(): List<List<Key>> = listOf(
        "1234567890".map { Key(it.toString()) },
        listOf("@", "#", "€", "%", "&", "-", "+", "(", ")", "/").map { Key(it) },
        listOf(Key("=\\<", TO_SYM2, 1.5f)) + listOf("*", "\"", "'", ":", ";", "!", "?").map { Key(it) } + Key("⌫", DEL, 1.5f),
        bottomRow("ABC", TO_ABC)
    )

    private fun sym2Rows(): List<List<Key>> = listOf(
        listOf("~", "`", "|", "•", "√", "π", "÷", "×", "¶", "∆").map { Key(it) },
        listOf("£", "$", "¥", "^", "°", "=", "{", "}", "[", "]").map { Key(it) },
        listOf(Key("?123", TO_SYM, 1.5f)) + listOf("\\", "<", ">", "_", "©", "®", "™").map { Key(it) } + Key("⌫", DEL, 1.5f),
        bottomRow("ABC", TO_ABC)
    )

    private fun pages() = ceil(EMOJIS.size / EMOJI_PER_PAGE.toFloat()).toInt()

    private fun emojiRows(): List<List<Key>> {
        val start = emojiPage * EMOJI_PER_PAGE
        val page = EMOJIS.subList(start, minOf(start + EMOJI_PER_PAGE, EMOJIS.size))
        val out = page.chunked(8).map { r -> r.map { Key(it, 0, 1.25f) } }.toMutableList()
        out += listOf(
            Key("ABC", TO_ABC, 1.5f), Key("‹", PG_PREV, 1.5f), Key("${emojiPage + 1}/${pages()}", SPACE, 3.5f),
            Key("›", PG_NEXT, 1.5f), Key("⌫", DEL, 2f)
        )
        return out
    }

    // ---------------------------------------------------------------- layout

    private val topBar get() = dp(10)
    private val pad get() = dp(4)
    private val rowH get() = dp(54)
    private val numRowH get() = dp(44)

    private fun desiredHeight(): Int {
        val numRow = Prefs.on(context, Prefs.KB_NUMROW)
        return topBar + pad * 2 + (if (numRow) numRowH else 0) + rowH * 4 + bottomInset
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        setMeasuredDimension(MeasureSpec.getSize(widthMeasureSpec), desiredHeight())
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) = layoutKeys()

    override fun onApplyWindowInsets(insets: WindowInsets): WindowInsets {
        val b = if (Build.VERSION.SDK_INT >= 30) insets.getInsets(WindowInsets.Type.navigationBars()).bottom else 0
        if (b != bottomInset) { bottomInset = b; requestLayout() }
        return super.onApplyWindowInsets(insets)
    }

    private fun layoutKeys() {
        if (width == 0 || rows.isEmpty()) return
        val unit = (width - pad * 2) / 10f
        val gap = context.dpf(2.6f)
        val avail = (height - topBar - pad * 2 - bottomInset).toFloat()
        val weights = rows.indices.map { if (it == 0 && numRowActive) 0.8f else 1f }
        val total = weights.sum()
        var y = (topBar + pad).toFloat()
        rows.forEachIndexed { i, row ->
            val rh = avail * weights[i] / total
            val units = row.sumOf { it.w.toDouble() }.toFloat()
            var x = pad + (10f - units) * unit / 2f
            for (k in row) {
                k.rect.set(x + gap, y + gap, x + k.w * unit - gap, y + rh - gap)
                x += k.w * unit
            }
            y += rh
        }
        textPaint.shader = null
        linePaint.shader = LinearGradient(
            0f, 0f, width.toFloat(), 0f,
            intArrayOf(t.accent.withAlpha(0), t.accent, t.accent.withAlpha(0)), null, Shader.TileMode.CLAMP
        )
    }

    // ---------------------------------------------------------------- drawing

    override fun onDraw(c: Canvas) {
        c.drawColor(t.bg)
        c.drawRect(0f, topBar / 2f - context.dpf(0.75f), width.toFloat(), topBar / 2f + context.dpf(0.75f), linePaint)
        val radius = context.dpf(8f)
        for ((ri, row) in rows.withIndex()) {
            for (k in row) {
                val pressed = k === cur
                val special = k.code != 0
                keyPaint.color = when {
                    pressed -> t.accent.withAlpha(if (t.light) 80 else 100)
                    k.code == ENTER -> t.accent
                    k.code == SHIFT && shift > 0 -> t.accent.withAlpha(70)
                    special -> t.surface
                    else -> t.surfaceHi
                }
                c.drawRoundRect(k.rect, radius, radius, keyPaint)
                strokePaint.strokeWidth = context.dpf(if (pressed) 1.4f else 1f)
                strokePaint.color = if (pressed) t.accent else t.accent.withAlpha(if (t.light) 45 else 34)
                c.drawRoundRect(k.rect, radius, radius, strokePaint)
                drawLabel(c, k, ri)
            }
        }
    }

    private fun drawLabel(c: Canvas, k: Key, rowIndex: Int) {
        var label = k.label
        var color = if (k.code == 0) t.text else t.accent
        var size = 20f
        textPaint.typeface = Fonts.regular
        textPaint.letterSpacing = 0f
        when (k.code) {
            0 -> {
                if (mode == Mode.LETTERS && shift > 0) label = label.uppercase()
                if (mode == Mode.EMOJI) size = 24f
                if (numRowActive && rowIndex == 0) size = 16f
            }
            SHIFT -> { label = if (shift == 2) "⇪" else "⇧"; color = if (shift > 0) t.accent else t.text }
            DEL -> { color = t.text; size = 18f }
            ENTER -> {
                label = ime.enterLabel()
                color = t.onAccent
                if (label.length > 1) { size = 11f; textPaint.typeface = Fonts.mono; textPaint.letterSpacing = 0.1f }
            }
            SPACE -> {
                label = if (mode == Mode.EMOJI) label else t.name.uppercase()
                color = t.textDim
                size = 10f
                textPaint.typeface = Fonts.mono
                textPaint.letterSpacing = 0.3f
            }
            EMOJI -> { size = 19f; color = t.text }
            PG_PREV, PG_NEXT -> { size = 22f }
            else -> { size = 12f; textPaint.typeface = Fonts.mono; textPaint.letterSpacing = 0.05f }
        }
        textPaint.textSize = context.dpf(size)
        textPaint.color = color
        val fm = textPaint.fontMetrics
        c.drawText(label, k.rect.centerX(), k.rect.centerY() - (fm.ascent + fm.descent) / 2f, textPaint)

        val alt = k.alt
        if (alt != null && mode == Mode.LETTERS) {
            hintPaint.textSize = context.dpf(9f)
            hintPaint.color = t.textDim
            c.drawText(alt, k.rect.right - context.dpf(5f), k.rect.top + context.dpf(12f), hintPaint)
        }
    }

    // ---------------------------------------------------------------- touch

    private fun keyAt(x: Float, y: Float): Key? {
        for (row in rows) for (k in row) {
            if (y >= k.rect.top - dp(3) && y <= k.rect.bottom + dp(3) &&
                x >= k.rect.left - dp(3) && x <= k.rect.right + dp(3)) return k
        }
        return null
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> {
                cur?.let { if (!longFired) release(it) }
                cancelTimers()
                val i = e.actionIndex
                val k = keyAt(e.getX(i), e.getY(i))
                cur = k
                curPointer = e.getPointerId(i)
                longFired = false
                if (k != null) press(k)
                invalidate()
            }
            MotionEvent.ACTION_MOVE -> {
                val i = e.findPointerIndex(curPointer)
                if (i >= 0 && !longFired) {
                    val k = keyAt(e.getX(i), e.getY(i))
                    if (k !== cur && cur?.code != DEL) {
                        cancelTimers()
                        cur = k
                        if (k != null) scheduleLong(k)
                        invalidate()
                    }
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP -> {
                if (e.getPointerId(e.actionIndex) == curPointer) {
                    cur?.let { if (!longFired) release(it) }
                    cancelTimers()
                    cur = null
                    curPointer = -1
                    invalidate()
                }
            }
            MotionEvent.ACTION_CANCEL -> {
                cancelTimers(); cur = null; curPointer = -1; invalidate()
            }
        }
        return true
    }

    private fun cancelTimers() {
        removeCallbacks(longPress)
        removeCallbacks(repeatDelete)
    }

    private fun haptic() {
        if (Prefs.on(context, Prefs.KB_HAPTIC)) performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
    }

    private fun press(k: Key) {
        haptic()
        if (k.code == DEL) {
            ime.delete()
            longFired = true   // the delete already happened; don't repeat it on release
            postDelayed(repeatDelete, 420)
            return
        }
        scheduleLong(k)
    }

    private fun scheduleLong(k: Key) {
        if (k.alt != null || k.code == SPACE || k.code == EMOJI) postDelayed(longPress, 380)
    }

    private fun onLong() {
        val k = cur ?: return
        longFired = true
        performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
        when {
            k.alt != null -> commitChar(k.alt)
            k.code == SPACE && mode != Mode.EMOJI -> ime.picker()
            k.code == EMOJI -> ime.nextKeyboard()
        }
        invalidate()
    }

    private fun commitChar(s: String) {
        val out = if (mode == Mode.LETTERS && shift > 0) s.uppercase() else s
        ime.commit(out)
        if (shift == 1) { shift = 0; invalidate() }
    }

    private fun release(k: Key) {
        when (k.code) {
            0 -> commitChar(k.label)
            SHIFT -> {
                val now = System.currentTimeMillis()
                shift = when {
                    shift == 1 && now - lastShiftTap < 350 -> 2
                    shift == 0 -> 1
                    else -> 0
                }
                lastShiftTap = now
            }
            ENTER -> ime.enter()
            SPACE -> ime.space()
            TO_SYM -> { mode = Mode.SYM; rebuild() }
            TO_SYM2 -> { mode = Mode.SYM2; rebuild() }
            TO_ABC -> { mode = Mode.LETTERS; rebuild(); updateAutoCaps() }
            EMOJI -> { mode = Mode.EMOJI; emojiPage = 0; rebuild() }
            PG_PREV -> { emojiPage = (emojiPage - 1 + pages()) % pages(); rebuild() }
            PG_NEXT -> { emojiPage = (emojiPage + 1) % pages(); rebuild() }
        }
        invalidate()
    }
}
