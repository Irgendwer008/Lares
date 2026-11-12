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
        val state = store.current()
        val unlock = state.unlock
        // Before the first setup is complete, nothing is protected yet (e.g. the blocking mode can
        // still be changed on the setup page after the code was stored).
        if (!unlock.isSetUp || !state.settings.setupComplete) return true
        return unlock.unlockedSession?.isActive(clock) == true
    }

    /** @throws CodeRequiredException if [kind] is loosening and the app isn't unlocked. */
    suspend fun check(kind: ChangeKind) {
        if (kind.needsCode && !isOpen()) throw CodeRequiredException()
    }
}
