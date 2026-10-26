package com.androidpause.ui.config

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ListItem
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import com.androidpause.core.SECONDS_PER_MINUTE

private val GRACE_CHOICES_MINUTES = listOf(0, 1, 2, 5, 10, 15, 30, 60)

/** Presets avoid persisting a configuration change on every keystroke. */
@Composable
internal fun ReentryGraceSetting(
    seconds: Int,
    onSelect: (Int) -> Unit,
) {
    var choosing by remember { mutableStateOf(false) }
    ListItem(
        headlineContent = { Text("Re-entry grace") },
        supportingContent = {
            Text(
                if (seconds == 0) {
                    "Every return to a covered app is a new opening"
                } else {
                    "Returning within ${graceLabel(seconds)} keeps the current opening"
                },
            )
        },
        trailingContent = { TextButton(onClick = { choosing = true }) { Text(graceLabel(seconds)) } },
    )
    if (!choosing) return
    AlertDialog(
        onDismissRequest = { choosing = false },
        title = { Text("Re-entry grace") },
        text = {
            Column(Modifier.selectableGroup()) {
                GRACE_CHOICES_MINUTES.map { it * SECONDS_PER_MINUTE }.forEach { choice ->
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier =
                            Modifier.fillMaxWidth().selectable(
                                selected = choice == seconds,
                                role = Role.RadioButton,
                                onClick = {
                                    choosing = false
                                    if (choice != seconds) onSelect(choice)
                                },
                            ),
                    ) {
                        RadioButton(selected = choice == seconds, onClick = null)
                        Text(graceLabel(choice))
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = { choosing = false }) { Text("Cancel") } },
    )
}

internal fun graceLabel(seconds: Int): String =
    when {
        seconds == 0 -> "Off"
        seconds % SECONDS_PER_MINUTE == 0 -> "${seconds / SECONDS_PER_MINUTE} min"
        else -> "$seconds s"
    }
