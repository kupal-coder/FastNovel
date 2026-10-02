package com.lagradost.quicknovel.ui.discover

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.google.android.material.bottomnavigation.BottomNavigationView
import com.lagradost.quicknovel.MainActivity.Companion.loadResult
import com.lagradost.quicknovel.R
import com.lagradost.quicknovel.auth.LoginActivity
import com.lagradost.quicknovel.compose.CloudStreamTheme
import com.lagradost.quicknovel.compose.loadPrimaryColor
import com.lagradost.quicknovel.compose.loadThemeMode

/** Hosted exactly like the existing Library and Settings Compose fragments. */
class DiscoverFragment : Fragment() {
    private val viewModel: DiscoverViewModel by viewModels()

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View = ComposeView(inflater.context).apply {
        setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
        setContent {
            CloudStreamTheme(
                mode = LocalContext.current.loadThemeMode(),
                primaryColor = LocalContext.current.loadPrimaryColor(),
            ) {
                val state by viewModel.state.collectAsStateWithLifecycle()
                DiscoverScreen(
                    state = state,
                    action = viewModel::onAction,
                    openNovel = { loadResult(it.novelUrl, it.provider) },
                    signIn = { startActivity(Intent(requireContext(), LoginActivity::class.java)) },
                    settings = {
                        activity?.findViewById<BottomNavigationView>(R.id.nav_view)?.selectedItemId =
                            R.id.navigation_settings
                    },
                )
            }
        }
    }

    override fun onResume() {
        super.onResume()
        viewModel.onResume()
    }
}
