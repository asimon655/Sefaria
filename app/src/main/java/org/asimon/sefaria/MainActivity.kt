package org.asimon.sefaria

import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountCircle
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.ShoppingCart
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.LayoutDirection
import androidx.activity.ComponentActivity
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.toRoute
import dagger.hilt.android.AndroidEntryPoint
import org.asimon.sefaria.screens.BookScreen
import org.asimon.sefaria.screens.HomeScreen


@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
                Nav()
            }
        }
    }
}

@Preview(showBackground = true)
@Composable
fun Nav() {
    val navController = rememberNavController()
    val navBackStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = navBackStackEntry?.destination?.route ?: DEFAULT_ROUTE
    Scaffold(
        bottomBar = {
            AnimatedVisibility(MAIN_ROUTES.indexOf(currentRoute) != -1) {
                NavigationBar {
                    NavigationBarItem(
                        selected = currentRoute == AccountRoute,
                        onClick = {
                            navController.navigate(AccountRoute) {
                                popUpTo(navController.graph.findStartDestination().id) {
                                    saveState = true
                                }
                                launchSingleTop = true
                                restoreState = true
                            }
                        },
                        icon = { Icon(Icons.Filled.AccountCircle, contentDescription = null) },
                        label = { Text("Account") })
                    NavigationBarItem(
                        selected = currentRoute == SearchRoute,
                        onClick = {
                            navController.navigate(SearchRoute) {
                                popUpTo(navController.graph.findStartDestination().id) {
                                    saveState = true
                                }
                                launchSingleTop = true
                                restoreState = true
                            }
                        },
                        icon = { Icon(Icons.Filled.Search, contentDescription = null) },
                        label = { Text("Search") })
                    NavigationBarItem(
                        selected = currentRoute == HomeRoute,
                        onClick = {
                            navController.navigate(HomeRoute) {
                                popUpTo(navController.graph.findStartDestination().id) {
                                    saveState = true
                                }
                                launchSingleTop = true
                                restoreState = true
                            }
                        },
                        icon = { Icon(Icons.Filled.Home, contentDescription = null) },
                        label = { Text("Home") })
                    NavigationBarItem(
                        selected = currentRoute == SavedRoute,
                        onClick = {
                            navController.navigate(SavedRoute) {
                                popUpTo(navController.graph.findStartDestination().id) {
                                    saveState = true
                                }
                                launchSingleTop = true
                                restoreState = true
                            }
                        },
                        icon = { Icon(Icons.Filled.ShoppingCart, contentDescription = null) },
                        label = { Text("Saved") })
                }
            }
        }
    ) { innerPadding ->
        NavHost(
            navController = navController,
            startDestination = DEFAULT_ROUTE,
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            composable(HomeRoute) {
                HomeScreen { ref -> navController.navigate(BookRoute(ref)) }
            }
            composable(SearchRoute) { Text("Search") }
            composable(AccountRoute) { Text("Account") }
            composable(SavedRoute) { Text("Saved") }
            composable<BookRoute> { backStackEntry ->
                val bookRoute: BookRoute = backStackEntry.toRoute()
                BookScreen(bookRoute)
            }
        }
    }
}