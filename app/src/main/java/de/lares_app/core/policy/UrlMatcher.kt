/*
 * Lares – blocks distracting apps and websites; loosening needs a guardian code.
 * Copyright (C) 2026 Lares contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package de.lares_app.core.policy

import java.net.IDN

/** A normalized host + path, e.g. host = `youtube.com`, path = `/shorts`. */
data class WebAddress(val host: String, val path: String) {
    override fun toString(): String = if (path.isEmpty() || path == "/") host else host + path
}

/**
 * Matches URL-bar contents against website rules.
 *
 * - Host matching includes subdomains: rule `youtube.com` matches `m.youtube.com`.
 * - `www.` is ignored on both sides.
 * - Path rules match by segment prefix, case-insensitively (so `/Shorts` can't bypass
 *   `/shorts`): rule `youtube.com/shorts` matches `/shorts` and `/shorts/abc`,
 *   but not `/shortsfoo`.
 */
object UrlMatcher {

    /** Parses what a browser shows in its URL bar. Returns null for search terms / garbage. */
    fun parse(input: String): WebAddress? {
        var s = input.trim()
        if (s.isEmpty() || s.any { it.isWhitespace() }) return null

        val schemeEnd = s.indexOf("://")
        if (schemeEnd >= 0) {
            val scheme = s.substring(0, schemeEnd).lowercase()
            if (scheme != "http" && scheme != "https") return null
            s = s.substring(schemeEnd + 3)
        }

        val pathStart = s.indexOfFirst { it == '/' || it == '?' || it == '#' }
        var authority = if (pathStart >= 0) s.substring(0, pathStart) else s
        var rest = if (pathStart >= 0) s.substring(pathStart) else ""

        authority = authority.substringAfterLast('@') // drop user info
        authority = stripPort(authority)
        val host = normalizeHost(authority) ?: return null

        rest = rest.substringBefore('#').substringBefore('?')
        val path = rest.trimEnd('/').lowercase()
        return WebAddress(host, path)
    }

    /** Normalizes a rule target as entered by the user (same rules as [parse]). */
    fun normalizeRule(target: String): WebAddress? = parse(target)

    /** Second-level suffixes where the registrable domain has three labels (e.g. bbc.co.uk). */
    private val TWO_PART_SUFFIXES = setOf(
        "co.uk", "org.uk", "ac.uk", "gov.uk", "com.au", "net.au", "org.au", "co.nz", "co.jp",
        "co.in", "com.br", "com.mx", "com.tr", "co.za", "com.cn", "com.ar", "co.kr",
    )

    /**
     * Base domain for sorting / grouping: `m.youtube.com` → `youtube.com`, `news.bbc.co.uk` →
     * `bbc.co.uk`. A heuristic (no public-suffix list), only used for display order.
     */
    fun baseHost(host: String): String {
        val labels = host.split('.')
        if (labels.size <= 2) return host
        val lastTwo = labels.takeLast(2).joinToString(".")
        return if (lastTwo in TWO_PART_SUFFIXES) labels.takeLast(3).joinToString(".") else lastTwo
    }

    fun matches(address: WebAddress, rule: WebAddress): Boolean {
        val hostMatches = address.host == rule.host || address.host.endsWith("." + rule.host)
        if (!hostMatches) return false
        if (rule.path.isEmpty()) return true
        return address.path == rule.path || address.path.startsWith(rule.path + "/")
    }

    fun matches(url: String, ruleTarget: String): Boolean {
        val address = parse(url) ?: return false
        val rule = normalizeRule(ruleTarget) ?: return false
        return matches(address, rule)
    }

    private fun stripPort(authority: String): String {
        if (authority.startsWith("[")) return authority // IPv6 literal, keep as is
        val colon = authority.lastIndexOf(':')
        return if (colon >= 0 && authority.substring(colon + 1).all { it.isDigit() }) {
            authority.substring(0, colon)
        } else {
            authority
        }
    }

    private fun normalizeHost(raw: String): String? {
        var host = raw.trim().trimEnd('.').lowercase()
        if (host.isEmpty()) return null
        host = try {
            IDN.toASCII(host, IDN.ALLOW_UNASSIGNED).lowercase()
        } catch (_: IllegalArgumentException) {
            return null
        }
        if (host.startsWith("www.")) host = host.removePrefix("www.")
        // Needs at least one dot (or be an IP / localhost) to be a plausible host.
        if (!host.contains('.') && host != "localhost" && !host.startsWith("[")) return null
        return host
    }
}
