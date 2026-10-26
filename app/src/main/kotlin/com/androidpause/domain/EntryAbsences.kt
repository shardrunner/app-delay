package com.androidpause.domain

import com.androidpause.domain.model.ScheduleConfig
import com.androidpause.domain.model.ScheduleRuntime
import java.time.ZonedDateTime

/**
 * Covered-app absences for per-rule entries (FR-009). Returning to an entry's app within the
 * re-entry grace continues that entry; a longer absence ends it. Departures are held in memory:
 * entries whose departure was not observed (e.g. after a service restart) end conservatively.
 */
class EntryAbsences {
    private val leftAtMillis = HashMap<String, Long>()
    private var previousPkg: String? = null

    fun reconcile(
        pkg: String,
        nowMillis: Long,
        graceMillis: Long,
        runtime: Map<Long, ScheduleRuntime>,
    ): Map<Long, ScheduleRuntime> {
        val left = previousPkg.takeIf { it != pkg }
        previousPkg = pkg
        if (left != null && runtime.values.any { it.sessionPackage == left }) leftAtMillis.putIfAbsent(left, nowMillis)
        val absentSince = leftAtMillis.remove(pkg)
        val returnedLate = absentSince != null && nowMillis - absentSince >= graceMillis
        return runtime.mapValues { (_, value) ->
            val session = value.sessionPackage
            val ends =
                when (session) {
                    null -> false
                    pkg -> returnedLate
                    else -> session !in leftAtMillis
                }
            if (ends) value.copy(sessionPackage = null, sessionTimeGranted = false) else value
        }
    }
}

/**
 * Admits [pkg] as the current entry of each active covering rule not already in it. Entries
 * admitted by extending a pause are not openings.
 */
internal fun recordEntry(
    pkg: String,
    now: ZonedDateTime,
    schedules: List<ScheduleConfig>,
    runtime: Map<Long, ScheduleRuntime>,
    countsAsOpening: Boolean,
): Map<Long, ScheduleRuntime> {
    val entering = schedules.activeCovering(pkg, now).filter { runtime[it.id]?.sessionPackage != pkg }
    if (entering.isEmpty()) return runtime
    val updated = runtime.toMutableMap()
    entering.forEach { schedule ->
        val current = updated[schedule.id] ?: initialRuntime(schedule, now)
        val opened = countsAsOpening && schedule.openingAllowance != null
        updated[schedule.id] =
            current.copy(
                consumedOpenings = current.consumedOpenings + if (opened) 1 else 0,
                sessionPackage = pkg,
            )
    }
    return updated
}
