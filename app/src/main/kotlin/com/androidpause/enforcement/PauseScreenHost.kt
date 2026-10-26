package com.androidpause.enforcement

import android.content.Context
import android.content.Intent
import com.androidpause.domain.model.PauseInfo
import com.androidpause.domain.model.PauseStats
import com.androidpause.ui.overlay.PauseActivity
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** What the pause screen renders and how its actions reach the service. */
data class PauseRequest(
    val info: PauseInfo,
    val stats: PauseStats,
    /** `null` for an infinite block. */
    val delaySeconds: Int?,
    val onProceed: () -> Unit,
    val onNotNow: () -> Unit,
)

/**
 * The pause screen is an activity in its own task rather than an overlay window, so system Back,
 * Home/Recents and gesture apps above activities (e.g. Samsung One Hand Operation +) work as on any
 * app. The service publishes the request; [PauseActivity] renders it and finishes once it clears.
 * Bound accessibility services may start activities from the background.
 */
object PauseScreenHost {
    private val mutableRequest = MutableStateFlow<PauseRequest?>(null)
    val request: StateFlow<PauseRequest?> = mutableRequest

    /** Set by [PauseActivity] while it is resumed. */
    @Volatile
    var visible = false

    val isShowing: Boolean get() = mutableRequest.value != null

    internal fun show(
        context: Context,
        request: PauseRequest,
    ) {
        mutableRequest.value = request
        bringToFront(context)
    }

    /** Re-shows the screen when the paused app returns above it (e.g. from Recents). */
    internal fun bringToFront(context: Context) {
        if (!isShowing) return
        context.startActivity(
            Intent(context, PauseActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }

    internal fun dismiss() {
        mutableRequest.value = null
    }
}
