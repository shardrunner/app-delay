package com.androidpause.ui.overlay

import android.os.SystemClock
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.androidpause.core.MILLIS_PER_SECOND
import com.androidpause.domain.model.PauseInfo
import com.androidpause.domain.model.PauseStats
import com.androidpause.domain.model.RuleStats
import com.androidpause.ui.formatTime
import kotlinx.coroutines.delay
import kotlin.math.ceil

/** Long enough to read the updated weekly count before leaving. */
private const val NOT_NOW_FEEDBACK_MILLIS = 1_200L
private const val NOT_NOW_PULSE_SCALE = 1.04f
private const val STATS_LABEL_WEIGHT = 1.4f

/**
 * Pause/block content of [PauseActivity]. Not now and Back leave for Home; the
 * extension countdown lives on its button so the screen can show usage and statistics.
 */
@Composable
fun PauseScreen(
    info: PauseInfo,
    stats: PauseStats,
    delaySeconds: Int?,
    onProceed: () -> Unit,
    onNotNow: () -> Unit,
) {
    val remaining = rememberRemainingSeconds(delaySeconds)
    var leaving by remember { mutableStateOf(false) }
    LaunchedEffect(leaving) {
        if (!leaving) return@LaunchedEffect
        delay(NOT_NOW_FEEDBACK_MILLIS)
        onNotNow()
    }
    Column(
        modifier =
            Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.surface)
                .windowInsetsPadding(WindowInsets.safeDrawing)
                .padding(horizontal = 24.dp, vertical = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Column(
            modifier =
                Modifier
                    .widthIn(max = 480.dp)
                    .fillMaxWidth()
                    .weight(1f)
                    .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Header(info = info, blocked = delaySeconds == null)
            UsageCard(info = info)
            StatsCard(stats = if (leaving) stats.withNotNow() else stats)
        }
        PauseActions(
            weekNotNow = stats.week.notNow + 1,
            leaving = leaving,
            extendLabel = delaySeconds?.let { extendLabel(info.afterPauseBudgetSeconds, remaining) },
            canProceed = delaySeconds != null && remaining == 0 && !leaving,
            onProceed = onProceed,
            onNotNow = { leaving = true },
        )
    }
}

private fun PauseStats.withNotNow(): PauseStats {
    val one = RuleStats(notNow = 1)
    return PauseStats(today + one, week + one)
}

@Composable
private fun Header(
    info: PauseInfo,
    blocked: Boolean,
) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(
            text =
                if (blocked) {
                    "Blocked until ${formatTime(LocalContext.current, info.windowEnd)}"
                } else {
                    "Time for a pause"
                },
            color = if (blocked) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
            style = MaterialTheme.typography.titleMedium,
            textAlign = TextAlign.Center,
        )
        if (info.message.isNotBlank()) {
            Text(
                text = "“${info.message.trim()}”",
                color = MaterialTheme.colorScheme.onSurface,
                style = MaterialTheme.typography.headlineSmall,
                fontStyle = FontStyle.Italic,
                textAlign = TextAlign.Center,
            )
        }
    }
}

/** Values that caused the pause are red instead of being explained in text. */
@Composable
private fun UsageCard(info: PauseInfo) {
    StatCard {
        info.openingAllowance?.let { allowance ->
            ValueRow(
                "Openings",
                "${info.consumedOpenings} / $allowance",
                exhausted = info.consumedOpenings >= allowance,
            )
        }
        info.sharedBudgetSeconds?.let { allowance ->
            ValueRow(
                "Screen time",
                timeUsageText(info.consumedBudgetSeconds, allowance),
                exhausted = info.consumedBudgetSeconds >= allowance,
            )
        }
        ValueRow("Rule ends", formatTime(LocalContext.current, info.windowEnd))
    }
}

@Composable
private fun StatsCard(stats: PauseStats) {
    StatCard {
        StatsRow("", "Today", "This week", header = true)
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        StatsRow("Not now", "${stats.today.notNow}", "${stats.week.notNow}", highlight = true)
        StatsRow("Extensions", "${stats.today.extensions}", "${stats.week.extensions}")
        StatsRow("Screen time", durationText(stats.today.usageMillis), durationText(stats.week.usageMillis))
        StatsRow("Openings", "${stats.today.openings}", "${stats.week.openings}")
    }
}

@Composable
private fun StatCard(content: @Composable () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
    ) {
        Column(modifier = Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) { content() }
    }
}

@Composable
private fun ValueRow(
    label: String,
    value: String,
    exhausted: Boolean = false,
) {
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyLarge)
        Text(
            value,
            color = if (exhausted) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
            style = MaterialTheme.typography.bodyLarge,
            fontWeight = if (exhausted) FontWeight.Bold else FontWeight.SemiBold,
        )
    }
}

@Composable
private fun StatsRow(
    label: String,
    today: String,
    week: String,
    header: Boolean = false,
    highlight: Boolean = false,
) {
    val style = if (header) MaterialTheme.typography.labelMedium else MaterialTheme.typography.bodyLarge
    val valueColor =
        when {
            header -> MaterialTheme.colorScheme.onSurfaceVariant
            highlight -> MaterialTheme.colorScheme.primary
            else -> MaterialTheme.colorScheme.onSurface
        }
    val weight = if (header) FontWeight.Normal else FontWeight.SemiBold
    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(
            label,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = style,
            modifier = Modifier.weight(STATS_LABEL_WEIGHT),
        )
        listOf(today, week).forEach {
            Text(
                it,
                color = valueColor,
                style = style,
                fontWeight = weight,
                textAlign = TextAlign.End,
                modifier = Modifier.weight(1f),
            )
        }
    }
}

@Composable
private fun PauseActions(
    weekNotNow: Int,
    leaving: Boolean,
    extendLabel: String?,
    canProceed: Boolean,
    onProceed: () -> Unit,
    onNotNow: () -> Unit,
) {
    val scale by animateFloatAsState(
        targetValue = if (leaving) NOT_NOW_PULSE_SCALE else 1f,
        animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy),
        label = "not now pulse",
    )
    Column(
        modifier = Modifier.widthIn(max = 480.dp).fillMaxWidth().padding(top = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Button(
            onClick = { if (!leaving) onNotNow() },
            modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp).scale(scale),
        ) {
            AnimatedContent(
                targetState = leaving,
                transitionSpec = { fadeIn() togetherWith fadeOut() },
                label = "not now label",
            ) { done ->
                Text(if (done) "✓  Not now · $weekNotNow× this week" else "Not now")
            }
        }
        if (extendLabel != null) {
            OutlinedButton(
                onClick = onProceed,
                enabled = canProceed,
                modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp),
            ) {
                Text(extendLabel)
            }
        }
    }
}

@Composable
private fun rememberRemainingSeconds(delaySeconds: Int?): Int {
    var remaining by remember(delaySeconds) { mutableIntStateOf(delaySeconds ?: 0) }
    LaunchedEffect(delaySeconds) {
        val finiteDelay = delaySeconds ?: return@LaunchedEffect
        val deadline = SystemClock.elapsedRealtime() + finiteDelay * MILLIS_PER_SECOND
        do {
            val remainingMillis = (deadline - SystemClock.elapsedRealtime()).coerceAtLeast(0L)
            remaining = ceil(remainingMillis.toDouble() / MILLIS_PER_SECOND).toInt()
            if (remaining > 0) delay(remainingMillis.coerceIn(1L, MILLIS_PER_SECOND))
        } while (remaining > 0)
    }
    return remaining
}
