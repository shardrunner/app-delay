package com.androidpause.data

import com.androidpause.data.entity.DailyStatsEntity
import com.androidpause.domain.model.PauseStats
import com.androidpause.domain.model.RuleStats
import com.androidpause.domain.weekStart
import java.time.DayOfWeek
import java.time.LocalDate

/** Statistics older than this are pruned; the pause screen needs at most the current week. */
private const val RETAINED_DAYS = 14L

/** Per-rule daily statistics for the pause screen. Local-only; never exported. */
class StatsStore(
    private val dao: DailyStatsDao,
) {
    private var prunedDay: LocalDate? = null

    suspend fun add(
        day: LocalDate,
        deltas: Map<Long, RuleStats>,
    ) {
        if (deltas.isEmpty()) return
        deltas.forEach { (scheduleId, stats) -> dao.add(stats.toEntity(scheduleId, day.toEpochDay())) }
        // Once per day keeps app switches to the upserts themselves.
        if (prunedDay != day) {
            dao.deleteBefore(day.toEpochDay() - RETAINED_DAYS)
            prunedDay = day
        }
    }

    suspend fun pauseStats(
        scheduleId: Long,
        today: LocalDate,
        firstDayOfWeek: DayOfWeek,
    ): PauseStats {
        val rows = dao.range(scheduleId, weekStart(today, firstDayOfWeek).toEpochDay(), today.toEpochDay())
        val week = rows.fold(RuleStats()) { sum, row -> sum + row.toStats() }
        val todayStats = rows.firstOrNull { it.epochDay == today.toEpochDay() }?.toStats() ?: RuleStats()
        return PauseStats(today = todayStats, week = week)
    }

    private fun RuleStats.toEntity(
        scheduleId: Long,
        epochDay: Long,
    ) = DailyStatsEntity(scheduleId, epochDay, usageMillis, openings, extensions, notNow)

    private fun DailyStatsEntity.toStats() = RuleStats(usageMillis, openings, extensions, notNow)
}
