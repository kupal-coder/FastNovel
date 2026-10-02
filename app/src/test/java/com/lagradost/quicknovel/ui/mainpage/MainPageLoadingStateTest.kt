package com.lagradost.quicknovel.ui.mainpage

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MainPageLoadingStateTest {
    @Test
    fun failedPageAfterExcludedOnlyPageDoesNotKeepShowingSpinner() {
        assertFalse(
            shouldWaitForExcludedNovelPage(
                openQuery = false,
                hasItems = false,
                page = 1,
                hasMore = true,
                hasError = true,
            )
        )
    }

    @Test
    fun excludedOnlyPageWaitsForTheNextPageWhileThereIsNoError() {
        assertTrue(
            shouldWaitForExcludedNovelPage(
                openQuery = false,
                hasItems = false,
                page = 1,
                hasMore = true,
                hasError = false,
            )
        )
    }
}
