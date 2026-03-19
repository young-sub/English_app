package com.example.bookhelper.perf

import com.example.bookhelper.contracts.OcrPage

class OcrCache(
    private val policy: OcrCachePolicy = OcrCachePolicy(),
) : OcrPageCache {
    private val cache = LinkedHashMap<Long, OcrPage>(16, 0.75f, true)

    override fun put(hash: Long, page: OcrPage) {
        cache[hash] = page
        trimToMaxEntries()
    }

    override fun get(hash: Long): OcrPage? = cache[hash]

    override fun clear() {
        cache.clear()
    }

    private fun trimToMaxEntries() {
        if (policy.maxEntries <= 0) {
            cache.clear()
            return
        }
        while (cache.size > policy.maxEntries) {
            val oldestKey = cache.entries.firstOrNull()?.key ?: break
            cache.remove(oldestKey)
        }
    }
}
