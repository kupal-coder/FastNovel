package com.lagradost.quicknovel.ui.home

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.core.view.GravityCompat
import androidx.drawerlayout.widget.DrawerLayout
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.google.android.material.bottomnavigation.BottomNavigationView
import com.lagradost.quicknovel.BookDownloader2
import com.lagradost.quicknovel.MainActivity.Companion.loadResult
import com.lagradost.quicknovel.MainActivity.Companion.navigate
import com.lagradost.quicknovel.R
import com.lagradost.quicknovel.ui.mainpage.MainPageFragment

class NovelHomeFragment : Fragment() {
    private val viewModel: NovelHomeViewModel by viewModels()

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View = ComposeView(inflater.context).apply {
        setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
        setContent {
            val state by viewModel.state.collectAsStateWithLifecycle()
            NovelHomeScreen(
                state = state,
                onOpenDrawer = {
                    activity?.findViewById<DrawerLayout>(R.id.main_drawer)
                        ?.openDrawer(GravityCompat.START)
                },
                onOpenSearch = { selectBottomNavigation(R.id.navigation_search) },
                onOpenAccount = { selectBottomNavigation(R.id.navigation_settings) },
                onContinueReading = { BookDownloader2.stream(it.novel) },
                onOpenNovel = { url, apiName -> loadResult(url, apiName) },
                onSeeMore = { target ->
                    activity?.navigate(
                        R.id.global_to_navigation_mainpage,
                        MainPageFragment.newInstance(
                            apiName = target.apiName,
                            mainCategory = target.categoryIndex,
                            orderBy = target.orderByIndex,
                            tag = -1,
                        ),
                    )
                },
                onSearchTag = { tag ->
                    activity?.navigate(
                        R.id.global_to_navigation_mainpage,
                        MainPageFragment.newTagSearchInstance(tagLabel = tag),
                    )
                },
                onOpenDiscover = { selectBottomNavigation(R.id.navigation_discover) },

                onRetry = viewModel::retry,
            )
        }
    }

    override fun onResume() {
        super.onResume()
        viewModel.onResume()
    }

    private fun selectBottomNavigation(itemId: Int) {
        activity?.findViewById<BottomNavigationView>(R.id.nav_view)?.selectedItemId = itemId
    }
}
