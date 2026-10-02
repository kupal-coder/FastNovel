package com.lagradost.quicknovel.comments

import com.lagradost.quicknovel.ui.result.mergePaginatedComments
import com.lagradost.quicknovel.ui.result.mergeUpsertedComment
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.IOException

class NovelCommentsTest {
    @Before
    fun setUp() {
        NovelCommentsApi.clearUsernameCacheForTests()
    }

    @Test
    fun buildsExpectedSupabaseEndpoints() {
        val getUrl = NovelCommentsApi.buildGetCommentsUrl(
            baseUrl = "https://example.supabase.co/",
            providerName = "WTR-LAB",
            novelUrl = "https://wtr-lab.com/en/serie-123/test-novel/",
            limit = 20,
            offset = 40,
        )
        assertEquals(
            "https://example.supabase.co/rest/v1/novel_comments" +
                "?select=id,provider_name,novel_url,user_id,rating,comment,created_at,updated_at" +
                "&provider_name=eq.WTR-LAB" +
                "&novel_url=eq.https%3A%2F%2Fwtr-lab.com%2Fen%2Fserie-123%2Ftest-novel" +
                "&order=created_at.desc" +
                "&limit=20" +
                "&offset=40",
            getUrl,
        )

        assertEquals(
            "https://example.supabase.co/rest/v1/novel_comments?on_conflict=provider_name,novel_url,user_id",
            NovelCommentsApi.buildUpsertCommentUrl("https://example.supabase.co/"),
        )

        assertEquals(
            "https://example.supabase.co/rest/v1/novel_comments?id=eq.comment-123",
            NovelCommentsApi.buildDeleteCommentUrl("https://example.supabase.co/", "comment-123"),
        )

        assertEquals(
            "https://example.supabase.co/rest/v1/profiles?id=eq.user-abc&select=username",
            NovelCommentsApi.buildProfileLookupUrl("https://example.supabase.co/", "user-abc"),
        )
    }

    @Test
    fun validatesRatingAndCommentLengthConstraints() {
        assertFalse(NovelCommentsApi.isValidSubmission(rating = 0, comment = "Valid comment"))
        assertFalse(NovelCommentsApi.isValidSubmission(rating = 6, comment = "Valid comment"))
        assertFalse(NovelCommentsApi.isValidSubmission(rating = 4, comment = "   "))
        assertFalse(NovelCommentsApi.isValidSubmission(rating = 4, comment = "a".repeat(2001)))
        assertTrue(NovelCommentsApi.isValidSubmission(rating = 1, comment = "a"))
        assertTrue(NovelCommentsApi.isValidSubmission(rating = 5, comment = "a".repeat(2000)))
    }

    @Test
    fun signedOutUsersCanReadPublicCommentsAndResolvePublicUsernameWithDefaultAvatar() {
        val transport = CommentHttpTransport { method, endpoint, _, bearerToken, _ ->
            assertNull(bearerToken)
            assertEquals("GET", method)
            when {
                endpoint.contains("/rest/v1/novel_comments") -> CommentHttpResponse(
                    code = 200,
                    body = """
                        [
                          {
                            "id": "c-1",
                            "provider_name": "NovelBin",
                            "novel_url": "https://novelbin.com/b/book-1",
                            "user_id": "u-1",
                            "rating": 5,
                            "comment": "Awesome story!",
                            "created_at": "2026-10-02T10:00:00Z",
                            "updated_at": "2026-10-02T10:00:00Z"
                          }
                        ]
                    """.trimIndent(),
                )
                endpoint.contains("/rest/v1/profiles?id=eq.u-1&select=username") -> CommentHttpResponse(
                    code = 200,
                    body = """[{"username":"bookworm_42"}]""",
                )
                else -> CommentHttpResponse(code = 404, body = "[]")
            }
        }

        val result = NovelCommentsApi.fetchCommentsInternal(
            isConfigured = true,
            baseUrl = "https://example.supabase.co",
            providerName = "NovelBin",
            novelUrl = "https://novelbin.com/b/book-1/",
            bearerToken = null,
            defaultUsername = "Anonymous",
            transport = transport,
        )

        assertTrue(result is CommentLoadResult.Success)
        val page = (result as CommentLoadResult.Success).page
        assertEquals(1, page.comments.size)
        val comment = page.comments.single()
        assertEquals("bookworm_42", comment.username)
        assertNull(comment.avatarUrl)
        assertEquals(5, comment.rating)
        assertEquals("Awesome story!", comment.comment)
        assertFalse(page.hasMore)
    }

