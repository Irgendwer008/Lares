/*
 * Lares – blocks distracting apps and websites; loosening needs a guardian code.
 * Copyright (C) 2026 Lares contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package de.lares_app.core.blocking

/**
 * Follows "activity resumed" events (Usage access mode): the app of the latest one is in the
 * foreground. Each poll asks for the events since the previous poll, with a small overlap for
 * events written late; events already seen are skipped.
 */
class ForegroundTracker(
    private val lookbackMillis: Long = 60 * 60_000L,
    private val overlapMillis: Long = 2_000L,
) {
    /** App in the foreground, null until the first event. */
    var current: String? = null
        private set

    private var since: Long? = null
    private var lastEventAt = 0L

    /** Start of the time range to query at wall-clock [now] (end = [now]). */
    fun queryStart(now: Long): Long {
        val s = since
        if (s == null || now < s) { // first poll, or the clock was set back: start over
            lastEventAt = 0L
            since = now - lookbackMillis
        }
        return since!!
    }

    /** Resumed events (package, wall-clock time) of the range queried at [now], oldest first. */
    fun onResumed(events: List<Pair<String, Long>>, now: Long): String? {
        for ((pkg, at) in events) {
            if (at > lastEventAt) {
                lastEventAt = at
                current = pkg
            }
        }
        since = now - overlapMillis
        return current
    }
}
