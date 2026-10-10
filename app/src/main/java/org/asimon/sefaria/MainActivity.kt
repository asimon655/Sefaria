package org.asimon.sefaria

import org.asimon.sefaria.ui.AppLanguage
import android.content.Context
import androidx.compose.ui.res.stringResource
import androidx.annotation.StringRes
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.LibraryBooks
import androidx.compose.material.icons.outlined.Bookmarks
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import dagger.hilt.android.AndroidEntryPoint
import org.asimon.sefaria.data.Settings
import org.asimon.sefaria.feature.book.BookScreen
import org.asimon.sefaria.feature.library.LibraryScreen
import org.asimon.sefaria.feature.reader.ReaderScreen
import org.asimon.sefaria.feature.saved.SavedScreen
import org.asimon.sefaria.feature.search.SearchScreen
import org.asimon.sefaria.feature.settings.SettingsScreen
import org.asimon.sefaria.ui.theme.SefariaTheme
import javax.inject.Inject
import kotlin.reflect.KClass

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    @Inject lateinit var settings: Settings

    override fun attachBaseContext(newBase: Context) = super.attachBaseContext(AppLanguage.wrap(newBase))

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            val prefs by settings.prefs.collectAsStateWithLifecycle()
            SefariaTheme(prefs.theme) { AppNav() }
        }
    }
}

private data class Tab(val route: Any, val type: KClass<*>, @StringRes val label: Int, val icon: ImageVector)

private val tabs = listOf(
    Tab(LibraryRoute(), LibraryRoute::class, R.string.tab_library, Icons.AutoMirrored.Outlined.LibraryBooks),
    Tab(SearchRoute, SearchRoute::class, R.string.tab_search, Icons.Outlined.Search),
    Tab(SavedRoute, SavedRoute::class, R.string.tab_saved, Icons.Outlined.Bookmarks),
)

@Composable
private fun AppNav() {
    val nav = rememberNavController()
    val entry by nav.currentBackStackEntryAsState()
    val dest = entry?.destination
    val showBar = dest != null && !dest.hasRoute(ReaderRoute::class) && !dest.hasRoute(SettingsRoute::class)
    Scaffold(
        bottomBar = {
            if (showBar) NavigationBar {
                for (tab in tabs) {
                    val selected = dest.hierarchy.any { it.hasRoute(tab.type) } ||
                        (tab.type == LibraryRoute::class && dest.hasRoute(BookRoute::class))
                    NavigationBarItem(
                        selected = selected,
                        onClick = { nav.switchTab(tab.route) },
                        icon = { Icon(tab.icon, contentDescription = null) },
                        label = { Text(stringResource(tab.label)) },
                    )
                }
            }
        },
    ) { padding ->
        // the bottom bar owns the bottom inset; screens' own Scaffolds handle the top
        val bottom = PaddingValues(bottom = padding.calculateBottomPadding())
        NavHost(nav, startDestination = LibraryRoute(), modifier = Modifier.padding(bottom).consumeWindowInsets(bottom)) {
            composable<LibraryRoute> {
                LibraryScreen(
                    onCategory = { nav.navigate(it) },
                    onBook = { nav.navigate(BookRoute(it)) },
                    onSettings = { nav.navigate(SettingsRoute) },
                    onBack = { nav.popBackStack() },
                )
            }
            composable<BookRoute> {
                BookScreen(onOpen = { nav.navigate(it) }, onBack = { nav.popBackStack() })
            }
            composable<ReaderRoute> {
                ReaderScreen(onBack = { nav.popBackStack() })
            }
            composable<SearchRoute> {
                SearchScreen(onOpen = { nav.navigate(it) })
            }
            composable<SavedRoute> {
                SavedScreen(onOpen = { nav.navigate(it) })
            }
            composable<SettingsRoute> {
                SettingsScreen(onBack = { nav.popBackStack() })
            }
        }
    }
}

private fun NavHostController.switchTab(route: Any) = navigate(route) {
    popUpTo(graph.findStartDestination().id) { saveState = true }
    launchSingleTop = true
    restoreState = true
}
