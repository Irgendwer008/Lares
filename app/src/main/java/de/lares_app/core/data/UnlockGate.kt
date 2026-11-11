/*
 * Lares – blocks distracting apps and websites; loosening needs a guardian code.
 * Copyright (C) 2026 Lares contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package de.lares_app.core.data

import de.lares_app.core.policy.ChangeKind
import de.lares_app.core.time.Clock

/** Single place that decides whether a loosening change may be applied right now (= app unlocked). */
class UnlockGate(
    private val store: StateStore,
    private val clock: Clock,
) {
    suspend fun isOpen(): Boolean {
        val unlock = store.current().unlock
        // Before the unlock method is set up (first start), nothing is protected yet.
        if (!unlock.isSetUp) return true
        return unlock.unlockedSession?.isActive(clock) == true
    }

    /** @throws CodeRequiredException if [kind] is loosening and the app isn't unlocked. */
    suspend fun check(kind: ChangeKind) {
        if (kind.needsCode && !isOpen()) throw CodeRequiredException()
    }
}
