package com.androidpause.enforcement

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import com.androidpause.core.Log

/** Observes explicit device clock/zone/date changes while the accessibility service is connected. */
class DeviceTimeChangeObserver(
    private val context: Context,
    private val onChange: () -> Unit,
) {
    private var registered = false
    private val receiver =
        object : BroadcastReceiver() {
            override fun onReceive(
                context: Context?,
                intent: Intent?,
            ) {
                Log.i("Device time changed: action=${intent?.action}")
                onChange()
            }
        }

    fun register() {
        if (registered) return
        val filter =
            IntentFilter().apply {
                addAction(Intent.ACTION_TIME_CHANGED)
                addAction(Intent.ACTION_TIMEZONE_CHANGED)
                addAction(Intent.ACTION_DATE_CHANGED)
            }
        context.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
        registered = true
    }

    fun unregister() {
        if (!registered) return
        context.unregisterReceiver(receiver)
        registered = false
    }
}
