package com.subflow

import android.app.Application
import com.subflow.data.ApiKeys
import com.subflow.data.AppSettings
import com.subflow.data.CrashLog
import com.subflow.data.SearchQueue
import com.subflow.data.Stats
import com.subflow.optimization.DeviceProfiler
import com.subflow.optimization.MemoryManager
import com.subflow.pipeline.MegaDictionary
import com.subflow.ui.theme.SubFlowColors
import com.subflow.utils.AppForeground
import com.subflow.utils.ConnectivityWatcher
import com.subflow.utils.L10n

class SubFlowApp : Application() {

    override fun onCreate() {
        super.onCreate()
        CrashLog.install(this)
        DeviceProfiler.init(this)
        DeviceProfiler.detect()
        L10n.init(this)
        SubFlowColors.load(this)
        ApiKeys.init(this)
        AppSettings.init(this)
        Stats.init(this)
        SearchQueue.init(this)
        // load the phrase dictionary off the main thread, it's only needed later
        Thread { MegaDictionary.load(this) }.apply { isDaemon = true }.start()
        AppForeground.register(this)
        ConnectivityWatcher.start(this)
    }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        MemoryManager.onTrimMemory(level)
    }
}
