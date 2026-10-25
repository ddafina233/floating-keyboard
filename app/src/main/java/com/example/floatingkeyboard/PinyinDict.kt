package com.example.floatingkeyboard

import android.content.Context
import android.util.Log

/**
 * 轻量拼音词典：从 assets/pinyin.txt 加载，支持全拼、前缀和简拼查询。
 *
 * 文件格式（TSV）：全拼key \t 简拼 \t 词 \t 权重
 * 例如：nihao \t nh \t 你好 \t 332885
 */
class PinyinDict(private val context: Context) {

    private class Entry(val word: String, val weight: Int)

    private val byKey = HashMap<String, MutableList<Entry>>()
    private val byAbbr = HashMap<String, MutableList<Entry>>()
    private var sortedKeys: Array<String> = emptyArray()
    private var sortedAbbrs: Array<String> = emptyArray()

    @Volatile
    private var loaded = false

    /** 首次加载约 200~500ms，建议在后台线程预热。 */
    fun ensureLoaded() {
        if (loaded) return
        synchronized(this) {
            if (loaded) return
            val t0 = System.currentTimeMillis()
            var count = 0
            context.assets.open("pinyin.txt").bufferedReader().use { br ->
                br.forEachLine { line ->
                    if (line.isEmpty()) return@forEachLine
                    val t1 = line.indexOf('\t')
                    if (t1 <= 0) return@forEachLine
                    val t2 = line.indexOf('\t', t1 + 1)
                    if (t2 <= 0) return@forEachLine
                    val t3 = line.indexOf('\t', t2 + 1)
                    if (t3 <= 0) return@forEachLine

                    val key = line.substring(0, t1)
                    val abbr = line.substring(t1 + 1, t2)
                    val word = line.substring(t2 + 1, t3)
                    val weight = line.substring(t3 + 1).toIntOrNull() ?: 0

                    val e = Entry(word, weight)
                    byKey.getOrPut(key) { ArrayList(8) }.add(e)
                    byAbbr.getOrPut(abbr) { ArrayList(8) }.add(e)
                    count++
                }
            }
            sortedKeys = byKey.keys.sorted().toTypedArray()
            sortedAbbrs = byAbbr.keys.sorted().toTypedArray()
            loaded = true
            Log.d(TAG, "loaded $count entries / ${sortedKeys.size} keys in ${System.currentTimeMillis() - t0}ms")
        }
    }

    /**
     * 查询候选词。
     * @param usage 用户历史选词次数，用于个性化加权
     */
    fun query(buffer: String, usage: (String) -> Int, limit: Int = 80): List<String> {
        if (buffer.isEmpty()) return emptyList()
        ensureLoaded()

        val scored = LinkedHashMap<String, Long>()

        fun add(word: String, weight: Int, exact: Boolean) {
            var score = weight.toLong()
            val u = usage(word)
            if (u > 0) score = (score * (1.0 + u * 1.2)).toLong()
            if (exact) score += 5_000_000L   // 精确匹配永远排在联想前面
            val old = scored[word]
            if (old == null || score > old) scored[word] = score
        }

        // 1) 全拼精确 + 简拼精确
        byKey[buffer]?.forEach { add(it.word, it.weight, true) }
        byAbbr[buffer]?.forEach { add(it.word, it.weight, true) }

        // 2) 全拼前缀（"ni" -> 你/泥/你好/你们…）
        var i = lowerBound(sortedKeys, buffer)
        var n = 0
        while (i < sortedKeys.size && n < 40 && sortedKeys[i].startsWith(buffer)) {
            byKey[sortedKeys[i]]?.forEach { add(it.word, it.weight, false) }
            i++
            n++
        }

        // 3) 简拼前缀（"nh" 时上面匹配不到，这里兜底）
        i = lowerBound(sortedAbbrs, buffer)
        n = 0
        while (i < sortedAbbrs.size && n < 25 && sortedAbbrs[i].startsWith(buffer)) {
            byAbbr[sortedAbbrs[i]]?.forEach { add(it.word, it.weight, false) }
            i++
            n++
        }

        return scored.entries
            .sortedByDescending { it.value }
            .take(limit)
            .map { it.key }
    }

    private fun lowerBound(sorted: Array<String>, prefix: String): Int {
        var lo = 0
        var hi = sorted.size
        while (lo < hi) {
            val mid = (lo + hi) ushr 1
            if (sorted[mid] < prefix) lo = mid + 1 else hi = mid
        }
        return lo
    }

    private companion object {
        const val TAG = "PinyinDict"
    }
}

