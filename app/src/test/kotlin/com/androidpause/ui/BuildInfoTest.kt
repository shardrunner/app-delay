package com.androidpause.ui

import java.time.ZoneId
import java.time.ZonedDateTime
import kotlin.test.Test
import kotlin.test.assertEquals

class BuildInfoTest {
    private val berlin = ZoneId.of("Europe/Berlin")

    /** 2026-08-22 10:40 local Berlin time (CEST, UTC+2). */
    private val installedAtMillis =
        ZonedDateTime.of(2026, 8, 22, 10, 40, 0, 0, berlin).toInstant().toEpochMilli()

    @Test
    fun `label combines version name with the local install timestamp`() {
        val label = buildInfoLabel("0.1.0", installedAtMillis, berlin)

        assertEquals("v0.1.0 · installed 2026-08-22 10:40", label)
    }

    @Test
    fun `install timestamp is rendered in the supplied zone`() {
        val label = buildInfoLabel("0.1.0", installedAtMillis, ZoneId.of("UTC"))

        assertEquals("v0.1.0 · installed 2026-08-22 08:40", label)
    }
}
