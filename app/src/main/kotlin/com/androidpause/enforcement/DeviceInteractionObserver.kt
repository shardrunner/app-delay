package com.androidpause.enforcement

import android.app.KeyguardManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.PowerManager

/** Screen-off stops enforcement; screen-on resumes only when no keyguard is present. Callbacks fire on changes only. */
internal class DeviceInteractionObserver(
    private val context: Context,
    private val onInactive: () -> Unit,
    private val onActive: () -> Unit,
) {
    var isActive = false
        private set
    private var registered = false
    private val receiver =
        object : BroadcastReceiver() {
            override fun onReceive(
                context: Context?,
                intent: Intent?,
            ) {
                // Screen-on while locked and screen-on followed by user-present repeat the current state.
                val active = intent?.action != Intent.ACTION_SCREEN_OFF && deviceIsActive()
                if (active == isActive) return
                isActive = active
                if (active) onActive() else onInactive()
            }
        }

    fun register() {
        if (registered) return
        isActive = deviceIsActive()
        val filter =
            IntentFilter(Intent.ACTION_SCREEN_OFF).apply {
                addAction(Intent.ACTION_SCREEN_ON)
                addAction(Intent.ACTION_USER_PRESENT)
            }
        // SystemUI, not the system UID, sends USER_PRESENT; a not-exported receiver never gets it.
        // All three actions are protected broadcasts, so other apps cannot spoof them.
        context.registerReceiver(receiver, filter, Context.RECEIVER_EXPORTED)
        registered = true
    }

    fun unregister() {
        if (!registered) return
        context.unregisterReceiver(receiver)
        registered = false
        isActive = false
    }

    private fun deviceIsActive(): Boolean =
        context.getSystemService(PowerManager::class.java).isInteractive &&
            !context.getSystemService(KeyguardManager::class.java).isKeyguardLocked
}
