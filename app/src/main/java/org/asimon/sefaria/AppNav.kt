package org.asimon.sefaria

import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.core.tween
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.res.stringResource
import androidx.annotation.StringRes
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.LibraryBooks
import androidx.compose.material.icons.outlined.Bookmarks
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material3.adaptive.navigationsuite.NavigationSuiteScaffoldLayout
import androidx.compose.material3.NavigationBarDefaults
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailDefaults
import androidx.compose.material3.NavigationRailItem
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.only
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.material3.adaptive.navigationsuite.NavigationSuiteType
import androidx.navigation.toRoute
import org.asimon.sefaria.feature.home.HomeScreen
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import org.asimon.sefaria.feature.book.BookScreen
import org.asimon.sefaria.feature.library.LibraryScreen
import org.asimon.sefaria.feature.reader.ReaderScreen
import org.asimon.sefaria.feature.saved.SavedScreen
import org.asimon.sefaria.feature.search.SearchScreen
import org.asimon.sefaria.feature.settings.SettingsScreen
import kotlin.reflect.KClass
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.unit.dp

private data class Tab(val route: Any, val type: KClass<*>, @StringRes val label: Int, val icon: ImageVector)

private val tabs = listOf(
    Tab(HomeRoute, HomeRoute::class, R.string.tab_home, Icons.Outlined.Home),
    Tab(LibraryRoute(), LibraryRoute::class, R.string.tab_library, Icons.AutoMirrored.Outlined.LibraryBooks),
    Tab(SavedRoute, SavedRoute::class, R.string.tab_saved, Icons.Outlined.Bookmarks),
)

/**
 * Bottom bar on phones, a side rail on wide screens (tablets, landscape); hidden in the reader.
 * Without [labels] the tabs show icons only, and the bottom bar is shorter.
 */
@Composable
internal fun AppNav(labels: Boolean) {
    val nav = rememberNavController()
    val entry by nav.currentBackStackEntryAsState()
    val dest = entry?.destination
    val showBar = dest != null && !dest.hasRoute(ReaderRoute::class) && !dest.hasRoute(SettingsRoute::class)
    fun selected(tab: Tab) = dest?.hierarchy?.any { it.hasRoute(tab.type) } == true ||
        (tab.type == LibraryRoute::class && dest?.hasRoute(BookRoute::class) == true)
    // which way a tab switch slides (+1: to the next tab, -1: to the previous); 0 for every other navigation
    var slide by remember { mutableIntStateOf(0) }
    // every other forward navigation fades, as before
    fun push(route: Any) {
        slide = 0
        nav.navigate(route)
    }
    val current = tabs.indexOfFirst(::selected)
    fun goTab(i: Int) {
        if (i !in tabs.indices || i == current) return
        if (current >= 0) slide = if (i > current) 1 else -1
        nav.switchTab(tabs[i].route)
    }
    val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    val wide = LocalConfiguration.current.screenWidthDp >= 600
    // by width alone: a phone in landscape also gets the rail, which keeps its short height for content
    val type = when {
        !showBar -> NavigationSuiteType.None
        wide -> NavigationSuiteType.NavigationRail
        else -> NavigationSuiteType.NavigationBar
    }
    // the scaffold's own bar has a fixed height, so the layout gets ours
    Surface(color = MaterialTheme.colorScheme.background) {
    NavigationSuiteScaffoldLayout(
        navigationSuite = { TabBar(type, labels, ::selected) { goTab(tabs.indexOf(it)) } },
        navigationSuiteType = type,
    ) {
        val open = { route: Any -> push(route) }
        // a tab switch slides the way the tabs lie (mirrored right to left); the rest fade, as before
        val side = if (rtl) -1 else 1
        NavHost(
            nav, startDestination = HomeRoute,
            // the bar pads itself for the system bars, so the pages don't
            modifier = Modifier
                .consumeWindowInsets(
                    when (type) {
                        NavigationSuiteType.NavigationBar -> NavigationBarDefaults.windowInsets.only(WindowInsetsSides.Bottom)
                        NavigationSuiteType.NavigationRail -> NavigationRailDefaults.windowInsets.only(WindowInsetsSides.Start)
                        else -> WindowInsets(0)
                    }
                )
                // swipe between the tabs wherever their bar shows
                .tabSwipe(enabled = showBar && current >= 0) { goTab(current + it) },
            enterTransition = {
                if (slide != 0) slideInHorizontally(tween(TAB_SLIDE_MS)) { w -> slide * side * w } else fadeIn(tween(FADE_MS))
            },
            exitTransition = {
                if (slide != 0) slideOutHorizontally(tween(TAB_SLIDE_MS)) { w -> -slide * side * w } else fadeOut(tween(FADE_MS))
            },
            popEnterTransition = { fadeIn(tween(FADE_MS)) },
            popExitTransition = { fadeOut(tween(FADE_MS)) },
        ) {
            composable<HomeRoute> {
                HomeScreen(onOpen = open, onSettings = { push(SettingsRoute) })
            }
            composable<LibraryRoute> {
                LibraryScreen(
                    onCategory = { push(it) },
                    onBook = { push(BookRoute(it)) },
                    onOpen = open,
                    onBack = { nav.popBackStack() },
                )
            }
            composable<BookRoute> {
                BookScreen(onOpen = { push(it) }, onBack = { nav.popBackStack() })
            }
            composable<ReaderRoute>(typeMap = ReaderRoute.typeMap) {
                ReaderScreen(onBack = { nav.popBackStack() }, onOpen = { push(it) }, onTrail = { slide = 0; nav.backAlongTrail(it) })
            }
            composable<SearchRoute> {
                SearchScreen(onOpen = { push(it) }, onBack = { nav.popBackStack() })
            }
            composable<SavedRoute> {
                SavedScreen(onOpen = { push(it) })
            }
            composable<SettingsRoute> {
                SettingsScreen(onBack = { nav.popBackStack() })
            }
        }
    }
    }
}

