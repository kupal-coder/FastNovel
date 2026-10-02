package com.lagradost.quicknovel.comments

import com.lagradost.quicknovel.ui.result.ResultCommentsUiState
import com.lagradost.quicknovel.ui.result.applyDraftCommentUpdate
import com.lagradost.quicknovel.ui.result.applyDraftRatingUpdate
import com.lagradost.quicknovel.ui.result.applyInitialLoadCompletion
import com.lagradost.quicknovel.ui.result.mergePaginatedComments
import com.lagradost.quicknovel.ui.result.mergeUpsertedComment
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

class NovelCommentsTest {

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
                "?select=id,provider_name,novel_url,user_id,author_name,rating,comment,created_at,updated_at" +
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
            "https://example.supabase.co/rest/v1/novel_comment_reports",
            NovelCommentsApi.buildReportCommentUrl("https://example.supabase.co/"),
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
    fun signedOutUsersReadAuthorNameFromCommentRowWithoutQueryingProfiles() {
        var profilesQueried = false
        val transport = CommentHttpTransport { method, endpoint, _, bearerToken, _ ->
            assertNull(bearerToken)
            assertEquals("GET", method)
            if (endpoint.contains("/rest/v1/profiles")) {
                profilesQueried = true
                return@CommentHttpTransport CommentHttpResponse(code = 403, body = "[]")
            }
            CommentHttpResponse(
                code = 200,
                body = """
                    [
                      {
                        "id": "c-1",
                        "provider_name": "NovelBin",
                        "novel_url": "https://novelbin.com/b/book-1",
                        "user_id": "u-1",
                        "author_name": "bookworm_42",
                        "rating": 5,
                        "comment": "Awesome story!",
                        "created_at": "2026-10-02T10:00:00Z",
                        "updated_at": "2026-10-02T10:00:00Z"
                      },
                      {
                        "id": "c-2",
                        "provider_name": "NovelBin",
                        "novel_url": "https://novelbin.com/b/book-1",
                        "user_id": "u-2",
                        "author_name": "",
                        "rating": 4,
                        "comment": "Good read",
                        "created_at": "2026-10-02T09:00:00Z",
                        "updated_at": "2026-10-02T09:00:00Z"
                      }
                    ]
                """.trimIndent(),
            )
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

        assertFalse(profilesQueried)
        assertTrue(result is CommentLoadResult.Success)
        val page = (result as CommentLoadResult.Success).page
        assertEquals(2, page.comments.size)
        assertEquals("bookworm_42", page.comments[0].username)
        assertNull(page.comments[0].avatarUrl)
        assertEquals(5, page.comments[0].rating)
        assertEquals("Awesome story!", page.comments[0].comment)
        assertEquals("Anonymous", page.comments[1].username)
        assertFalse(page.hasMore)
    }

    @Test
    fun upsertHandlesSuccessAuthNetworkAndDailyLimitErrors() {
        var capturedPrefer: String? = null
        var capturedPayload: String? = null

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
                assertEquals("POST", method)
                capturedPrefer = prefer
                capturedPayload = payloadJson
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
                            "author_name": "reader_one",
                            "rating": 4,
                            "comment": "Updated review text",
                            "created_at": "2026-10-02T10:00:00Z",
                            "updated_at": "2026-10-02T11:00:00Z"
                          }
                        ]
                    """.trimIndent(),
                )
            },
        )

        assertEquals("resolution=merge-duplicates,return=representation", capturedPrefer)
        assertTrue(capturedPayload?.contains("\"user_id\":\"auth-user-1\"") == true)
        assertTrue(successResult is CommentWriteResult.Success)
        assertEquals("reader_one", (successResult as CommentWriteResult.Success).comment?.username)

        assertEquals(
            CommentWriteResult.DailyLimitReached,
            NovelCommentsApi.upsertCommentInternal(
                isConfigured = true,
                baseUrl = "https://example.supabase.co",
                providerName = "NovelBin",
                novelUrl = "https://novelbin.com/b/book-1",
                sessionUserId = "auth-user-1",
                accessToken = "jwt-token",
                rating = 4,
                comment = "Draft",
                transport = { _, _, _, _, _ ->
                    CommentHttpResponse(
                        code = 400,
                        body = """{"code":"P0001","message":"Daily comment limit reached"}""",
                    )
                },
            )
        )

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
    fun reportCommentMapsDuplicate23505ToAlreadyReportedAndHandlesOtherErrors() {
        val success = NovelCommentsApi.reportCommentInternal(
            isConfigured = true,
            baseUrl = "https://example.supabase.co",
            commentId = "c-99",
            sessionUserId = "user-1",
            accessToken = "jwt",
            reason = "Spam",
            transport = { method, endpoint, payloadJson, _, _ ->
                assertEquals("POST", method)
                assertTrue(endpoint.endsWith("/rest/v1/novel_comment_reports"))
                assertTrue(payloadJson?.contains("\"comment_id\":\"c-99\"") == true)
                CommentHttpResponse(code = 201, body = "")
            },
        )
        assertEquals(CommentReportResult.Success, success)

        val duplicate = NovelCommentsApi.reportCommentInternal(
            isConfigured = true,
            baseUrl = "https://example.supabase.co",
            commentId = "c-99",
            sessionUserId = "user-1",
            accessToken = "jwt",
            transport = { _, _, _, _, _ ->
                CommentHttpResponse(
                    code = 409,
                    body = """{"code":"23505","message":"duplicate key value violates unique constraint \"novel_comment_reports_comment_user_key\""}""",
                )
            },
        )
        assertEquals(CommentReportResult.AlreadyReported, duplicate)

        val genericFailure = NovelCommentsApi.reportCommentInternal(
            isConfigured = true,
            baseUrl = "https://example.supabase.co",
            commentId = "c-99",
            sessionUserId = "user-1",
            accessToken = "jwt",
            transport = { _, _, _, _, _ ->
                CommentHttpResponse(code = 500, body = """{"message":"internal error"}""")
            },
        )
        assertEquals(CommentReportResult.Failure, genericFailure)
    }

    @Test
    fun finishingLoadDoesNotOverwriteDraftTypedDuringLoad() {
        val stateFlow = MutableStateFlow(
            ResultCommentsUiState(
                providerName = "NovelBin",
                novelUrl = "https://novelbin.com/b/book-1",
                isInitialLoading = true,
            )
        )

        // User selects a rating and types a comment while the initial load is still in flight.
        stateFlow.update { applyDraftRatingUpdate(it, 5) }
        stateFlow.update { applyDraftCommentUpdate(it, "Typing my review while comments load") }

        val loadedComment = NovelComment(
            id = "c-1",
            providerName = "NovelBin",
            novelUrl = "https://novelbin.com/b/book-1",
            userId = "other-user",
            username = "bob",
            avatarUrl = null,
            rating = 4,
            comment = "Existing comment",
            createdAt = "2026-10-02T10:00:00Z",
            updatedAt = "2026-10-02T10:00:00Z",
        )

        // Initial load finishes after the user typed their draft.
        stateFlow.update {
            applyInitialLoadCompletion(
                state = it,
                providerName = "NovelBin",
                novelUrl = "https://novelbin.com/b/book-1",
                result = CommentLoadResult.Success(
                    NovelCommentsPage(comments = listOf(loadedComment), hasMore = false)
                ),
            )
        }

        val finalState = stateFlow.value
        assertFalse(finalState.isInitialLoading)
        assertEquals(1, finalState.comments.size)
        assertEquals(5, finalState.draftRating)
        assertEquals("Typing my review while comments load", finalState.draftComment)
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
