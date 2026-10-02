package com.lagradost.quicknovel.discover

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.URLDecoder
import java.util.Locale

/** Pure JVM tests: no Android context, credentials, network, or extra test dependencies. */
class DiscoverApiTest {
    @Test
    fun tagsAreTrimmedLowercasedStrippedCollapsedAndDeduplicated() {
        assertEquals(listOf("martial arts", "slice of life"), normalizeDiscoverTags(listOf(
            "  {\"Martial,   Arts\"}  ", "MARTIAL\tARTS", " Slice\u00a0of\nlife ", " ,{}\" ",
        )))
    }

    @Test
    fun tagsUseLocaleIndependentLowercase() {
        val previous = Locale.getDefault()
        try {
            Locale.setDefault(Locale.forLanguageTag("tr-TR"))
            assertEquals(listOf("isekai"), normalizeDiscoverTags(listOf("ISEKAI")))
        } finally {
            Locale.setDefault(previous)
        }
    }

    @Test
    fun longTagsAreSkippedAndOnlyEightValidTagsAreKept() {
        assertEquals((1..8).map { "tag $it" }, normalizeDiscoverTags(
            listOf("x".repeat(41)) + (1..10).map { "tag $it" }
        ))
        assertEquals(listOf("x".repeat(40)), normalizeDiscoverTags(listOf("x".repeat(40))))
    }

    @Test
    fun combinedTagsRespectTheDatabaseCharacterBudget() {
        val tags = normalizeDiscoverTags((1..8).map { "$it" + "x".repeat(39) } + "short")
        assertEquals(8, tags.size)
        assertEquals("short", tags.last())
        assertTrue(tags.sumOf { it.discoverCharacterCount() } <= 300)
    }

    @Test
    fun feedEncodesTheWholeQuotedArrayContainmentValueIncludingSpaces() {
        val path = DiscoverApi.feedPath(40, " {\"Martial,  Arts\"} ")
        assertTrue(path.contains("order=created_at.desc&limit=20&offset=40"))
        assertEquals("cs.{\"martial arts\"}", URLDecoder.decode(path.substringAfter("&tags="), "UTF-8"))
        assertFalse(path.contains(' '))
        assertFalse(DiscoverApi.feedPath(0, null).contains("&tags="))
        assertFalse(DiscoverApi.feedPath(-10, "{}\",").contains("&tags="))
        assertTrue(DiscoverApi.feedPath(-10, null).endsWith("offset=0"))
    }

    @Test
    fun errorsMapToSafeFixedMessages() {
        assertEquals(DiscoverError.SignIn, DiscoverApi.errorOf(401, ""))
        assertEquals(DiscoverError.Duplicate, DiscoverApi.errorOf(409, ""))
        assertEquals(DiscoverError.Username, DiscoverApi.errorOf(400, "Set a USERNAME first"))
        assertEquals(DiscoverError.DailyLimit, DiscoverApi.errorOf(400, "Daily post limit reached"))
        assertEquals(DiscoverError.CouldNotPost, DiscoverApi.errorOf(403, "private server details"))
        assertEquals(DiscoverError.Generic, DiscoverApi.errorOf(503, "private server details"))
    }

    @Test
    fun bodyValidationRejectsWhitespaceAndHonorsTenToFiveHundredCharacters() {
        assertFalse(isDiscoverBodyValid(" ".repeat(10)))
        assertFalse(isDiscoverBodyValid("x".repeat(9)))
        assertTrue(isDiscoverBodyValid("x".repeat(10)))
        assertTrue(isDiscoverBodyValid("x".repeat(500)))
        assertFalse(isDiscoverBodyValid("x".repeat(501)))
        // Postgres char_length counts code points, not UTF-16 code units.
        assertTrue(isDiscoverBodyValid("\uD83D\uDCD6".repeat(500)))
        assertFalse(isDiscoverBodyValid("\uD83D\uDCD6".repeat(501)))
    }

    @Test
    fun timestampsAcceptSupabaseFractionsAndTimeZoneOffsetsOnApi23() {
        val utc = DiscoverApi.parseCreatedAt("2026-10-02T12:34:56+00:00")
        assertTrue(utc > 0)
        assertEquals(utc, DiscoverApi.parseCreatedAt("2026-10-02T12:34:56Z"))
        assertEquals(utc, DiscoverApi.parseCreatedAt("2026-10-02T14:34:56+02:00"))
        assertEquals(utc + 123, DiscoverApi.parseCreatedAt("2026-10-02T12:34:56.123456+00:00"))
        assertEquals(utc + 100, DiscoverApi.parseCreatedAt("2026-10-02T12:34:56.1Z"))
        assertEquals(0L, DiscoverApi.parseCreatedAt("invalid"))
    }
}
