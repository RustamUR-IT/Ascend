package com.ascend.ui

import android.content.Context
import android.inputmethodservice.InputMethodService
import android.os.Build
import android.text.InputType
import android.view.KeyEvent
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager

/** The Ascend keyboard. Typing logic lives here; drawing and touch handling live in [KeyboardView]. */
class KeyboardService : InputMethodService() {
    private var kv: KeyboardView? = null

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

    override fun onUpdateSelection(
        oldSelStart: Int, oldSelEnd: Int, newSelStart: Int, newSelEnd: Int,
        candidatesStart: Int, candidatesEnd: Int
    ) {
        super.onUpdateSelection(oldSelStart, oldSelEnd, newSelStart, newSelEnd, candidatesStart, candidatesEnd)
        kv?.updateAutoCaps()
    }

    fun commit(s: String) {
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
        val action = info.imeOptions and EditorInfo.IME_MASK_ACTION
        val noAction = (info.imeOptions and EditorInfo.IME_FLAG_NO_ENTER_ACTION) != 0 ||
            action == EditorInfo.IME_ACTION_NONE || action == EditorInfo.IME_ACTION_UNSPECIFIED
        if (noAction) sendDownUpKeyEvents(KeyEvent.KEYCODE_ENTER) else ic.performEditorAction(action)
    }

    /** Double-tapping space after a word types ". " like most phone keyboards. */
    fun space() {
        val ic = currentInputConnection ?: return
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

    fun enterLabel(): String {
        val info = currentInputEditorInfo ?: return "↵"
        if ((info.imeOptions and EditorInfo.IME_FLAG_NO_ENTER_ACTION) != 0) return "↵"
        return when (info.imeOptions and EditorInfo.IME_MASK_ACTION) {
            EditorInfo.IME_ACTION_GO -> "GO"
            EditorInfo.IME_ACTION_SEARCH -> "FIND"
            EditorInfo.IME_ACTION_SEND -> "SEND"
            EditorInfo.IME_ACTION_NEXT -> "NEXT"
            EditorInfo.IME_ACTION_DONE -> "DONE"
            else -> "↵"
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
