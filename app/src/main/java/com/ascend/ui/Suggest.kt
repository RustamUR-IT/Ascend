package com.ascend.ui

import android.content.Context
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Word suggestions for the keyboard. The bundled list (assets/dict.txt, German + English,
 * "word<TAB>score" with score 1–255) is loaded once in the background. Words you type are
 * remembered on the phone (files/user_words.txt) and ranked higher next time.
 */
object Suggest {
    private class Entry(val word: String, val lower: String, var score: Int)

    @Volatile private var entries: Array<Entry> = emptyArray()   // sorted by `lower`
    private val user = HashMap<String, Int>()                      // word → times typed
    private val loading = AtomicBoolean(false)
    @Volatile private var dirty = false
    private var userFile: File? = null

    fun warmUp(ctx: Context) {
        if (!loading.compareAndSet(false, true)) return
        val app = ctx.applicationContext
        Thread {
            val list = ArrayList<Entry>(48000)
            try {
                app.assets.open("dict.txt").bufferedReader().useLines { lines ->
                    for (line in lines) {
                        val tab = line.indexOf('\t')
                        if (tab <= 0) continue
                        val w = line.substring(0, tab)
                        list.add(Entry(w, w.lowercase(), line.substring(tab + 1).toIntOrNull() ?: 1))
                    }
                }
            } catch (_: Exception) { }
            val f = File(app.filesDir, "user_words.txt")
            userFile = f
            val learned = HashMap<String, Int>()
            try {
                if (f.exists()) f.forEachLine { line ->
                    val p = line.split('\t')
                    if (p.size == 2) learned[p[0]] = p[1].toIntOrNull() ?: 1
                }
            } catch (_: Exception) { }
            val byLower = HashMap<String, Entry>(list.size * 2)
            for (e in list) byLower.putIfAbsent(e.lower, e)
            for ((w, n) in learned) {
                val e = byLower[w.lowercase()]
                if (e == null) { val ne = Entry(w, w.lowercase(), 0); list.add(ne); byLower[ne.lower] = ne }
            }
            list.sortBy { it.lower }
            synchronized(user) { user.clear(); user.putAll(learned) }
            entries = list.toTypedArray()
        }.start()
    }

    private fun rank(e: Entry): Int {
        val n = synchronized(user) { user[e.word] ?: user[e.lower] ?: 0 }
        return e.score + if (n > 0) 60 + minOf(n, 10) * 12 else 0
    }

    /** Up to [limit] completions for [typed], best first, in the casing the user is typing. */
    fun complete(typed: String, limit: Int = 3): List<String> {
        if (typed.isEmpty()) return emptyList()
        val arr = entries
        if (arr.isEmpty()) return emptyList()
        val p = typed.lowercase()
        var lo = 0
        var hi = arr.size
        while (lo < hi) {
            val mid = (lo + hi) ushr 1
            if (arr[mid].lower < p) lo = mid + 1 else hi = mid
        }
        val best = ArrayList<Pair<Entry, Int>>(limit + 1)
        var i = lo
        var scanned = 0
        while (i < arr.size && arr[i].lower.startsWith(p) && scanned < 6000) {
            val e = arr[i]
            var r = rank(e)
            if (e.lower == p) r += 40                         // what you typed is a real word: keep it in reach
            if (best.size < limit || r > best.last().second) {
                best.add(e to r)
                best.sortByDescending { it.second }
                if (best.size > limit) best.removeAt(best.size - 1)
            }
            i++; scanned++
        }
        return best.map { applyCase(typed, it.first.word) }.distinct()
    }

    private fun applyCase(typed: String, word: String): String = when {
        typed.length > 1 && typed.all { !it.isLetter() || it.isUpperCase() } -> word.uppercase()
        typed[0].isUpperCase() -> word.replaceFirstChar { it.uppercaseChar() }
        else -> word
    }

    /** Remember a finished word. Stored with the casing used unless it's just sentence-start capitalisation. */
    fun learn(word: String, sentenceStart: Boolean) {
        if (word.length < 2 || word.length > 30 || !word.all { it.isLetter() || it == '\'' || it == '-' }) return
        val arr = entries
        if (arr.isEmpty()) return
        val key = if (sentenceStart && word[0].isUpperCase() && word.drop(1).all { it.isLowerCase() }) {
            arr.firstOrNull { it.lower == word.lowercase() }?.word ?: word
        } else word
        synchronized(user) { user[key] = (user[key] ?: 0) + 1 }
        if (arr.none { it.lower == key.lowercase() }) {
            val list = arr.toMutableList()
            list.add(Entry(key, key.lowercase(), 0))
            list.sortBy { it.lower }
            entries = list.toTypedArray()
        }
        dirty = true
    }

    fun save() {
        if (!dirty) return
        val f = userFile ?: return
        dirty = false
        val snapshot = synchronized(user) {
            user.entries.sortedByDescending { it.value }.take(3000).map { "${it.key}\t${it.value}" }
        }
        Thread { try { f.writeText(snapshot.joinToString("\n")) } catch (_: Exception) { } }.start()
    }

    fun forgetLearned(ctx: Context) {
        synchronized(user) { user.clear() }
        try { File(ctx.filesDir, "user_words.txt").delete() } catch (_: Exception) { }
        loading.set(false)
        entries = emptyArray()
        warmUp(ctx)
    }
}
