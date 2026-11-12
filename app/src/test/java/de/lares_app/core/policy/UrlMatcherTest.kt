/*
 * Lares – blocks distracting apps and websites; loosening needs a guardian code.
 * Copyright (C) 2026 Lares contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package de.lares_app.core.policy

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class UrlMatcherTest {

    @Test fun parsesUrlBarVariants() {
        assertEquals(WebAddress("instagram.com", ""), UrlMatcher.parse("instagram.com"))
        assertEquals(WebAddress("instagram.com", ""), UrlMatcher.parse("https://www.instagram.com/"))
        assertEquals(WebAddress("m.youtube.com", "/shorts/abc"), UrlMatcher.parse("http://user@M.YouTube.com:443/shorts/abc/?x=1#t"))
        assertEquals(WebAddress("xn--bcher-kva.de", ""), UrlMatcher.parse("bücher.de"))
    }

    @Test fun rejectsSearchTermsAndOtherSchemes() {
        assertNull(UrlMatcher.parse("funny cat videos"))
        assertNull(UrlMatcher.parse("cats"))
        assertNull(UrlMatcher.parse(""))
        assertNull(UrlMatcher.parse("chrome://settings"))
        assertNull(UrlMatcher.parse("file:///sdcard/x.html"))
    }

    @Test fun matchesSubdomainsButNotLookalikes() {
        assertTrue(UrlMatcher.matches("https://m.facebook.com/feed", "facebook.com"))
        assertTrue(UrlMatcher.matches("facebook.com", "www.facebook.com"))
        assertFalse(UrlMatcher.matches("notfacebook.com", "facebook.com"))
        assertFalse(UrlMatcher.matches("facebook.com.evil.org", "facebook.com"))
    }

    @Test fun baseHostForSorting() {
        assertEquals("youtube.com", UrlMatcher.baseHost("youtube.com"))
        assertEquals("youtube.com", UrlMatcher.baseHost("m.youtube.com"))
        assertEquals("bbc.co.uk", UrlMatcher.baseHost("news.bbc.co.uk"))
        assertEquals("localhost", UrlMatcher.baseHost("localhost"))
    }

    @Test fun pathRulesMatchBySegmentCaseInsensitively() {
        val rule = "youtube.com/shorts"
        assertTrue(UrlMatcher.matches("youtube.com/shorts", rule))
        assertTrue(UrlMatcher.matches("https://m.youtube.com/shorts/xyz", rule))
        assertTrue(UrlMatcher.matches("youtube.com/Shorts/xyz", rule))
        assertFalse(UrlMatcher.matches("youtube.com/shortsfoo", rule))
        assertFalse(UrlMatcher.matches("youtube.com/watch?v=1", rule))
        assertFalse(UrlMatcher.matches("youtube.com", rule))
    }

    @Test fun schemeOnlyAtTheStart() {
        assertTrue(UrlMatcher.matches("youtube.com/shorts/abc?x=https://a", "youtube.com/shorts"))
        assertTrue(UrlMatcher.matches("reddit.com/#://", "reddit.com"))
        assertTrue(UrlMatcher.matches("https://reddit.com/r/x?u=ftp://y", "reddit.com"))
        assertEquals(null, UrlMatcher.parse("ftp://reddit.com"))
    }
}
