/*
 * Lares – blocks distracting apps and websites; loosening needs a guardian code.
 * Copyright (C) 2026 Lares contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package de.lares_app.core.policy

/**
 * Ready-made app + website combinations. App rules for apps that aren't installed are still
 * added: they take effect as soon as the app gets installed (no bypass by reinstalling).
 * Alternative clients (e.g. NewPipe for YouTube) are included so they can't be used to bypass.
 *
 * Names are brand names and therefore not translated. [color] is the commonly known brand color
 * (ARGB), used for the letter icon – no logos are shipped (see docs/decisions.md, "Icons").
 */
data class RuleTemplate(
    val id: String,
    val name: String,
    val apps: List<String>,
    val websites: List<String>,
    val color: Long,
) {
    val targets: List<Pair<RuleType, String>>
        get() = apps.map { RuleType.APP to it } + websites.map { RuleType.WEBSITE to it }
}

object RuleTemplates {
    /** Display names for template apps, used when the app isn't installed (no label from the system). */
    /** Brand color of the template an app or website belongs to (null if none). */
    fun colorFor(type: RuleType, target: String): Long? = when (type) {
        RuleType.APP -> all.firstOrNull { target in it.apps }?.color
        RuleType.WEBSITE -> UrlMatcher.normalizeRule(target)?.host?.let { host ->
            all.firstOrNull { t -> t.websites.any { host == it || host.endsWith(".$it") } }?.color
        }
    }

    val knownAppNames: Map<String, String> = mapOf(
        "com.google.android.youtube" to "YouTube",
        "app.revanced.android.youtube" to "YouTube ReVanced",
        "org.schabi.newpipe" to "NewPipe",
        "com.github.libretube" to "LibreTube",
        "com.instagram.android" to "Instagram",
        "com.instagram.lite" to "Instagram Lite",
        "com.zhiliaoapp.musically" to "TikTok",
        "com.ss.android.ugc.trill" to "TikTok (Asia)",
        "com.zhiliaoapp.musically.go" to "TikTok Lite",
        "com.snapchat.android" to "Snapchat",
        "com.facebook.katana" to "Facebook",
        "com.facebook.lite" to "Facebook Lite",
        "com.twitter.android" to "X (Twitter)",
        "com.reddit.frontpage" to "Reddit",
        "com.linkedin.android" to "LinkedIn",
        "com.instagram.barcelona" to "Threads",
        "com.pinterest" to "Pinterest",
        "tv.twitch.android.app" to "Twitch",
        "com.tumblr" to "Tumblr",
        "xyz.blueskyweb.app" to "Bluesky",
    )

    val all: List<RuleTemplate> = listOf(
        RuleTemplate(
            "youtube", "YouTube",
            apps = listOf(
                "com.google.android.youtube",
                "app.revanced.android.youtube", // ReVanced
                "org.schabi.newpipe", // NewPipe
                "com.github.libretube", // LibreTube
            ),
            websites = listOf("youtube.com", "youtu.be"),
            color = 0xFFFF0000,
        ),
        RuleTemplate(
            "instagram", "Instagram",
            apps = listOf("com.instagram.android", "com.instagram.lite"),
            websites = listOf("instagram.com"),
            color = 0xFFE1306C,
        ),
        RuleTemplate(
            "tiktok", "TikTok",
            apps = listOf("com.zhiliaoapp.musically", "com.ss.android.ugc.trill", "com.zhiliaoapp.musically.go"),
            websites = listOf("tiktok.com"),
            color = 0xFF000000,
        ),
        RuleTemplate(
            "snapchat", "Snapchat",
            apps = listOf("com.snapchat.android"),
            websites = listOf("snapchat.com"),
            color = 0xFFFFFC00,
        ),
        RuleTemplate(
            "facebook", "Facebook",
            apps = listOf("com.facebook.katana", "com.facebook.lite"),
            websites = listOf("facebook.com", "fb.com"),
            color = 0xFF1877F2,
        ),
        RuleTemplate(
            "x", "X (Twitter)",
            apps = listOf("com.twitter.android"),
            websites = listOf("x.com", "twitter.com"),
            color = 0xFF000000,
        ),
        RuleTemplate(
            "reddit", "Reddit",
            apps = listOf("com.reddit.frontpage"),
            websites = listOf("reddit.com", "redd.it"),
            color = 0xFFFF4500,
        ),
        RuleTemplate(
            "linkedin", "LinkedIn",
            apps = listOf("com.linkedin.android"),
            websites = listOf("linkedin.com"),
            color = 0xFF0A66C2,
        ),
        RuleTemplate(
            "threads", "Threads",
            apps = listOf("com.instagram.barcelona"),
            websites = listOf("threads.net", "threads.com"),
            color = 0xFF000000,
        ),
        RuleTemplate(
            "pinterest", "Pinterest",
            apps = listOf("com.pinterest"),
            websites = listOf("pinterest.com", "pinterest.de"),
            color = 0xFFE60023,
        ),
        RuleTemplate(
            "twitch", "Twitch",
            apps = listOf("tv.twitch.android.app"),
            websites = listOf("twitch.tv"),
            color = 0xFF9146FF,
        ),
        RuleTemplate(
            "tumblr", "Tumblr",
            apps = listOf("com.tumblr"),
            websites = listOf("tumblr.com"),
            color = 0xFF001935,
        ),
        RuleTemplate(
            "bluesky", "Bluesky",
            apps = listOf("xyz.blueskyweb.app"),
            websites = listOf("bsky.app"),
            color = 0xFF1185FE,
        ),
    )
}
