package com.lagradost.quicknovel.ui.result

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NovelCommentsApiTest {
    @Test
    fun acceptsOnlyAValidRatingAndNonBlankCommentWithinTheCodePointLimit() {
        assertTrue(isNovelCommentValid(1, "a"))
        assertTrue(isNovelCommentValid(5, "  thoughtful comment  "))
        assertTrue(isNovelCommentValid(3, "😀".repeat(NovelCommentsApi.MAX_COMMENT_CHARACTERS)))

        assertFalse(isNovelCommentValid(null, "comment"))
        assertFalse(isNovelCommentValid(0, "comment"))
        assertFalse(isNovelCommentValid(6, "comment"))
        assertFalse(isNovelCommentValid(4, " \n\t "))
        assertFalse(isNovelCommentValid(4, "😀".repeat(NovelCommentsApi.MAX_COMMENT_CHARACTERS + 1)))
    }

    @Test
    fun pagePathKeepsTheExactProviderAndNovelIdentityAndUsesTwentyItemPages() {
        val path = NovelCommentsApi.commentsPath(
            providerName = "Provider One",
            novelUrl = "https://novel.example/book?id=1&part=2",
            offset = 40,
        )

        assertTrue(path.startsWith("/rest/v1/novel_comments?select="))
        assertTrue(path.contains("provider_name=eq.Provider+One"))
        assertTrue(path.contains("novel_url=eq.https%3A%2F%2Fnovel.example%2Fbook%3Fid%3D1%26part%3D2"))
        assertTrue(path.contains("limit=${NovelCommentsApi.PAGE_SIZE}&offset=40"))
    }

    @Test
    fun deletePathEncodesTheCommentId() {
        assertTrue(NovelCommentsApi.deletePath("id/with spaces").endsWith("id=eq.id%2Fwith+spaces"))
    }
}
