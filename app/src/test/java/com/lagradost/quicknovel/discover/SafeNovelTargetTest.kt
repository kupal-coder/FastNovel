package com.lagradost.quicknovel.discover

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure JVM tests for the URL half of [isSafeNovelTarget]; the provider lookup itself is a single
 * Apis.getApiFromNameNull call that only fails closed.
 */
class SafeNovelTargetTest {
    private val mainUrl = "https://wuxiabox.example"

    @Test
    fun httpsUrlsOnTheProviderHostOrItsSubdomainsAreSafe() {
        assertTrue(isSafeNovelUrl(mainUrl, "https://wuxiabox.example/novel/a-b"))
        assertTrue(isSafeNovelUrl(mainUrl, "https://sub.wuxiabox.example/novel/a"))
        assertTrue(isSafeNovelUrl(mainUrl, "https://deep.sub.wuxiabox.example/n"))
        // A mainUrl with a path still only contributes its host.
        assertTrue(isSafeNovelUrl("https://wuxiabox.example/novels/", "https://wuxiabox.example/n"))
        // Hosts compare case-insensitively.
        assertTrue(isSafeNovelUrl("https://WuxiaBox.example", "https://WUXIABOX.EXAMPLE/N"))
    }

    @Test
    fun otherSchemesHostsAndMalformedUrlsAreRejected() {
        assertFalse(isSafeNovelUrl(mainUrl, "http://wuxiabox.example/n"))
        assertFalse(isSafeNovelUrl(mainUrl, "file:///wuxiabox.example/n"))
        assertFalse(isSafeNovelUrl(mainUrl, "https://evil.example/n"))
        assertFalse(isSafeNovelUrl(mainUrl, "https://wuxiabox.example.evil.example/n"))
        assertFalse(isSafeNovelUrl(mainUrl, "https://evilwuxiabox.example/n"))
        assertFalse(isSafeNovelUrl(mainUrl, "https://evil.com/https://wuxiabox.example/n"))
        assertFalse(isSafeNovelUrl(mainUrl, "not a url"))
        assertFalse(isSafeNovelUrl(mainUrl, "/novel/relative"))
    }

    @Test
    fun missingValuesFailClosed() {
        assertFalse(isSafeNovelUrl(mainUrl, null))
        assertFalse(isSafeNovelUrl(mainUrl, ""))
        assertFalse(isSafeNovelUrl(null, "https://wuxiabox.example/n"))
        assertFalse(isSafeNovelUrl("", "https://wuxiabox.example/n"))
        // MainAPI's untouched default mainUrl is not a usable host.
        assertFalse(isSafeNovelUrl("NONE", "https://wuxiabox.example/n"))
    }
}
