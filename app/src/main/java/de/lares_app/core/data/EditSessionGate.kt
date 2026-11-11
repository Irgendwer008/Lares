/*
 * Lares – blocks distracting apps and websites; loosening needs a guardian code.
 * Copyright (C) 2026 Lares contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package de.lares_app.core.data

import de.lares_app.core.policy.ChangeKind
import de.lares_app.core.time.Clock

/** Single place that decides whether a change may be applied right now. */
class EditSessionGate(
    private val store: StateStore,
    private val clock: Clock,
) {
    suspend fun isOpen(): Boolean {
        val unlock = store.current().unlock
        // Before the unlock method is set up (first start) or after recovery, nothing is protected yet.
        if (!unlock.isSetUp) return true
        return unlock.editSession?.isActive(clock) == true
    }

    /** @throws CodeRequiredException if [kind] is loosening and no edit session is open. */
    suspend fun check(kind: ChangeKind) {
        if (kind.needsCode && !isOpen()) throw CodeRequiredException()
    }
}
