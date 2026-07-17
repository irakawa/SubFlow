package com.subflow.utils

import android.content.Context
import androidx.annotation.StringRes

// resolves localized strings for the pipeline, which has no Context of its own.
object L10n {

    private lateinit var appContext: Context

    fun init(context: Context) {
        appContext = context.applicationContext
    }

    // falls back to the unformatted string if args don't match
    fun t(@StringRes resId: Int, vararg args: Any?): String = try {
        appContext.getString(resId, *args)
    } catch (e: Exception) {
        appContext.getString(resId)
    }
}
