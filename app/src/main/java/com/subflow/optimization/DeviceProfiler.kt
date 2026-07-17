package com.subflow.optimization

import android.app.ActivityManager
import android.content.Context
import android.view.Display
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi

enum class DeviceTier { LOW, MID, HIGH }

@OptIn(ExperimentalCoroutinesApi::class)
object DeviceProfiler {

    private var appContext: Context? = null
    private var cached: DeviceTier? = null

    fun init(context: Context) {
        appContext = context.applicationContext
    }

    fun detect(): DeviceTier {
        cached?.let { return it }
        val ram = getAvailableRamMB()
        val cores = Runtime.getRuntime().availableProcessors()
        val tier = when {
            ram < 3000 || cores <= 4 -> DeviceTier.LOW   // Helio P70 and below
            ram < 8000 || cores <= 6 -> DeviceTier.MID   // mid segment
            else -> DeviceTier.HIGH                      // flagship
        }
        cached = tier
        return tier
    }

    private fun getAvailableRamMB(): Long {
        val ctx = appContext ?: return 4000
        return try {
            val am = ctx.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
            val info = ActivityManager.MemoryInfo()
            am.getMemoryInfo(info)
            info.totalMem / (1024 * 1024)
        } catch (e: Exception) {
            4000
        }
    }

    val ioDispatcher: CoroutineDispatcher by lazy {
        when (detect()) {
            DeviceTier.LOW -> Dispatchers.IO.limitedParallelism(2)
            DeviceTier.MID -> Dispatchers.IO.limitedParallelism(4)
            DeviceTier.HIGH -> Dispatchers.IO.limitedParallelism(8)
        }
    }

    // parallel sources in the cascade
    val maxParallelSources: Int
        get() = when (detect()) {
            DeviceTier.LOW -> 2
            DeviceTier.MID -> 4
            DeviceTier.HIGH -> 8
        }

    // timeouts in seconds, longer on low tier
    val connectTimeoutSec: Long get() = if (detect() == DeviceTier.LOW) 15 else 8
    val readTimeoutSec: Long get() = if (detect() == DeviceTier.LOW) 30 else 15

    // low tier stretches durations 20% to hold 60fps
    fun animDurationScale(context: Context? = appContext): Float {
        val refresh = try {
            val display: Display? = context?.display
            display?.refreshRate ?: 60f
        } catch (e: Exception) {
            60f
        }
        return if (detect() == DeviceTier.LOW || refresh < 90f) 1.2f else 1.0f
    }

    fun animMs(base: Int): Int = (base * animDurationScale()).toInt()
}
