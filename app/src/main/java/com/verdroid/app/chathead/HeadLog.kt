package com.verdroid.app.chathead

import android.util.Log

/** Floating-window / Home-signal diagnostics: logcat only (`adb logcat -s VerdroidHead`). */
object HeadLog {
    const val TAG = "VerdroidHead"
    fun d(msg: String) { Log.d(TAG, msg) }
}
