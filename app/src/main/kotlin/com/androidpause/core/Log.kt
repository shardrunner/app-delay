package com.androidpause.core

import android.content.Context
import android.content.pm.ApplicationInfo
import android.util.Log as AndroidLog

/**
 * Thin logging facade that fails loud (Constitution V): enforcement-critical
 * problems are logged at error and may be promoted to thrown errors. No silent
 * swallowing of failures anywhere in the codebase.
 */
object Log {
    private const val TAG = "AppPause"

    @Volatile
    private var debugEnabled = false

    fun initialize(context: Context) {
        debugEnabled = context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0
    }

    fun d(message: String): Int = if (debugEnabled) AndroidLog.d(TAG, message) else 0

    fun i(message: String) = AndroidLog.i(TAG, message)

    fun w(
        message: String,
        throwable: Throwable? = null,
    ) = AndroidLog.w(TAG, message, throwable)

    fun e(
        message: String,
        throwable: Throwable? = null,
    ) = AndroidLog.e(TAG, message, throwable)

    /**
     * Fail loud: log at error then throw. Use for invariant violations and missing
     * preconditions that must never be silently ignored (FR-018, Constitution V).
     */
    fun fail(
        message: String,
        cause: Throwable? = null,
    ): Nothing {
        AndroidLog.e(TAG, message, cause)
        throw IllegalStateException(message, cause)
    }
}
