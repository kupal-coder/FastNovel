package com.lagradost.quicknovel.discover

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Pure JVM tests: vote arithmetic, score formatting, vote/score parsing and REST paths. */
class DiscoverVotingTest {
    @Test
    fun tappingTheActiveArrowRemovesTheVoteAndTheOtherArrowSwitches() {
        // none -> up/down
        assertEquals(1, discoverVoteTarget(0, 1))
        assertEquals(-1, discoverVoteTarget(0, -1))
        // up -> none (tap again) / down (switch)
        assertEquals(0, discoverVoteTarget(1, 1))
        assertEquals(-1, discoverVoteTarget(1, -1))
        // down -> none (tap again) / up (switch)
        assertEquals(0, discoverVoteTarget(-1, -1))
        assertEquals(1, discoverVoteTarget(-1, 1))
    }

    @Test
    fun voteDeltaCoversEveryTransition() {
        val votes = listOf(-1, 0, 1)
        for (old in votes) {
            for (new in votes) {
                assertEquals(new - old, discoverVoteDelta(old, new))
            }
        }
        // The resulting score for every transition, starting from a score of 5.
        assertEquals(5, 5 + discoverVoteDelta(0, 0))
        assertEquals(6, 5 + discoverVoteDelta(0, 1))
        assertEquals(4, 5 + discoverVoteDelta(0, -1))
        assertEquals(4, 5 + discoverVoteDelta(1, 0))
        assertEquals(5, 5 + discoverVoteDelta(1, 1))
        assertEquals(3, 5 + discoverVoteDelta(1, -1))
        assertEquals(6, 5 + discoverVoteDelta(-1, 0))
        assertEquals(7, 5 + discoverVoteDelta(-1, 1))
        assertEquals(5, 5 + discoverVoteDelta(-1, -1))
    }

    @Test
    fun scoresFormatCompactly() {
        assertEquals("0", formatDiscoverScore(0))
        assertEquals("7", formatDiscoverScore(7))
        assertEquals("999", formatDiscoverScore(999))
        assertEquals("1k", formatDiscoverScore(1000))
        assertEquals("1.2k", formatDiscoverScore(1234))
        assertEquals("9.9k", formatDiscoverScore(9999))
        assertEquals("10k", formatDiscoverScore(10000))
        assertEquals("12.3k", formatDiscoverScore(12345))
        assertEquals("99.9k", formatDiscoverScore(99999))
        assertEquals("100k", formatDiscoverScore(100000))
        assertEquals("999k", formatDiscoverScore(999999))
        assertEquals("1M", formatDiscoverScore(1000000))
        assertEquals("1.2M", formatDiscoverScore(1234567))
        assertEquals("-999", formatDiscoverScore(-999))
        assertEquals("-1.2k", formatDiscoverScore(-1234))
    }

    @Test
    fun scoreAndVoteValueParsingFallBackToSafeDefaults() {
        assertEquals(0, parseDiscoverScore(null))
        assertEquals(0, parseDiscoverScore(0))
        assertEquals(42, parseDiscoverScore(42))
        assertEquals(-3, parseDiscoverScore(-3))

        assertEquals(1, parseVoteValue(1))
        assertEquals(-1, parseVoteValue(-1))
        assertEquals(0, parseVoteValue(0))
        assertEquals(0, parseVoteValue(2))
        assertEquals(0, parseVoteValue(-9))
        assertEquals(0, parseVoteValue(null))
    }

    @Test
    fun myVotesAreAppliedPerPageAndUnknownOrInvalidEntriesStayNeutral() {
        val posts = listOf(post("a", score = 5), post("b"), post("c"), post("d"))
        val voted = applyDiscoverVotes(posts, mapOf("a" to 1, "b" to -1, "d" to 7, "other" to 1))

        assertEquals(1, voted[0].myVote)
        assertEquals(-1, voted[1].myVote)
        assertEquals(0, voted[2].myVote) // no vote row for this post
        assertEquals(0, voted[3].myVote) // invalid value coerced to no vote
        // Scores are owned by the database trigger and stay untouched.
        assertEquals(posts.map { it.score }, voted.map { it.score })
        assertEquals(posts.map { it.id }, voted.map { it.id })
    }

    @Test
    fun voteErrorsMapToFixedMessages() {
        assertEquals(VoteError.SignIn, DiscoverApi.voteErrorOf(401, ""))
        assertEquals(
            VoteError.OwnPost,
            DiscoverApi.voteErrorOf(400, "{\"message\":\"You can't vote on your own post\"}"),
        )
        assertEquals(VoteError.OwnPost, DiscoverApi.voteErrorOf(500, "OWN POST"))
        assertEquals(VoteError.Generic, DiscoverApi.voteErrorOf(400, "private server details"))
        assertEquals(VoteError.Generic, DiscoverApi.voteErrorOf(503, ""))
    }

    @Test
    fun voteEndpointsMatchTheRestContract() {
        assertEquals(
            "/rest/v1/discover_votes?on_conflict=post_id,user_id",
            DiscoverApi.voteUpsertPath(),
        )
        assertEquals(
            "/rest/v1/discover_votes?post_id=eq.p1&user_id=eq.u1",
            DiscoverApi.voteRemovePath("p1", "u1"),
        )
        assertEquals(
            "/rest/v1/discover_votes?select=post_id,value&post_id=in.(a,b,c)",
            DiscoverApi.votesPath(listOf("a", "b", "c")),
        )
    }

    @Test
    fun feedSortSwitchesBetweenNewAndTopOrdering() {
        assertTrue(DiscoverApi.feedPath(0, null).contains("order=created_at.desc"))
        assertTrue(DiscoverApi.feedPath(0, null, DiscoverSort.New).contains("order=created_at.desc"))
        assertTrue(
            DiscoverApi.feedPath(20, null, DiscoverSort.Top)
                .contains("order=score.desc,created_at.desc&limit=20&offset=20"),
        )
        // Tag filtering keeps working with both sorts.
        assertTrue(DiscoverApi.feedPath(0, "fantasy", DiscoverSort.Top).contains("&tags="))
        assertTrue(DiscoverApi.feedPath(0, "fantasy", DiscoverSort.Top).contains("order=score.desc"))
    }

    private fun post(id: String, score: Int = 0) = DiscoverPost(
        id = id,
        userId = "user-$id",
        authorName = "Author",
        provider = "Provider",
        novelUrl = "https://example.invalid/novel",
        novelTitle = "Title",
        coverUrl = null,
        body = "A body that is long enough.",
        rating = null,
        tags = emptyList(),
        createdAt = 0L,
        score = score,
    )
}
