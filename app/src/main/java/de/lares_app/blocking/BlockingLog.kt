/*
 * Lares – blocks distracting apps and websites; loosening needs a guardian code.
 * Copyright (C) 2026 Lares contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package de.lares_app.blocking

import android.util.Log

/**
 * Diagnostic output for the blocking logic (Logcat tag "LaresBlocking"). Only in debuggable builds,
 * so release builds never log which apps are used.
 */
object BlockingLog {
    @Volatile var enabled = false

    inline fun d(message: () -> String) {
        if (enabled) Log.d("LaresBlocking", message())
    }
}
