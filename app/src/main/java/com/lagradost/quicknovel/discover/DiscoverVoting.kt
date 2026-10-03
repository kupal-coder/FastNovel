package com.lagradost.quicknovel.discover

import kotlin.math.abs

/** Pure vote arithmetic shared by the Discover tab and the Home "Popular" row. */

/** Tapping the active arrow removes the vote; tapping the other arrow switches to it. */
fun discoverVoteTarget(currentVote: Int, tapped: Int): Int =
    if (currentVote == tapped) 0 else tapped

/** Optimistic score adjustment: score += newVote - oldVote. */
fun discoverVoteDelta(oldVote: Int, newVote: Int): Int = newVote - oldVote

/** Compact Reddit-style score: 999, 1.2k, 12.3k, 123k, 1.2M. Truncates, never rounds up. */
fun formatDiscoverScore(score: Int): String {
    val sign = if (score < 0) "-" else ""
    val abs = abs(score.toLong())
    return when {
        abs < 1_000 -> "$sign$abs"
        abs < 100_000 -> compactScore(sign, abs, 1_000L, "k")
        abs < 1_000_000 -> "$sign${abs / 1_000}k"
        else -> compactScore(sign, abs, 1_000_000L, "M")
    }
}

private fun compactScore(sign: String, abs: Long, divisor: Long, suffix: String): String {
    val tenths = abs * 10 / divisor
    val whole = tenths / 10
    val fraction = tenths % 10
    return if (fraction == 0L) "$sign$whole$suffix" else "$sign$whole.$fraction$suffix"
}
