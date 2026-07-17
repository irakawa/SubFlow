package com.subflow.optimization

import android.util.LruCache

// budget scales with device tier. cache clears when the app backgrounds.
object MemoryManager {

    private val budgetBytes: Int
        get() = when (DeviceProfiler.detect()) {
            DeviceTier.LOW -> 50 * 1024 * 1024
            DeviceTier.MID -> 150 * 1024 * 1024
            DeviceTier.HIGH -> 300 * 1024 * 1024
        }

    private val cache: LruCache<String, ByteArray> by lazy {
        object : LruCache<String, ByteArray>(budgetBytes) {
            override fun sizeOf(key: String, value: ByteArray): Int = value.size
        }
    }

    fun put(key: String, value: ByteArray) {
        // on LOW tier a single entry can't exceed a quarter of the budget
        if (DeviceProfiler.detect() == DeviceTier.LOW && value.size > budgetBytes / 4) return
        cache.put(key, value)
    }

    fun get(key: String): ByteArray? = cache.get(key)

    fun clear() {
        cache.evictAll()
    }

    fun onTrimMemory(level: Int) {
        if (level >= android.content.ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN) {
            clear()
        } else {
            cache.trimToSize(cache.maxSize() / 2)
        }
    }
}