    @Test
    fun upsertUsesAuthenticatedSessionIdentityAndHandlesAuthAndNetworkErrors() {
        var capturedPrefer: String? = null
        var capturedUserId: String? = null

        val successResult = NovelCommentsApi.upsertCommentInternal(
            isConfigured = true,
            baseUrl = "https://example.supabase.co",
            providerName = "NovelBin",
            novelUrl = "https://novelbin.com/b/book-1",
            sessionUserId = "auth-user-1",
            accessToken = "jwt-token",
            rating = 4,
            comment = "  Updated review text  ",
            transport = { method, endpoint, payloadJson, _, prefer ->
                if (method == "POST") {
                    capturedPrefer = prefer
                    capturedUserId = payloadJson
                    assertTrue(endpoint.endsWith("/rest/v1/novel_comments?on_conflict=provider_name,novel_url,user_id"))
                    CommentHttpResponse(
                        code = 201,
                        body = """
                            [
                              {
                                "id": "c-1",
                                "provider_name": "NovelBin",
                                "novel_url": "https://novelbin.com/b/book-1",
                                "user_id": "auth-user-1",
                                "rating": 4,
                                "comment": "Updated review text",
                                "created_at": "2026-10-02T10:00:00Z",
                                "updated_at": "2026-10-02T11:00:00Z"
                              }
                            ]
                        """.trimIndent(),
                    )
                } else {
                    CommentHttpResponse(code = 200, body = """[{"username":"reader_one"}]""")
                }
            },
        )

        assertEquals("resolution=merge-duplicates,return=representation", capturedPrefer)
        assertTrue(capturedUserId?.contains("\"user_id\":\"auth-user-1\"") == true)
        assertTrue(successResult is CommentWriteResult.Success)

        assertEquals(
            CommentWriteResult.NotSignedIn,
            NovelCommentsApi.upsertCommentInternal(
                isConfigured = true,
                baseUrl = "https://example.supabase.co",
                providerName = "NovelBin",
                novelUrl = "https://novelbin.com/b/book-1",
                sessionUserId = null,
                accessToken = null,
                rating = 4,
                comment = "Draft",
            )
        )

        assertEquals(
            CommentWriteResult.SessionExpired,
            NovelCommentsApi.upsertCommentInternal(
                isConfigured = true,
                baseUrl = "https://example.supabase.co",
                providerName = "NovelBin",
                novelUrl = "https://novelbin.com/b/book-1",
                sessionUserId = "auth-user-1",
                accessToken = "expired-jwt",
                rating = 4,
                comment = "Draft",
                transport = { _, _, _, _, _ -> CommentHttpResponse(code = 401, body = "{}") },
            )
        )

        assertEquals(
            CommentWriteResult.NetworkError,
            NovelCommentsApi.upsertCommentInternal(
                isConfigured = true,
                baseUrl = "https://example.supabase.co",
                providerName = "NovelBin",
                novelUrl = "https://novelbin.com/b/book-1",
                sessionUserId = "auth-user-1",
                accessToken = "jwt",
                rating = 4,
                comment = "Draft",
                transport = { _, _, _, _, _ -> throw IOException("Offline") },
            )
        )
    }

    @Test
    fun mergeUpsertedCommentUpdatesExistingEntryInPlaceAndEnforcesAuthorOwnership() {
        val original = NovelComment(
            id = "c-1",
            providerName = "NovelBin",
            novelUrl = "https://novelbin.com/b/book-1",
            userId = "user-1",
            username = "alice",
            avatarUrl = null,
            rating = 3,
            comment = "Initial thoughts",
            createdAt = "2026-10-02T10:00:00Z",
            updatedAt = "2026-10-02T10:00:00Z",
        )
        val otherUser = original.copy(
            id = "c-2",
            userId = "user-2",
            username = "bob",
            rating = 5,
            comment = "Loved it",
        )
        val updatedOriginal = original.copy(
            rating = 5,
            comment = "Edited thoughts after finishing",
            updatedAt = "2026-10-02T12:00:00Z",
        )

        val merged = mergeUpsertedComment(listOf(otherUser, original), updatedOriginal)
        assertEquals(2, merged.size)
        assertEquals(5, merged[1].rating)
        assertEquals("Edited thoughts after finishing", merged[1].comment)

        assertTrue(NovelCommentsApi.canModifyComment(original, "user-1"))
        assertFalse(NovelCommentsApi.canModifyComment(original, "user-2"))
        assertFalse(NovelCommentsApi.canModifyComment(original, null))

        val paginated = mergePaginatedComments(listOf(otherUser, original), listOf(original))
        assertEquals(2, paginated.size)
    }
}
