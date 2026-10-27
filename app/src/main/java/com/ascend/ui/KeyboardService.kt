package com.ascend.ui

import android.content.Context
import android.os.Build
import android.text.InputType
import android.inputmethodservice.InputMethodService
import android.view.KeyEvent
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager

/** The Ascend keyboard. Typing logic lives here; drawing and touch handling live in [KeyboardView]. */
class KeyboardService : InputMethodService() {
    private var kv: KeyboardView? = null

    override fun onCreate() {
        super.onCreate()
        Fonts.init(this)
        Suggest.warmUp(this)
    }

    override fun onCreateInputView(): View {
        val v = KeyboardView(this)
        kv = v
        return v
    }

    override fun onEvaluateFullscreenMode(): Boolean = false

    override fun onStartInputView(info: EditorInfo, restarting: Boolean) {
        super.onStartInputView(info, restarting)
        @Suppress("DEPRECATION")
        window.window?.navigationBarColor = Prefs.theme(this).bg
        kv?.reset(info)
    }

    override fun onFinishInputView(finishingInput: Boolean) {
        super.onFinishInputView(finishingInput)
        Suggest.save()
    }

    override fun onUpdateSelection(
        oldSelStart: Int, oldSelEnd: Int, newSelStart: Int, newSelEnd: Int,
        candidatesStart: Int, candidatesEnd: Int
    ) {
        super.onUpdateSelection(oldSelStart, oldSelEnd, newSelStart, newSelEnd, candidatesStart, candidatesEnd)
        kv?.updateAutoCaps()
        kv?.refreshSuggestions()
    }

    // ---------------------------------------------------------------- suggestions

    /** Suggestions are off in password, e-mail, URL and number fields, and when the user switched them off. */
    fun suggestionsAllowed(): Boolean {
        if (!Prefs.on(this, Prefs.KB_SUGGEST)) return false
        val type = currentInputEditorInfo?.inputType ?: return false
        if ((type and InputType.TYPE_MASK_CLASS) != InputType.TYPE_CLASS_TEXT) return false
        val variation = type and InputType.TYPE_MASK_VARIATION
        val blocked = intArrayOf(
            InputType.TYPE_TEXT_VARIATION_PASSWORD, InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD,
            InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD, InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS,
            InputType.TYPE_TEXT_VARIATION_WEB_EMAIL_ADDRESS, InputType.TYPE_TEXT_VARIATION_URI
        )
        return variation !in blocked
    }

    /** The word directly before the cursor, or "" if the cursor follows a space or punctuation. */
    fun currentWord(): String {
        val before = currentInputConnection?.getTextBeforeCursor(48, 0)?.toString() ?: return ""
        var i = before.length
        while (i > 0 && (before[i - 1].isLetter() || before[i - 1] == '\'')) i--
        return before.substring(i)
    }

    private fun atSentenceStart(beforeWord: CharSequence): Boolean {
        val t = beforeWord.trimEnd()
        return t.isEmpty() || t.last() in ".!?\n"
    }

    /** Replace the word being typed with [word] and add a space. */
    fun pickSuggestion(word: String) {
        val ic = currentInputConnection ?: return
        val typed = currentWord()
        ic.beginBatchEdit()
        if (typed.isNotEmpty()) ic.deleteSurroundingText(typed.length, 0)
        val before = ic.getTextBeforeCursor(48, 0) ?: ""
        ic.commitText("$word ", 1)
        ic.endBatchEdit()
        Suggest.learn(word, atSentenceStart(before))
    }

    /** Called just before a space, Enter or punctuation ends the current word. */
    private fun learnCurrentWord() {
        if (!suggestionsAllowed()) return
        val ic = currentInputConnection ?: return
        val word = currentWord()
        if (word.length < 2) return
        val before = ic.getTextBeforeCursor(48 + word.length, 0)?.toString() ?: return
        Suggest.learn(word, atSentenceStart(before.dropLast(word.length)))
    }

    // ---------------------------------------------------------------- typing

    fun commit(s: String) {
        if (s.length == 1 && s[0] in ".,!?;:") learnCurrentWord()
        currentInputConnection?.commitText(s, 1)
    }

    fun delete() {
        val ic = currentInputConnection ?: return
        val sel = ic.getSelectedText(0)
        if (!sel.isNullOrEmpty()) ic.commitText("", 1) else sendDownUpKeyEvents(KeyEvent.KEYCODE_DEL)
    }

    fun enter() {
        val info = currentInputEditorInfo ?: return
        val ic = currentInputConnection ?: return
        learnCurrentWord()
        val action = info.imeOptions and EditorInfo.IME_MASK_ACTION
        val noAction = (info.imeOptions and EditorInfo.IME_FLAG_NO_ENTER_ACTION) != 0 ||
            action == EditorInfo.IME_ACTION_NONE || action == EditorInfo.IME_ACTION_UNSPECIFIED
        if (noAction) sendDownUpKeyEvents(KeyEvent.KEYCODE_ENTER) else ic.performEditorAction(action)
    }

    /** Double-tapping space after a word types ". " like most phone keyboards. */
    fun space() {
        val ic = currentInputConnection ?: return
        learnCurrentWord()
        val before = ic.getTextBeforeCursor(2, 0)
        if (before != null && before.length == 2 && before[1] == ' ' && before[0].isLetterOrDigit()) {
            ic.deleteSurroundingText(1, 0)
            ic.commitText(". ", 1)
        } else {
            ic.commitText(" ", 1)
        }
    }

    fun capsMode(): Boolean {
        val info = currentInputEditorInfo ?: return false
        val ic = currentInputConnection ?: return false
        if ((info.inputType and InputType.TYPE_MASK_CLASS) != InputType.TYPE_CLASS_TEXT) return false
        return ic.getCursorCapsMode(info.inputType) != 0
    }

    /** Returns null for the plain ↵ arrow, otherwise a short label such as "SEND". */
    fun enterLabel(): String? {
        val info = currentInputEditorInfo ?: return null
        if ((info.imeOptions and EditorInfo.IME_FLAG_NO_ENTER_ACTION) != 0) return null
        return when (info.imeOptions and EditorInfo.IME_MASK_ACTION) {
            EditorInfo.IME_ACTION_GO -> "GO"
            EditorInfo.IME_ACTION_SEARCH -> "FIND"
            EditorInfo.IME_ACTION_SEND -> "SEND"
            EditorInfo.IME_ACTION_NEXT -> "NEXT"
            EditorInfo.IME_ACTION_DONE -> "DONE"
            else -> null
        }
    }

    fun nextKeyboard() {
        if (Build.VERSION.SDK_INT >= 28) {
            if (!switchToNextInputMethod(false)) picker()
        } else picker()
    }

    fun picker() {
        (getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager).showInputMethodPicker()
    }
}
