package com.kidguard.ui.navigation

import androidx.compose.runtime.Composable
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.navArgument
import com.kidguard.ui.children.AddChildScreen
import com.kidguard.ui.children.ChildrenScreen
import com.kidguard.ui.children.FaceEnrollScreen
import com.kidguard.ui.home.HomeScreen
import com.kidguard.ui.lock.LockScreen
import com.kidguard.ui.logs.LogsScreen
import com.kidguard.ui.settings.SettingsScreen

sealed class Screen(val route: String) {
    data object Home : Screen("home")
    data object Children : Screen("children")
    data object AddChild : Screen("add_child")
    data object FaceEnroll : Screen("face_enroll/{childId}") {
        fun createRoute(childId: Long) = "face_enroll/$childId"
    }
    data object Settings : Screen("settings")
    data object Logs : Screen("logs")
    data object Lock : Screen("lock")
}

@Composable
fun NavGraph(navController: NavHostController) {
    NavHost(
        navController = navController,
        startDestination = Screen.Home.route
    ) {
        composable(Screen.Home.route) {
            HomeScreen(navController = navController)
        }
        composable(Screen.Children.route) {
            ChildrenScreen(navController = navController)
        }
        composable(Screen.AddChild.route) {
            AddChildScreen(navController = navController)
        }
        composable(
            route = Screen.FaceEnroll.route,
            arguments = listOf(navArgument("childId") { type = NavType.LongType })
        ) { backStackEntry ->
            val childId = backStackEntry.arguments?.getLong("childId") ?: return@composable
            FaceEnrollScreen(childId = childId, navController = navController)
        }
        composable(Screen.Settings.route) {
            SettingsScreen(navController = navController)
        }
        composable(Screen.Logs.route) {
            LogsScreen(navController = navController)
        }
        composable(Screen.Lock.route) {
            LockScreen()
        }
    }
}
