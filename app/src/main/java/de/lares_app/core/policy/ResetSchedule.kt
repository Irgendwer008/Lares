/*
 * Lares – blocks distracting apps and websites; loosening needs a guardian code.
 * Copyright (C) 2026 Lares contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package de.lares_app.core.policy

import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime

/** One usage period ("day") for daily limits: [start, end). Usage is stored per period start. */
data class UsagePeriod(val start: Instant, val end: Instant) {
    operator fun contains(instant: Instant): Boolean = instant in start..<end
}

/** A requested reset-time change; it takes effect at [effectiveFrom] (the next regular reset). */
data class PendingReset(val resetTime: LocalTime, val effectiveFrom: Instant)

/**
 * Configurable daily reset time (default midnight). Changing it is loosening and only takes
 * effect after the next reset, so changing it never ends the current period early.
 */
data class ResetSchedule(
    val resetTime: LocalTime = LocalTime.MIDNIGHT,
    val pending: PendingReset? = null,
) {

    /** Schedules a change of the reset time, effective at the next reset under the current schedule. */
    fun requestChange(newTime: LocalTime, now: Instant, zone: ZoneId): ResetSchedule {
        val current = settle(now, zone)
        if (newTime == current.resetTime) return current.copy(pending = null)
        val next = current.periodAt(now, zone).end
        return current.copy(pending = PendingReset(newTime, next))
    }

    /** Applies a pending change once its transition period is completely over. */
    fun settle(now: Instant, zone: ZoneId): ResetSchedule {
        val p = pending ?: return this
        val transitionEnd = nextOccurrence(p.resetTime, p.effectiveFrom, zone)
        return if (now >= transitionEnd) ResetSchedule(p.resetTime) else this
    }

    /** The period containing [now]. */
    fun periodAt(now: Instant, zone: ZoneId): UsagePeriod {
        val p = pending
        return if (p == null || now < p.effectiveFrom) {
            val start = latestOccurrence(resetTime, now, zone)
            val end = nextOccurrence(resetTime, now, zone).let { if (p != null) minOf(it, p.effectiveFrom) else it }
            UsagePeriod(start, end)
        } else {
            // Transition: the period starting at the old reset lasts until the new reset time.
            val start = maxOf(latestOccurrence(p.resetTime, now, zone), p.effectiveFrom)
            UsagePeriod(start, nextOccurrence(p.resetTime, now, zone))
        }
    }

    /**
     * Advances the tracked period monotonically. A period only ends when its stored end is
     * reached, so setting the clock back never grants a fresh quota; setting it forward or
     * switching time zones doesn't either (both are also reported as tamper events):
     * - [elapsedSinceStartMillis] (monotonic time since the current period started, null if
     *   unknown – e.g. another boot): a period can't end before most of its length really passed.
     * - A period that follows directly (time-zone change near the end) lasts at least
     *   [MIN_FOLLOWING_PERIOD_MILLIS], so repeated zone changes can't chain short periods.
     */
    fun advance(current: UsagePeriod?, now: Instant, zone: ZoneId, elapsedSinceStartMillis: Long? = null): UsagePeriod {
        if (current != null && now < current.end) return current
        if (current != null && elapsedSinceStartMillis != null) {
            val length = current.end.toEpochMilli() - current.start.toEpochMilli()
            if (elapsedSinceStartMillis < length - CLOCK_TOLERANCE_MILLIS) return current // clock set forward
        }
        val next = periodAt(now, zone)
        return if (current != null && next.start < current.end) {
            // Never overlap the previous period (e.g. after a time-zone change).
            UsagePeriod(current.end, maxOf(next.end, current.end.plusMillis(MIN_FOLLOWING_PERIOD_MILLIS)))
        } else {
            next
        }
    }

    companion object {
        /** Daylight saving time can make a day an hour shorter; small clock corrections. */
        const val CLOCK_TOLERANCE_MILLIS = 2 * 60 * 60_000L

        /** Shortest regular day (DST) – a period after a zone change is never shorter. */
        const val MIN_FOLLOWING_PERIOD_MILLIS = 23 * 60 * 60_000L

        internal fun latestOccurrence(time: LocalTime, now: Instant, zone: ZoneId): Instant {
            val local = now.atZone(zone)
            var candidate = ZonedDateTime.of(local.toLocalDate(), time, zone)
            if (candidate.toInstant() > now) candidate = ZonedDateTime.of(local.toLocalDate().minusDays(1), time, zone)
            return candidate.toInstant()
        }

        internal fun nextOccurrence(time: LocalTime, now: Instant, zone: ZoneId): Instant {
            val local = now.atZone(zone)
            var candidate = ZonedDateTime.of(local.toLocalDate(), time, zone)
            if (candidate.toInstant() <= now) candidate = ZonedDateTime.of(local.toLocalDate().plusDays(1), time, zone)
            return candidate.toInstant()
        }
    }
}
