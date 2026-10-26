package com.androidpause.ui.overlay

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.addCallback
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.lifecycle.lifecycleScope
import com.androidpause.enforcement.PauseScreenHost
import com.androidpause.ui.AppTheme
import kotlinx.coroutines.launch

/** Renders [PauseScreenHost.request]; Back means "Not now", and a cleared request finishes it. */
class PauseActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        onBackPressedDispatcher.addCallback(this) {
            PauseScreenHost.request.value
                ?.onNotNow
                ?.invoke() ?: finish()
        }
        // Collected while created, not only while started, so a cleared request also ends a hidden screen.
        lifecycleScope.launch { PauseScreenHost.request.collect { if (it == null) finish() } }
        setContent {
            AppTheme {
                val request by PauseScreenHost.request.collectAsState()
                request?.let {
                    // A new pause starts fresh countdown and "Not now" state.
                    key(it) {
                        PauseScreen(
                            info = it.info,
                            stats = it.stats,
                            delaySeconds = it.delaySeconds,
                            onProceed = it.onProceed,
                            onNotNow = it.onNotNow,
                        )
                    }
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        PauseScreenHost.visible = true
    }

    override fun onPause() {
        PauseScreenHost.visible = false
        super.onPause()
    }
}
