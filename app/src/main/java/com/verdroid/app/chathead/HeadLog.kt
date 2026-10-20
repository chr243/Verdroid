package com.verdroid.app.chathead

import android.util.Log

/** Floating-window / Home-signal diagnostics: logcat only (`adb logcat -s VerdroidHead`). */
object HeadLog {
    const val TAG = "VerdroidHead"
    /** Debug builds only (release also strips Log.d/v via R8). */
    fun d(msg: String) { if (com.verdroid.app.BuildConfig.DEBUG) Log.d(TAG, msg) }
}
