package com.androidpause.domain

import com.androidpause.domain.model.ScheduleConfig
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime

/** Resolves recurring local schedule windows to real instants, including DST gaps and overlaps. */
internal object ScheduleWindowBoundaries {
    fun isActive(
        schedule: ScheduleConfig,
        now: ZonedDateTime,
    ): Boolean = activeOccurrence(schedule, now) != null

    fun windowOccurrenceKey(
        schedule: ScheduleConfig,
        now: ZonedDateTime,
    ): Long =
        requireNotNull(activeOccurrence(schedule, now)) { "Schedule ${schedule.id} is not active at $now" }
            .start
            .toLocalDate()
            .toEpochDay()

    /** Epoch millis of the next start or end of an enabled rule covering [pkg]. */
    fun next(
        pkg: String,
        now: ZonedDateTime,
        schedules: List<ScheduleConfig>,
    ): Long? =
        schedules
            .filter { it.enabled && pkg in it.packages }
            .minOfOrNull { schedule ->
                (activeOccurrence(schedule, now)?.end ?: nextOccurrence(schedule, now).start).toInstant().toEpochMilli()
            }

    private fun activeOccurrence(
        schedule: ScheduleConfig,
        now: ZonedDateTime,
    ): Occurrence? {
        val instant = now.toInstant()
        return sequenceOf(now.toLocalDate().minusDays(1), now.toLocalDate())
            .map { occurrence(schedule, it, now.zone) }
            .firstOrNull { !instant.isBefore(it.start.toInstant()) && instant.isBefore(it.end.toInstant()) }
    }

    private fun nextOccurrence(
        schedule: ScheduleConfig,
        now: ZonedDateTime,
    ): Occurrence =
        generateSequence(now.toLocalDate()) { it.plusDays(1) }
            .map { occurrence(schedule, it, now.zone) }
            .first { it.start.toInstant().isAfter(now.toInstant()) }

    private fun occurrence(
        schedule: ScheduleConfig,
        startDate: LocalDate,
        zone: ZoneId,
    ): Occurrence {
        val crossesMidnight = schedule.windowEnd <= schedule.windowStart
        val endDate = if (crossesMidnight) startDate.plusDays(1) else startDate
        return Occurrence(
            start = resolve(startDate, schedule.windowStart, zone, preferLater = false),
            end = resolve(endDate, schedule.windowEnd, zone, preferLater = true),
        )
    }

    private fun resolve(
        date: LocalDate,
        time: LocalTime,
        zone: ZoneId,
        preferLater: Boolean,
    ): ZonedDateTime {
        val local = LocalDateTime.of(date, time)
        val offsets = zone.rules.getValidOffsets(local)
        if (offsets.isEmpty()) {
            val transition = requireNotNull(zone.rules.getTransition(local))
            return transition.dateTimeAfter.atZone(zone)
        }
        val candidates = offsets.map { offset -> ZonedDateTime.ofLocal(local, zone, offset) }
        return if (preferLater) candidates.maxBy { it.toInstant() } else candidates.minBy { it.toInstant() }
    }

    private data class Occurrence(
        val start: ZonedDateTime,
        val end: ZonedDateTime,
    )
}
