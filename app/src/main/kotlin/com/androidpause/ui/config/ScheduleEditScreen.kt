package com.androidpause.ui.config

import android.text.format.DateFormat
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.androidpause.ui.BackButton
import com.androidpause.ui.formatTime
import java.time.LocalTime

/** Edit a rule's fields; Back saves valid edits. The app selection belongs to the profile. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ScheduleEditScreen(
    draft: ScheduleDraft,
    onDraftChange: (ScheduleDraft) -> Unit,
    onDelete: (() -> Unit)?,
    onBack: () -> Unit,
) {
    Scaffold(
        topBar = {
            ScheduleEditTopBar(
                draft = draft,
                onDelete = onDelete,
                onBack = onBack,
            )
        },
    ) { padding ->
        ScheduleEditContent(
            draft = draft,
            onDraftChange = onDraftChange,
            modifier =
                Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .padding(16.dp)
                    .verticalScroll(rememberScrollState()),
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ScheduleEditTopBar(
    draft: ScheduleDraft,
    onDelete: (() -> Unit)?,
    onBack: () -> Unit,
) {
    TopAppBar(
        title = { Text(if (draft.id == 0L) "New rule" else "Edit rule") },
        navigationIcon = {
            BackButton(onBack)
        },
        actions = {
            if (onDelete != null) {
                TextButton(onClick = onDelete) { Text("Delete") }
            }
        },
    )
}

@Composable
private fun ScheduleEditContent(
    draft: ScheduleDraft,
    onDraftChange: (ScheduleDraft) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            if (draft.canSave) {
                "Going back keeps these changes."
            } else {
                "Needs a name, an allowance and valid numbers before it can be kept."
            },
        )
        OutlinedTextField(
            value = draft.name,
            onValueChange = { onDraftChange(draft.copy(name = it)) },
            label = { Text("Name") },
            modifier = Modifier.fillMaxWidth(),
        )

        TimeField("Window start", draft.windowStart) {
            onDraftChange(draft.copy(windowStart = it))
        }
        TimeField("Window end (exclusive)", draft.windowEnd) {
            onDraftChange(draft.copy(windowEnd = it))
        }

        CheckboxRow("Time allowance", draft.timeBudgetEnabled) {
            onDraftChange(draft.copy(timeBudgetEnabled = it))
        }
        if (draft.timeBudgetEnabled) {
            IntField("Shared budget (minutes)", draft.budgetInput, MAX_BUDGET_MINUTES) {
                onDraftChange(draft.copy(budgetInput = it))
            }
        }
        CheckboxRow("Opening allowance", draft.openingAllowanceEnabled) {
            onDraftChange(draft.copy(openingAllowanceEnabled = it))
        }
        if (draft.openingAllowanceEnabled) {
            IntField("Shared openings", draft.openingsInput) { onDraftChange(draft.copy(openingsInput = it)) }
        }
        Text("Zero restricts from the first entry. Allowances are shared across the profile’s apps.")
        Text("Openings count foreground entries, including switching between covered apps.")
        PauseSettingsSection(draft = draft, onDraftChange = onDraftChange)

        OutlinedTextField(
            value = draft.message,
            onValueChange = { onDraftChange(draft.copy(message = it)) },
            label = { Text("Pause message") },
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Composable
private fun PauseSettingsSection(
    draft: ScheduleDraft,
    onDraftChange: (ScheduleDraft) -> Unit,
) {
    CheckboxRow("Infinite (total block)", draft.infinitePause) {
        onDraftChange(draft.copy(infinitePause = it))
    }
    if (!draft.infinitePause) {
        IntField("Pause delay (seconds)", draft.delayInput) {
            onDraftChange(draft.copy(delayInput = it))
        }
    }
    if (!draft.infinitePause) {
        IntField("After-pause extension (seconds)", draft.afterPauseInput) {
            onDraftChange(draft.copy(afterPauseInput = it))
        }
        Text("Positive extensions use wall-clock time, including time away. Zero allows only the current app entry.")
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TimeField(
    label: String,
    value: LocalTime,
    onChange: (LocalTime) -> Unit,
) {
    val context = LocalContext.current
    var showPicker by rememberSaveable { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(label, style = androidx.compose.material3.MaterialTheme.typography.labelMedium)
        OutlinedButton(
            onClick = { showPicker = true },
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(formatTime(context, value))
        }
    }
    if (showPicker) {
        val pickerState =
            rememberTimePickerState(
                initialHour = value.hour,
                initialMinute = value.minute,
                is24Hour = DateFormat.is24HourFormat(context),
            )
        AlertDialog(
            onDismissRequest = { showPicker = false },
            confirmButton = {
                TextButton(
                    onClick = {
                        onChange(LocalTime.of(pickerState.hour, pickerState.minute))
                        showPicker = false
                    },
                ) { Text("OK") }
            },
            dismissButton = {
                TextButton(onClick = { showPicker = false }) { Text("Cancel") }
            },
            text = { TimePicker(state = pickerState) },
        )
    }
}

@Composable
private fun CheckboxRow(
    label: String,
    checked: Boolean,
    onChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().toggleable(value = checked, role = Role.Checkbox, onValueChange = onChange),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Checkbox(checked = checked, onCheckedChange = null)
        Text(label)
    }
}

@Composable
private fun IntField(
    label: String,
    value: String,
    maximum: Int = Int.MAX_VALUE,
    onChange: (String) -> Unit,
) {
    val invalid = boundedNumber(value, maximum) == null
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        isError = invalid,
        supportingText = { if (invalid) Text("Enter a whole number from 0 to $maximum") },
        label = { Text(label) },
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        modifier = Modifier.fillMaxWidth(),
    )
}
