package org.asimon.sefaria

import org.asimon.sefaria.ui.AppLanguage
import android.content.Context
import androidx.compose.ui.res.stringResource
import androidx.annotation.StringRes
import android.os.Bundle
import android.view.ViewTreeObserver
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.SystemBarStyle
import android.graphics.Color as AndroidColor
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.DisposableEffect
import org.asimon.sefaria.ui.theme.isDark
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
import org.asimon.sefaria.feature.onboarding.Onboarding
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
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
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.produceState
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.asimon.sefaria.ui.DeviceFonts
import org.asimon.sefaria.ui.LocalTextFonts
import org.asimon.sefaria.ui.TextFonts

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    @Inject lateinit var settings: Settings

    override fun attachBaseContext(newBase: Context) = super.attachBaseContext(AppLanguage.wrap(newBase))

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            val prefs by settings.prefs.collectAsStateWithLifecycle()
            val fonts by produceState(TextFonts(), prefs.heFont, prefs.enFont) {
                value = withContext(Dispatchers.IO) { TextFonts(DeviceFonts.family(prefs.heFont), DeviceFonts.family(prefs.enFont)) }
            }
            // system bar icons follow the app's theme, not the system's
            val dark = prefs.theme.isDark(isSystemInDarkTheme())
            DisposableEffect(dark) {
                val style = if (dark) SystemBarStyle.dark(AndroidColor.TRANSPARENT)
                    else SystemBarStyle.light(AndroidColor.TRANSPARENT, AndroidColor.TRANSPARENT)
                enableEdgeToEdge(statusBarStyle = style, navigationBarStyle = style)
                onDispose {}
            }
            SefariaTheme(prefs.theme) {
                CompositionLocalProvider(LocalTextFonts provides fonts) {
                if (prefs.onboarded) AppNav(prefs.navLabels)
                else Onboarding(
                    prefs.layout,
                    onLayout = { l -> lifecycleScope.launch { settings.setLayout(l) } },
                    onDone = { lifecycleScope.launch { settings.setOnboarded() } },
                )
                }
            }
        }
        // deferred startup work (App) waits for the first frame
        val view = window.decorView
        view.viewTreeObserver.addOnDrawListener(object : ViewTreeObserver.OnDrawListener {
            override fun onDraw() {
                Startup.firstFrameDrawn()
                view.post { view.viewTreeObserver.removeOnDrawListener(this) } // not while drawing
            }
        })
    }
}

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
private fun AppNav(labels: Boolean) {
    val nav = rememberNavController()
    val entry by nav.currentBackStackEntryAsState()
    val dest = entry?.destination
    val showBar = dest != null && !dest.hasRoute(ReaderRoute::class) && !dest.hasRoute(SettingsRoute::class)
    fun selected(tab: Tab) = dest?.hierarchy?.any { it.hasRoute(tab.type) } == true ||
        (tab.type == LibraryRoute::class && dest?.hasRoute(BookRoute::class) == true)
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
        navigationSuite = { TabBar(type, labels, ::selected) { nav.switchTab(it.route) } },
        navigationSuiteType = type,
    ) {
        val open = { route: Any -> nav.navigate(route) }
        NavHost(
            nav, startDestination = HomeRoute,
            // the bar pads itself for the system bars, so the pages don't
            modifier = Modifier.consumeWindowInsets(
                when (type) {
                    NavigationSuiteType.NavigationBar -> NavigationBarDefaults.windowInsets.only(WindowInsetsSides.Bottom)
                    NavigationSuiteType.NavigationRail -> NavigationRailDefaults.windowInsets.only(WindowInsetsSides.Start)
                    else -> WindowInsets(0)
                }
            ),
        ) {
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
            composable<ReaderRoute>(typeMap = ReaderRoute.typeMap) {
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
}

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
