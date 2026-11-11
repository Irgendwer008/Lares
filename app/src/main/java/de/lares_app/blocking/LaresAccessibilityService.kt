/*
 * Lares – blocks distracting apps and websites; loosening needs a guardian code.
 * Copyright (C) 2026 Lares contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package de.lares_app.blocking

import android.accessibilityservice.AccessibilityService
import android.view.accessibility.AccessibilityEvent

/**
 * Detects the foreground app (and later the browser URL) for blocking. Declared now so the user can
 * enable it and the setup check can see it; the detection / blocking logic is added with the
 * blocking services. Events are ignored and nothing is stored.
 */
class LaresAccessibilityService : AccessibilityService() {
    override fun onAccessibilityEvent(event: AccessibilityEvent?) = Unit

    override fun onInterrupt() = Unit
}
