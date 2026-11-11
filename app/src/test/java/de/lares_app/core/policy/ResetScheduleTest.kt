/*
 * Lares – blocks distracting apps and websites; loosening needs a guardian code.
 * Copyright (C) 2026 Lares contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package de.lares_app.core.policy

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId

class ResetScheduleTest {

    private val berlin = ZoneId.of("Europe/Berlin")
    private fun at(s: String, zone: ZoneId = berlin) = LocalDateTime.parse(s).atZone(zone).toInstant()

    @Test fun midnightDefault() {
        val p = ResetSchedule().periodAt(at("2026-11-11T15:00"), berlin)
        assertEquals(UsagePeriod(at("2026-11-11T00:00"), at("2026-11-12T00:00")), p)
    }

    @Test fun customResetBeforeAndAfter() {
        val s = ResetSchedule(LocalTime.of(4, 0))
        assertEquals(UsagePeriod(at("2026-11-10T04:00"), at("2026-11-11T04:00")), s.periodAt(at("2026-11-11T02:00"), berlin))
        assertEquals(UsagePeriod(at("2026-11-11T04:00"), at("2026-11-12T04:00")), s.periodAt(at("2026-11-11T04:00"), berlin))
    }

    @Test fun dstChangeGives23HourPeriod() {
        val p = ResetSchedule().periodAt(at("2026-03-29T12:00"), berlin)
        assertEquals(23 * 3600L, p.end.epochSecond - p.start.epochSecond)
    }

    @Test fun changeTakesEffectAfterNextResetAndNeverShortensCurrentPeriod() {
        val now = at("2026-11-11T21:00")
        val s = ResetSchedule().requestChange(LocalTime.of(4, 0), now, berlin)
        assertEquals(PendingReset(LocalTime.of(4, 0), at("2026-11-12T00:00")), s.pending)
        // Current period unchanged.
        assertEquals(UsagePeriod(at("2026-11-11T00:00"), at("2026-11-12T00:00")), s.periodAt(now, berlin))
        // Transition period: old reset at midnight until the new reset at 04:00 the next day.
        assertEquals(UsagePeriod(at("2026-11-12T00:00"), at("2026-11-12T04:00")), s.periodAt(at("2026-11-12T01:00"), berlin))
        // Afterwards the new schedule applies and settles.
        assertEquals(UsagePeriod(at("2026-11-12T04:00"), at("2026-11-13T04:00")), s.periodAt(at("2026-11-12T10:00"), berlin))
        assertEquals(ResetSchedule(LocalTime.of(4, 0)), s.settle(at("2026-11-12T10:00"), berlin))
        assertEquals(s, s.settle(at("2026-11-12T01:00"), berlin))
    }

    @Test fun changeToEarlierTime() {
        val s = ResetSchedule(LocalTime.of(4, 0)).requestChange(LocalTime.of(20, 0), at("2026-11-11T10:00"), berlin)
        assertEquals(at("2026-11-12T04:00"), s.pending!!.effectiveFrom)
        assertEquals(UsagePeriod(at("2026-11-11T04:00"), at("2026-11-12T04:00")), s.periodAt(at("2026-11-11T21:00"), berlin))
        assertEquals(UsagePeriod(at("2026-11-12T04:00"), at("2026-11-12T20:00")), s.periodAt(at("2026-11-12T05:00"), berlin))
    }

    @Test fun requestingCurrentTimeCancelsPending() {
        val now = at("2026-11-11T10:00")
        val s = ResetSchedule().requestChange(LocalTime.of(4, 0), now, berlin)
        assertEquals(ResetSchedule(), s.requestChange(LocalTime.MIDNIGHT, now, berlin))
    }

    @Test fun advanceIsMonotonic() {
        val s = ResetSchedule()
        val p = s.advance(null, at("2026-11-11T10:00"), berlin)
        // Clock set back a day: still the same period, no fresh quota.
        assertEquals(p, s.advance(p, at("2026-11-10T10:00"), berlin))
        // Time-zone change that would make the period "end" earlier: still the same period.
        val tokyo = ZoneId.of("Asia/Tokyo")
        assertEquals(p, s.advance(p, at("2026-11-11T23:00"), tokyo))
        // Normal rollover.
        assertEquals(UsagePeriod(at("2026-11-12T00:00"), at("2026-11-13T00:00")), s.advance(p, at("2026-11-12T00:00"), berlin))
    }

    @Test fun advanceAfterZoneChangeDoesNotOverlap() {
        val s = ResetSchedule()
        val p = s.advance(null, at("2026-11-11T10:00"), berlin)
        // Moving west: in New York the new period would start before the old one ended.
        val ny = ZoneId.of("America/New_York")
        val next = s.advance(p, at("2026-11-12T00:00"), ny)
        assertEquals(p.end, next.start)
    }
}
