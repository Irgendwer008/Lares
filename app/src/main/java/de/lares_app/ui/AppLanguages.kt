/*
 * Lares – blocks distracting apps and websites; loosening needs a guardian code.
 * Copyright (C) 2026 Lares contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package de.lares_app.ui

/**
 * Languages the UI and guardian messages are translated into: language tag to endonym (shown in
 * the language's own name, so it isn't translated). Adding a language = add `values-xx/strings.xml`
 * and an entry here (see docs/localization.md).
 */
val AppLanguages: List<Pair<String, String>> = listOf(
    "en" to "English",
    "de" to "Deutsch",
)
