package com.subflow.data

import android.content.Context
import android.util.Log
import com.subflow.BuildConfig
import java.io.File

/**
 * writes the last uncaught exception to a local file so it can be shared from
 * Settings. nothing leaves the device on its own.
 */
object CrashLog {

    private fun file(context: Context): File = File(context.filesDir, "last_crash.txt")

    fun install(context: Context) {
        val appContext = context.applicationContext
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            runCatching {
                file(appContext).writeText(
                    "SubFlow ${BuildConfig.VERSION_NAME} · thread=${thread.name}\n" +
                        Log.getStackTraceString(throwable)
                )
            }
            previous?.uncaughtException(thread, throwable)
        }
    }

    fun read(context: Context): String? =
        file(context).takeIf { it.exists() }?.readText()?.ifBlank { null }

    fun clear(context: Context) {
        runCatching { file(context).delete() }
    }
}
