package com.ascend.ui

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
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

/**
 * Keyboard drawn entirely on a canvas so every pixel follows the Ascend theme:
 * suggestion bar, glass keys with an accent edge, glowing Enter key and a key preview bubble.
 */
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
        const val DIGIT = -11   // number-row digit: typed like a letter, drawn smaller

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
        var shader: Shader? = null
        val typesText get() = code == 0 || code == DIGIT
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

    private var suggestions: List<String> = emptyList()
    private val sugRects = Array(3) { RectF() }
    private var sugSlots: Array<String?> = arrayOfNulls(3)
    private var sugDown = -1

    private val d = resources.displayMetrics.density
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val glyph = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND
    }
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER }
    private val hint = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.RIGHT }
    private val grid = Paint().apply { strokeWidth = 1f }
    private val path = Path()
    private val tmp = RectF()
    private var bgShader: Shader? = null
    private var glowShader: Shader? = null
    private var edgeShader: Shader? = null

    private val longPress = Runnable { onLong() }
    private val repeatDelete = object : Runnable {
        override fun run() { ime.delete(); refreshSuggestions(); postDelayed(this, 55) }
    }

    init {
        // Shadow layers (the glow) on shapes need a software-friendly path on older Android versions.
        if (Build.VERSION.SDK_INT < 28) setLayerType(LAYER_TYPE_SOFTWARE, null)
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
        refreshSuggestions()
        requestLayout()
    }

    fun updateAutoCaps() {
        if (mode != Mode.LETTERS || shift == 2) return
        val want = if (ime.capsMode()) 1 else 0
        if (want != shift) { shift = want; invalidate() }
    }

    fun refreshSuggestions() {
        val list = if (mode == Mode.LETTERS && ime.suggestionsAllowed()) Suggest.complete(ime.currentWord()) else emptyList()
        if (list != suggestions) { suggestions = list; invalidate() }
    }

    private fun rebuild() {
        numRowActive = mode == Mode.LETTERS && Prefs.on(context, Prefs.KB_NUMROW)
        rows = when (mode) {
            Mode.LETTERS -> letterRows()
            Mode.SYM -> symRows()
            Mode.SYM2 -> sym2Rows()
            Mode.EMOJI -> emojiRows()
        }
        if (mode != Mode.LETTERS) suggestions = emptyList()
        layoutKeys()
        invalidate()
    }

    private fun bottomRow(label: String, code: Int) = listOf(
        Key(label, code, 1.5f), Key("☺", EMOJI, 1f), Key(",", 0, 1f, "!"),
        Key(" ", SPACE, 4f), Key(".", 0, 1f, "?"), Key("↵", ENTER, 1.5f)
    )

    private fun letterRows(): List<List<Key>> {
        val qwertz = Prefs.int(context, Prefs.KB_LAYOUT, 1) == 1
        val r1 = if (qwertz) "qwertzuiop" else "qwertyuiop"
        val r2 = "asdfghjkl"
        val r3 = if (qwertz) "yxcvbnm" else "zxcvbnm"
        val digits = "1234567890"
        val alts = mapOf('a' to "ä", 'o' to "ö", 'u' to "ü", 's' to "ß", 'e' to "é", 'c' to "ç", 'n' to "ñ", 'i' to "í")
        val out = ArrayList<List<Key>>()
        if (numRowActive) out += digits.map { Key(it.toString(), DIGIT) }
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

    private val sugH get() = dp(46)
    private val topGap get() = dp(6)
    private val pad get() = dp(4)
    private val rowH get() = dp(54)
    private val numRowH get() = dp(40)
    private val keysTop get() = sugH + topGap + pad

    private fun desiredHeight(): Int {
        val numRow = Prefs.on(context, Prefs.KB_NUMROW)
        return sugH + topGap + pad * 2 + (if (numRow) numRowH else 0) + rowH * 4 + bottomInset
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
        if (width == 0 || height == 0 || rows.isEmpty()) return
        val w = width.toFloat()
        val h = height.toFloat()
        val unit = (width - pad * 2) / 10f
        val gap = 3f * d
        val avail = (height - keysTop - pad - bottomInset).toFloat()
        val weights = rows.indices.map { if (it == 0 && numRowActive) 0.74f else 1f }
        val total = weights.sum()
        var y = keysTop.toFloat()
        rows.forEachIndexed { i, row ->
            val rh = avail * weights[i] / total
            val units = row.sumOf { it.w.toDouble() }.toFloat()
            var x = pad + (10f - units) * unit / 2f
            for (k in row) {
                k.rect.set(x + gap, y + gap, x + k.w * unit - gap, y + rh - gap)
                k.shader = LinearGradient(
                    0f, k.rect.top, 0f, k.rect.bottom,
                    if (t.light) Color.WHITE else t.surfaceHi.withAlpha(242),
                    if (t.light) t.surfaceHi else t.surface.withAlpha(242),
                    Shader.TileMode.CLAMP
                )
                x += k.w * unit
            }
            y += rh
        }
        bgShader = LinearGradient(0f, 0f, 0f, h, t.bg, if (t.light) t.surfaceHi else t.surface, Shader.TileMode.CLAMP)
        glowShader = RadialGradient(
            w / 2f, h + 40f * d, w * 0.8f,
            t.accent.withAlpha(if (t.light) 46 else 56), t.accent.withAlpha(0), Shader.TileMode.CLAMP
        )
        edgeShader = LinearGradient(
            0f, 0f, w, 0f, intArrayOf(t.accent.withAlpha(0), t.accent, t.accent.withAlpha(0)), null, Shader.TileMode.CLAMP
        )
    }

    // ---------------------------------------------------------------- drawing

    override fun onDraw(c: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        if (bgShader == null) layoutKeys()

        // Backdrop: gradient, accent glow from below, faint HUD grid, top edge line and corner brackets.
        fill.shader = bgShader; c.drawRect(0f, 0f, w, h, fill)
        fill.shader = glowShader; c.drawRect(0f, 0f, w, h, fill)
        fill.shader = null
        grid.color = t.accent.withAlpha(if (t.light) 13 else 12)
        val step = 24f * d
        var gx = step / 2f
        while (gx < w) { c.drawLine(gx, sugH.toFloat(), gx, h, grid); gx += step }
        var gy = sugH + step / 2f
        while (gy < h) { c.drawLine(0f, gy, w, gy, grid); gy += step }
        fill.shader = edgeShader; c.drawRect(0f, 0f, w, 1.5f * d, fill); fill.shader = null
        glyph.color = t.accent; glyph.strokeWidth = 2f * d
        val b = 6f * d
        val bl = 12f * d
        path.reset()
        path.moveTo(b, b + bl); path.lineTo(b, b); path.lineTo(b + bl, b)
        path.moveTo(w - b - bl, b); path.lineTo(w - b, b); path.lineTo(w - b, b + bl)
        c.drawPath(path, glyph)

        drawSuggestions(c, w)

        for ((ri, row) in rows.withIndex()) for (k in row) drawKey(c, k, ri)
        cur?.let { if (it.typesText && !longFired) drawPreview(c, it) }
    }

    private fun drawSuggestions(c: Canvas, w: Float) {
        val mid = sugH / 2f
        // HUD diamond at the left.
        fill.color = t.accent
        c.save(); c.translate(22f * d, mid); c.rotate(45f); c.drawRect(-3.5f * d, -3.5f * d, 3.5f * d, 3.5f * d, fill); c.restore()
        fill.color = t.accent.withAlpha(46)
        c.drawRect(16f * d, sugH - 0.5f * d, w - 16f * d, sugH + 0.5f * d, fill)

        // Best word in the middle, runners-up on either side.
        sugSlots = arrayOf(suggestions.getOrNull(1), suggestions.getOrNull(0), suggestions.getOrNull(2))
        val left = 44f * d
        val sw = (w - left - 16f * d) / 3f
        for (i in 0..2) {
            sugRects[i].set(left + sw * i, 0f, left + sw * (i + 1), sugH.toFloat())
            val s = sugSlots[i] ?: continue
            val cx = sugRects[i].centerX()
            val isMid = i == 1
            if (i == sugDown) {
                fill.color = t.accent.withAlpha(40)
                tmp.set(sugRects[i]); tmp.inset(4f * d, 7f * d)
                c.drawRoundRect(tmp, 10f * d, 10f * d, fill)
            }
            text.typeface = Fonts.medium
            text.textSize = (if (isMid) 18f else 16f) * d
            text.letterSpacing = 0f
            text.color = if (isMid) t.accent else t.text
            if (isMid && !t.light) text.setShadowLayer(10f * d, 0f, 0f, t.accent) else text.clearShadowLayer()
            val label = TextUtilsCompat.ellipsize(s, text, sw - 12f * d)
            c.drawText(label, cx, centerBaseline(mid, text), text)
            text.clearShadowLayer()
            if (isMid) { fill.color = t.accent; c.drawRect(cx - 14f * d, mid + 12f * d, cx + 14f * d, mid + 14f * d, fill) }
            if (i > 0 && (sugSlots[i - 1] != null)) {
                fill.color = t.accent.withAlpha(90)
                c.drawRect(sugRects[i].left, mid - 9f * d, sugRects[i].left + 1f * d, mid + 9f * d, fill)
            }
        }
    }

    private fun drawKey(c: Canvas, k: Key, rowIndex: Int) {
        val r = 10f * d
        val pressed = k === cur
        val special = !k.typesText && k.code != SPACE && k.code != ENTER

        when {
            k.code == ENTER -> {
                fill.shader = LinearGradient(k.rect.left, k.rect.top, k.rect.right, k.rect.bottom,
                    t.accent, if (t.light) t.accent else t.accentSoft, Shader.TileMode.CLAMP)
                fill.setShadowLayer((if (t.light) 6f else 14f) * d, 0f, 0f, t.accent)
            }
            pressed -> {
                fill.color = t.accent.withAlpha(if (t.light) 77 else 107)
                fill.setShadowLayer(12f * d, 0f, 0f, t.accent)
            }
            special && k.code == SHIFT && shift > 0 -> fill.color = t.accent.withAlpha(if (shift == 2) 110 else 70)
            special -> fill.color = t.accent.withAlpha(if (t.light) 31 else 36)
            else -> fill.shader = k.shader
        }
        c.drawRoundRect(k.rect, r, r, fill)
        fill.shader = null
        fill.clearShadowLayer()

        stroke.strokeWidth = if (pressed) 1.3f * d else 1f * d
        stroke.color = t.accent.withAlpha(when { pressed -> 255; special -> 140; t.light -> 56; else -> 51 })
        c.drawRoundRect(k.rect, r, r, stroke)
        if (!t.light && k.code != ENTER) {
            stroke.color = Color.argb(18, 255, 255, 255)
            c.drawLine(k.rect.left + 8f * d, k.rect.top + 1f * d, k.rect.right - 8f * d, k.rect.top + 1f * d, stroke)
        }
        drawLabel(c, k, rowIndex)
    }

    private fun drawLabel(c: Canvas, k: Key, rowIndex: Int) {
        val cx = k.rect.centerX()
        val cy = k.rect.centerY()
        text.letterSpacing = 0f
        text.clearShadowLayer()
        glyph.strokeWidth = 1.8f * d
        glyph.color = t.accent
        when (k.code) {
            0, DIGIT -> {
                var label = k.label
                if (mode == Mode.LETTERS && shift > 0 && k.code == 0) label = label.uppercase()
                text.typeface = if (mode == Mode.EMOJI) Fonts.regular else Fonts.medium
                text.textSize = when {
                    mode == Mode.EMOJI -> 24f
                    k.code == DIGIT -> 17f
                    label.length > 1 -> 16f
                    else -> 22f
                } * d
                text.color = if (k.code == DIGIT) t.textDim else t.text
                c.drawText(label, cx, centerBaseline(cy, text), text)
                val alt = k.alt
                if (alt != null && mode == Mode.LETTERS) {
                    hint.typeface = Fonts.medium
                    hint.textSize = 11f * d
                    hint.color = t.accent.withAlpha(205)
                    c.drawText(alt, k.rect.right - 5f * d, k.rect.top + 12f * d, hint)
                }
            }
            SHIFT -> {
                path.reset()
                path.moveTo(cx, cy - 9f * d); path.lineTo(cx + 9f * d, cy + 1f * d); path.lineTo(cx + 4f * d, cy + 1f * d)
                path.lineTo(cx + 4f * d, cy + 8f * d); path.lineTo(cx - 4f * d, cy + 8f * d); path.lineTo(cx - 4f * d, cy + 1f * d)
                path.lineTo(cx - 9f * d, cy + 1f * d); path.close()
                if (shift == 2) { fill.color = t.accent; c.drawPath(path, fill) }
                c.drawPath(path, glyph)
                if (shift == 2) c.drawLine(cx - 5f * d, cy + 12f * d, cx + 5f * d, cy + 12f * d, glyph)
            }
            DEL -> {
                path.reset()
                path.moveTo(cx - 12f * d, cy); path.lineTo(cx - 5f * d, cy - 8f * d); path.lineTo(cx + 12f * d, cy - 8f * d)
                path.lineTo(cx + 12f * d, cy + 8f * d); path.lineTo(cx - 5f * d, cy + 8f * d); path.close()
                path.moveTo(cx - 1f * d, cy - 3.5f * d); path.lineTo(cx + 6f * d, cy + 3.5f * d)
                path.moveTo(cx + 6f * d, cy - 3.5f * d); path.lineTo(cx - 1f * d, cy + 3.5f * d)
                c.drawPath(path, glyph)
            }
            ENTER -> {
                val on = t.onAccent
                val label = ime.enterLabel()
                if (label == null) {
                    glyph.color = on; glyph.strokeWidth = 2f * d
                    path.reset()
                    path.moveTo(cx + 9f * d, cy - 7f * d); path.lineTo(cx + 9f * d, cy + 2f * d); path.lineTo(cx - 8f * d, cy + 2f * d)
                    path.moveTo(cx - 3f * d, cy - 3f * d); path.lineTo(cx - 8f * d, cy + 2f * d); path.lineTo(cx - 3f * d, cy + 7f * d)
                    c.drawPath(path, glyph)
                } else {
                    text.typeface = Fonts.mono; text.textSize = 10f * d; text.letterSpacing = 0.1f; text.color = on
                    c.drawText(label, cx, centerBaseline(cy, text), text)
                }
            }
            SPACE -> {
                text.typeface = Fonts.mono
                text.color = t.textDim
                if (mode == Mode.EMOJI) {
                    text.textSize = 11f * d
                    c.drawText(k.label, cx, centerBaseline(cy, text), text)
                } else {
                    text.textSize = 9f * d
                    text.letterSpacing = 0.3f
                    c.drawText(t.name.uppercase(), cx, centerBaseline(cy - 3f * d, text), text)
                    fill.color = t.accent
                    c.drawRect(cx - 14f * d, cy + 8f * d, cx + 14f * d, cy + 10f * d, fill)
                }
            }
            EMOJI -> {
                glyph.strokeWidth = 1.6f * d
                c.drawCircle(cx, cy, 9f * d, glyph)
                path.reset()
                path.moveTo(cx - 5f * d, cy + 2f * d); path.quadTo(cx, cy + 7f * d, cx + 5f * d, cy + 2f * d)
                c.drawPath(path, glyph)
                fill.color = t.accent
                c.drawRect(cx - 4.5f * d, cy - 4f * d, cx - 2.5f * d, cy - 1f * d, fill)
                c.drawRect(cx + 2.5f * d, cy - 4f * d, cx + 4.5f * d, cy - 1f * d, fill)
            }
            PG_PREV, PG_NEXT -> {
                text.typeface = Fonts.medium; text.textSize = 24f * d; text.color = t.accent
                c.drawText(k.label, cx, centerBaseline(cy, text), text)
            }
            else -> {
                text.typeface = Fonts.mono; text.textSize = 10f * d; text.letterSpacing = 0.06f; text.color = t.accent
                c.drawText(k.label, cx, centerBaseline(cy, text), text)
            }
        }
    }

    /** Enlarged copy of the pressed key, floating above it. */
    private fun drawPreview(c: Canvas, k: Key) {
        val bw = k.rect.width() * 1.45f
        val bh = 62f * d
        val bx = (k.rect.centerX() - bw / 2f).coerceIn(4f * d, width - bw - 4f * d)
        val by = (k.rect.top - bh - 6f * d).coerceAtLeast(2f * d)
        tmp.set(bx, by, bx + bw, by + bh)
        fill.color = if (t.light) Color.WHITE else t.surfaceHi
        fill.setShadowLayer((if (t.light) 8f else 20f) * d, 0f, 0f, t.accent)
        c.drawRoundRect(tmp, 12f * d, 12f * d, fill)
        fill.clearShadowLayer()
        stroke.strokeWidth = 1.2f * d; stroke.color = t.accent
        c.drawRoundRect(tmp, 12f * d, 12f * d, stroke)
        var label = k.label
        if (mode == Mode.LETTERS && shift > 0 && k.code == 0) label = label.uppercase()
        text.typeface = if (mode == Mode.EMOJI) Fonts.regular else Fonts.medium
        text.textSize = 36f * d
        text.letterSpacing = 0f
        text.color = t.accent
        if (!t.light) text.setShadowLayer(8f * d, 0f, 0f, t.accent)
        c.drawText(label, tmp.centerX(), centerBaseline(tmp.centerY() + 1f * d, text), text)
        text.clearShadowLayer()
    }

    private fun centerBaseline(cy: Float, p: Paint): Float {
        val fm = p.fontMetrics
        return cy - (fm.ascent + fm.descent) / 2f
    }

    // ---------------------------------------------------------------- touch

    private fun keyAt(x: Float, y: Float): Key? {
        val slop = 3f * d
        for (row in rows) for (k in row) {
            if (y >= k.rect.top - slop && y <= k.rect.bottom + slop &&
                x >= k.rect.left - slop && x <= k.rect.right + slop) return k
        }
        return null
    }

    private fun sugAt(x: Float, y: Float): Int {
        if (y > sugH) return -1
        for (i in 0..2) if (sugSlots[i] != null && x >= sugRects[i].left && x <= sugRects[i].right) return i
        return -1
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> {
                cur?.let { if (!longFired) release(it) }
                cancelTimers()
                val i = e.actionIndex
                val x = e.getX(i)
                val y = e.getY(i)
                if (e.actionMasked == MotionEvent.ACTION_DOWN && y < sugH) {
                    sugDown = sugAt(x, y)
                    cur = null
                    curPointer = e.getPointerId(i)
                    invalidate()
                    return true
                }
                val k = keyAt(x, y)
                cur = k
                curPointer = e.getPointerId(i)
                longFired = false
                if (k != null) press(k)
                invalidate()
            }
            MotionEvent.ACTION_MOVE -> {
                if (sugDown >= 0) return true
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
                if (sugDown >= 0) {
                    val i = e.actionIndex
                    val hit = sugAt(e.getX(i), e.getY(i))
                    val word = if (hit == sugDown) sugSlots[hit] else null
                    sugDown = -1
                    if (word != null) {
                        haptic()
                        ime.pickSuggestion(word)
                        updateAutoCaps()
                        refreshSuggestions()
                    }
                    invalidate()
                    return true
                }
                if (e.getPointerId(e.actionIndex) == curPointer) {
                    cur?.let { if (!longFired) release(it) }
                    cancelTimers()
                    cur = null
                    curPointer = -1
                    invalidate()
                }
            }
            MotionEvent.ACTION_CANCEL -> {
                cancelTimers(); cur = null; curPointer = -1; sugDown = -1; invalidate()
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
            refreshSuggestions()
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
        refreshSuggestions()
    }

    private fun release(k: Key) {
        when (k.code) {
            0, DIGIT -> commitChar(k.label)
            SHIFT -> {
                val now = System.currentTimeMillis()
                shift = when {
                    shift == 1 && now - lastShiftTap < 350 -> 2
                    shift == 0 -> 1
                    else -> 0
                }
                lastShiftTap = now
            }
            ENTER -> { ime.enter(); refreshSuggestions() }
            SPACE -> if (mode != Mode.EMOJI) { ime.space(); updateAutoCaps(); refreshSuggestions() }
            TO_SYM -> { mode = Mode.SYM; rebuild() }
            TO_SYM2 -> { mode = Mode.SYM2; rebuild() }
            TO_ABC -> { mode = Mode.LETTERS; rebuild(); updateAutoCaps(); refreshSuggestions() }
            EMOJI -> { mode = Mode.EMOJI; emojiPage = 0; rebuild() }
            PG_PREV -> { emojiPage = (emojiPage - 1 + pages()) % pages(); rebuild() }
            PG_NEXT -> { emojiPage = (emojiPage + 1) % pages(); rebuild() }
        }
        invalidate()
    }
}

/** Shortens text with "…" so it fits a width; avoids android.text layout objects in onDraw. */
private object TextUtilsCompat {
    fun ellipsize(s: String, p: Paint, maxW: Float): String {
        if (p.measureText(s) <= maxW) return s
        var end = s.length
        while (end > 1 && p.measureText(s, 0, end) + p.measureText("…") > maxW) end--
        return s.substring(0, end) + "…"
    }
}
