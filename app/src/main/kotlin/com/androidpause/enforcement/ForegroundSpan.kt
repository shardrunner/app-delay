package com.androidpause.enforcement

/** A wall-clock boundary is mapped once, never subtracted from a later clock reading. */
internal class ForegroundSpan(
    private val startedElapsedMillis: Long,
    startedWallMillis: Long,
    boundaryWallMillis: Long?,
) {
    private val boundaryElapsedMillis =
        boundaryWallMillis?.let { startedElapsedMillis + (it - startedWallMillis).coerceAtLeast(0L) }

    fun elapsedMillis(nowElapsedMillis: Long): Long = (nowElapsedMillis - startedElapsedMillis).coerceAtLeast(0L)

    fun usageMillis(nowElapsedMillis: Long): Long {
        val end = boundaryElapsedMillis?.let { minOf(it, nowElapsedMillis) } ?: nowElapsedMillis
        return elapsedMillis(end)
    }

    fun remainingMillis(
        durationMillis: Long,
        nowElapsedMillis: Long,
    ): Long = (durationMillis - elapsedMillis(nowElapsedMillis)).coerceAtLeast(0L)
}
