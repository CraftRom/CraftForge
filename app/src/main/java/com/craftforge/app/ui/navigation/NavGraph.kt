package com.craftforge.app.ui.navigation

import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.foundation.layout.*
import androidx.compose.material3.adaptive.navigationsuite.NavigationSuiteScaffold
import androidx.compose.ui.Modifier
import androidx.navigation.compose.*
import androidx.navigation.compose.NavHost
import com.craftforge.app.ui.screens.*

@Composable
fun CraftForgeApp() {
    val navController = rememberNavController()
    val currentBackStack by navController.currentBackStackEntryAsState()
    val currentDestination = AppDestinations.entries.find {
        it.route == currentBackStack?.destination?.route
    } ?: AppDestinations.HOME

    NavigationSuiteScaffold(
        navigationSuiteItems = {
            AppDestinations.entries.forEach { destination ->
                item(
                    icon = { Icon(destination.icon, contentDescription = destination.label) },
                    label = { Text(destination.label) },
                    selected = currentDestination == destination,
                    onClick = {
                        if (currentDestination != destination) {
                            navController.navigate(destination.route) {
                                popUpTo(AppDestinations.HOME.route)
                                launchSingleTop = true
                            }
                        }
                    }
                )
            }
        }
    ) {
        Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
            NavHost(
                navController = navController,
                startDestination = AppDestinations.HOME.route,
                modifier = Modifier.padding(innerPadding)
            ) {
                composable(AppDestinations.HOME.route) { HomeScreen() }
                composable(AppDestinations.TWEAKS.route) { TweaksScreen() }
            }
        }
    }
}