/** How long a tab switch slides; other screens fade in [FADE_MS], Navigation's default. */
private const val TAB_SLIDE_MS = 280
private const val FADE_MS = 700

/** The bottom bar's height without labels (with them, the standard 80dp). */
private val ShortBarHeight = 56.dp

@Composable
private fun TabBar(type: NavigationSuiteType, labels: Boolean, selected: (Tab) -> Boolean, onTab: (Tab) -> Unit) {
    // without labels the icon names the tab for screen readers
    val icon = @Composable { tab: Tab -> Icon(tab.icon, contentDescription = if (labels) null else stringResource(tab.label)) }
    fun label(tab: Tab): (@Composable () -> Unit)? = if (labels) { { Text(stringResource(tab.label)) } } else null
    when (type) {
        NavigationSuiteType.NavigationBar -> {
            val inset = NavigationBarDefaults.windowInsets.asPaddingValues().calculateBottomPadding()
            NavigationBar(if (labels) Modifier else Modifier.height(ShortBarHeight + inset)) {
                for (tab in tabs) NavigationBarItem(selected(tab), { onTab(tab) }, icon = { icon(tab) }, label = label(tab))
            }
        }
        NavigationSuiteType.NavigationRail -> NavigationRail {
            for (tab in tabs) NavigationRailItem(selected(tab), { onTab(tab) }, icon = { icon(tab) }, label = label(tab))
        }
        else -> {}
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
    val step = current.trail.getOrNull(i) ?: return
    val matches = target?.destination?.hasRoute(ReaderRoute::class) == true &&
        target.toRoute<ReaderRoute>().let { it.book == step.book && it.trail == current.trail.take(i) }
    if (matches) repeat(count) { popBackStack() }
    else navigate(ReaderRoute(step.book, step.ref, highlight = true, trail = current.trail.take(i)))
}
