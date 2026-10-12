package org.asimon.sefaria

import org.asimon.sefaria.ui.AppLanguage
import android.content.Context
import androidx.compose.ui.res.stringResource
import androidx.annotation.StringRes
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.SystemBarStyle
import android.graphics.Color as AndroidColor
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.DisposableEffect
import org.asimon.sefaria.ui.theme.isDark
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.LibraryBooks
import androidx.compose.material.icons.outlined.Bookmarks
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material3.adaptive.navigationsuite.NavigationSuiteScaffold
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.material3.adaptive.navigationsuite.NavigationSuiteType
import androidx.navigation.toRoute
import org.asimon.sefaria.core.TrailStep
import org.asimon.sefaria.feature.home.HomeScreen
import org.asimon.sefaria.feature.onboarding.Onboarding
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
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
            // system bar icons follow the app's theme, not the system's
            val dark = prefs.theme.isDark(isSystemInDarkTheme())
            DisposableEffect(dark) {
                val style = if (dark) SystemBarStyle.dark(AndroidColor.TRANSPARENT)
                    else SystemBarStyle.light(AndroidColor.TRANSPARENT, AndroidColor.TRANSPARENT)
                enableEdgeToEdge(statusBarStyle = style, navigationBarStyle = style)
                onDispose {}
            }
            SefariaTheme(prefs.theme) {
                if (prefs.onboarded) AppNav()
                else Onboarding(
                    prefs.layout,
                    onLayout = { l -> lifecycleScope.launch { settings.setLayout(l) } },
                    onDone = { lifecycleScope.launch { settings.setOnboarded() } },
                )
            }
        }
    }
}

private data class Tab(val route: Any, val type: KClass<*>, @StringRes val label: Int, val icon: ImageVector)

private val tabs = listOf(
    Tab(HomeRoute, HomeRoute::class, R.string.tab_home, Icons.Outlined.Home),
    Tab(LibraryRoute(), LibraryRoute::class, R.string.tab_library, Icons.AutoMirrored.Outlined.LibraryBooks),
    Tab(SavedRoute, SavedRoute::class, R.string.tab_saved, Icons.Outlined.Bookmarks),
)

/** Bottom bar on phones, a side rail on wide screens (tablets, landscape); hidden in the reader. */
@Composable
private fun AppNav() {
    val nav = rememberNavController()
    val entry by nav.currentBackStackEntryAsState()
    val dest = entry?.destination
    val showBar = dest != null && !dest.hasRoute(ReaderRoute::class) && !dest.hasRoute(SettingsRoute::class)
    NavigationSuiteScaffold(
        navigationSuiteItems = {
            for (tab in tabs) {
                val selected = dest?.hierarchy?.any { it.hasRoute(tab.type) } == true ||
                    (tab.type == LibraryRoute::class && dest?.hasRoute(BookRoute::class) == true)
                item(
                    selected = selected,
                    onClick = { nav.switchTab(tab.route) },
                    icon = { Icon(tab.icon, contentDescription = null) },
                    label = { Text(stringResource(tab.label)) },
                )
            }
        },
        // by width alone: a phone in landscape also gets the rail, which keeps its short height for content
        layoutType = when {
            !showBar -> NavigationSuiteType.None
            LocalConfiguration.current.screenWidthDp >= 600 -> NavigationSuiteType.NavigationRail
            else -> NavigationSuiteType.NavigationBar
        },
    ) {
        val open = { route: Any -> nav.navigate(route) }
        NavHost(nav, startDestination = HomeRoute) {
            composable<HomeRoute> {
                HomeScreen(onOpen = open, onSettings = { nav.navigate(SettingsRoute) })
            }
            composable<LibraryRoute> {
                LibraryScreen(
                    onCategory = { nav.navigate(it) },
                    onBook = { nav.navigate(BookRoute(it)) },
                    onOpen = open,
                    onBack = { nav.popBackStack() },
                )
            }
            composable<BookRoute> {
                BookScreen(onOpen = { nav.navigate(it) }, onBack = { nav.popBackStack() })
            }
            composable<ReaderRoute> {
                ReaderScreen(onBack = { nav.popBackStack() }, onOpen = { nav.navigate(it) }, onTrail = nav::backAlongTrail)
            }
            composable<SearchRoute> {
                SearchScreen(onOpen = { nav.navigate(it) }, onBack = { nav.popBackStack() })
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

/**
 * Returns to step [i] of the current reader's trail. When the readers of the trail are the
 * entries right below this one (the usual case), pops back to keep their scroll positions;
 * otherwise (e.g. a trail resumed from history) opens the step anew.
 */
private fun NavHostController.backAlongTrail(i: Int) {
    val stack = currentBackStack.value
    val current = stack.lastOrNull()?.toRoute<ReaderRoute>() ?: return
    val count = current.trail.size - i
    val target = stack.getOrNull(stack.size - 1 - count)
    val step = TrailStep.decode(current.trail[i]) ?: return
    val matches = target?.destination?.hasRoute(ReaderRoute::class) == true &&
        target.toRoute<ReaderRoute>().let { it.book == step.book && it.trail == current.trail.take(i) }
    if (matches) repeat(count) { popBackStack() }
    else navigate(ReaderRoute(step.book, step.ref, highlight = true, trail = current.trail.take(i)))
}
