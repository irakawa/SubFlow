package com.subflow.ui

import android.view.HapticFeedbackConstants
import android.view.View
import com.subflow.data.AppSettings

// uses View feedback constants so no VIBRATE permission, respects device haptic settings. gated by the user toggle.
object Haptics {

    fun tick(view: View) {
        if (AppSettings.haptics) view.performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK)
    }

    fun success(view: View) {
        if (AppSettings.haptics) view.performHapticFeedback(HapticFeedbackConstants.CONFIRM)
    }
}
