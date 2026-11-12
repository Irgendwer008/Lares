/*
 * Lares – blocks distracting apps and websites; loosening needs a guardian code.
 * Copyright (C) 2026 Lares contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package de.lares_app.platform

/**
 * Manufacturer tips on dontkillmyapp.com (how to keep background apps alive). Only manufacturers
 * the site has a page for get a direct link – others get the start page (no "page not found").
 * Keep [pages] in sync with the site's vendor list.
 */
object DontKillMyApp {
    private const val BASE = "https://dontkillmyapp.com/"

    /** `Build.MANUFACTURER` (lowercase, letters / digits only) → page on the site. */
    private val pages = mapOf(
        "xiaomi" to "xiaomi", "redmi" to "xiaomi", "poco" to "xiaomi",
        "samsung" to "samsung",
        "oneplus" to "oneplus",
        "huawei" to "huawei", "honor" to "huawei",
        "ulefone" to "ulefone", "rugone" to "ulefone",
        "oppo" to "oppo",
        "meizu" to "meizu",
        "asus" to "asus",
        "wiko" to "wiko",
        "vivo" to "vivo",
        "tecno" to "tecno", "tecnomobile" to "tecno",
        "realme" to "realme",
        "motorola" to "motorola",
        "lenovo" to "lenovo",
        "blackview" to "blackview",
        "unihertz" to "unihertz",
        "sony" to "sony",
        "nokia" to "nokia",
        "hmdglobal" to "hmd-global",
        "htc" to "htc",
        "google" to "google",
    )

    fun url(manufacturer: String): String {
        val key = manufacturer.lowercase().filter { it.isLetterOrDigit() }
        return pages[key]?.let { BASE + it } ?: BASE
    }
}
