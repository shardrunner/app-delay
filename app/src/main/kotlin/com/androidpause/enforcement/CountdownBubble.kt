package com.androidpause.enforcement

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.provider.Settings
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.TextView

// White text keeps at least 4.5:1 contrast on each background.
private const val NORMAL_COLOR = 0xFF282830.toInt()
private const val WARNING_COLOR = 0xFFBF4300.toInt()
private const val CRITICAL_COLOR = 0xFFC62828.toInt()

/**
 * Small opaque overlay that the user can drag aside; touches outside it reach the app. Its lifetime
 * and ticks belong to the foreground session; the dragged position lasts while the service runs.
 */
internal class CountdownBubble(
    private val context: Context,
) {
    private val manager = context.getSystemService(WindowManager::class.java)
    private val density = context.resources.displayMetrics.density
    private var view: TextView? = null
    private var level: BubbleLevel? = null
    private var x = (8 * density).toInt()
    private var y = (64 * density).toInt()

    fun update(state: BubbleState?) {
        if (state == null) {
            dismiss()
            return
        }
        val current = view ?: attach() ?: return
        if (current.text != state.label) current.text = state.label
        if (level != state.level) {
            level = state.level
            (current.background as GradientDrawable).setColor(backgroundColor(state.level))
        }
    }

    private fun attach(): TextView? {
        // Permission is needed when attaching, not on every countdown tick.
        if (!Settings.canDrawOverlays(context)) return null
        val padding = (12 * density).toInt()
        val bubble =
            TextView(context).apply {
                textSize = 16f
                typeface = Typeface.DEFAULT_BOLD
                setTextColor(Color.WHITE)
                setPadding(padding, padding / 2, padding, padding / 2)
                background = GradientDrawable().apply { cornerRadius = 24 * density }
                elevation = 4 * density
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            }
        val params =
            WindowManager
                .LayoutParams(
                    WindowManager.LayoutParams.WRAP_CONTENT,
                    WindowManager.LayoutParams.WRAP_CONTENT,
                    WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
                    PixelFormat.TRANSLUCENT,
                ).apply {
                    gravity = Gravity.TOP or Gravity.END
                    x = this@CountdownBubble.x
                    y = this@CountdownBubble.y
                }
        bubble.setOnTouchListener(DragListener(params))
        try {
            manager.addView(bubble, params)
        } catch (error: SecurityException) {
            if (Settings.canDrawOverlays(context)) throw error
            return null
        } catch (error: WindowManager.BadTokenException) {
            if (Settings.canDrawOverlays(context)) throw error
            return null
        }
        view = bubble
        level = null
        return bubble
    }

    fun dismiss() {
        view?.let(manager::removeView)
        view = null
    }

    /** Gravity END measures x from the right edge, so dragging right decreases it. */
    private inner class DragListener(
        private val params: WindowManager.LayoutParams,
    ) : View.OnTouchListener {
        private var downX = 0f
        private var downY = 0f
        private var startX = 0
        private var startY = 0

        @SuppressLint("ClickableViewAccessibility") // Dragging only; the bubble has no click action.
        override fun onTouch(
            view: View,
            event: MotionEvent,
        ): Boolean {
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = event.rawX
                    downY = event.rawY
                    startX = params.x
                    startY = params.y
                }
                MotionEvent.ACTION_MOVE -> {
                    params.x = startX - (event.rawX - downX).toInt()
                    params.y = startY + (event.rawY - downY).toInt()
                    x = params.x
                    y = params.y
                    manager.updateViewLayout(view, params)
                }
            }
            return true
        }
    }

    private fun backgroundColor(level: BubbleLevel): Int =
        when (level) {
            BubbleLevel.NORMAL -> NORMAL_COLOR
            BubbleLevel.WARNING -> WARNING_COLOR
            BubbleLevel.CRITICAL -> CRITICAL_COLOR
        }
}
