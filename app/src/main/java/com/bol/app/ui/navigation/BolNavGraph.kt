package com.bol.app.ui.navigation

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.toRoute
import com.bol.app.ui.screens.detail.CategoryDetailScreen
import com.bol.app.ui.screens.history.HistoryScreen
import com.bol.app.ui.screens.home.HomeScreen
import com.bol.app.ui.screens.recent.AllRecentScreen
import com.bol.app.ui.screens.recording.detail.RecordingDetailScreen
import com.bol.app.ui.screens.task.TaskDetailScreen
import com.bol.app.ui.screens.reminders.ScheduledRemindersScreen
import com.bol.app.ui.screens.restore.RestoreScreen
import com.bol.app.ui.screens.search.SearchDetailScreen
import com.bol.app.ui.screens.search.SearchScreen
import com.bol.app.ui.screens.onboarding.OnboardingScreen
import com.bol.app.ui.screens.about.OssLicensesScreen
import com.bol.app.ui.screens.logs.LogViewerScreen
import com.bol.app.ui.screens.settings.SettingsScreen
import kotlinx.serialization.Serializable

// Navigation routes
@Serializable data object HomeRoute
@Serializable data object SearchRoute
@Serializable data object HistoryRoute
@Serializable data object SettingsRoute
@Serializable data object OnboardingRoute
@Serializable data object LogViewerRoute
@Serializable data object ScheduledRemindersRoute
@Serializable data object AllRecentRoute
@Serializable data object RestoreRoute
@Serializable data object OssLicensesRoute
// A reminder tap opens TaskDetailRoute (MainActivity), so this no longer carries the reminder's item.
@Serializable data class CategoryDetailRoute(val categoryId: String)
@Serializable data class SearchDetailRoute(val searchId: String)
@Serializable data class RecordingDetailRoute(val recordingId: String)
@Serializable data class TaskDetailRoute(val itemId: String)

@Composable
fun BolNavGraph(
    navController: NavHostController,
    innerPadding: PaddingValues,
    startDestination: Any = HomeRoute,
) {
    NavHost(
        navController = navController,
        startDestination = startDestination,
        modifier = Modifier.padding(innerPadding)
    ) {
        composable<OnboardingRoute> {
            OnboardingScreen(
                onComplete = {
                    navController.navigate(HomeRoute) {
                        popUpTo(OnboardingRoute) { inclusive = true }
                    }
                }
            )
        }
        composable<HomeRoute> {
            HomeScreen(
                onCategoryClick = { categoryId ->
                    navController.navigate(CategoryDetailRoute(categoryId))
                },
                onItemClick = { itemId ->
                    navController.navigate(TaskDetailRoute(itemId))
                },
                onNavigateToAllRecent = {
                    navController.navigate(AllRecentRoute)
                },
            )
        }
        composable<AllRecentRoute> {
            AllRecentScreen(
                onBack = { navController.popBackStack() },
                onItemClick = { itemId ->
                    navController.navigate(TaskDetailRoute(itemId))
                },
            )
        }
        composable<TaskDetailRoute> { backStackEntry ->
            val route = backStackEntry.toRoute<TaskDetailRoute>()
            TaskDetailScreen(
                itemId = route.itemId,
                onBack = { navController.popBackStack() },
                onOpenCategory = { categoryId ->
                    navController.navigate(CategoryDetailRoute(categoryId))
                },
                onOpenRecording = { recordingId ->
                    navController.navigate(RecordingDetailRoute(recordingId))
                },
            )
        }
        composable<CategoryDetailRoute> { backStackEntry ->
            val route = backStackEntry.toRoute<CategoryDetailRoute>()
            CategoryDetailScreen(
                categoryId = route.categoryId,
                onBack = { navController.popBackStack() },
                onOpenItem = { itemId ->
                    navController.navigate(TaskDetailRoute(itemId))
                },
            )
        }
        composable<SearchRoute> {
            SearchScreen(
                onOpenDetail = { searchId ->
                    navController.navigate(SearchDetailRoute(searchId))
                }
            )
        }
        composable<SearchDetailRoute> { backStackEntry ->
            val route = backStackEntry.toRoute<SearchDetailRoute>()
            SearchDetailScreen(
                searchId = route.searchId,
                onBack = { navController.popBackStack() },
                // Retry: replace this detail screen with the new one so
                // the back stack stays short (Search → Detail, not Search
                // → Detail → Detail for every retry).
                onOpenSearch = { newId ->
                    navController.navigate(SearchDetailRoute(newId)) {
                        popUpTo(SearchDetailRoute(route.searchId)) { inclusive = true }
                    }
                },
                onOpenRecording = { recordingId ->
                    navController.navigate(RecordingDetailRoute(recordingId))
                },
            )
        }
        composable<HistoryRoute> {
            HistoryScreen(
                onOpenRecording = { recordingId ->
                    navController.navigate(RecordingDetailRoute(recordingId))
                },
            )
        }
        composable<RecordingDetailRoute> { backStackEntry ->
            val route = backStackEntry.toRoute<RecordingDetailRoute>()
            RecordingDetailScreen(
                recordingId = route.recordingId,
                onBack = { navController.popBackStack() },
            )
        }
        composable<SettingsRoute> {
            SettingsScreen(
                onNavigateToLogs = {
                    navController.navigate(LogViewerRoute)
                },
                onNavigateToScheduledReminders = {
                    navController.navigate(ScheduledRemindersRoute)
                },
                onNavigateToRestore = {
                    navController.navigate(RestoreRoute)
                },
                onNavigateToOssLicenses = {
                    navController.navigate(OssLicensesRoute)
                },
            )
        }
        composable<RestoreRoute> {
            RestoreScreen(onBack = { navController.popBackStack() })
        }
        composable<OssLicensesRoute> {
            OssLicensesScreen(onBack = { navController.popBackStack() })
        }
        composable<LogViewerRoute> {
            LogViewerScreen(onBack = { navController.popBackStack() })
        }
        composable<ScheduledRemindersRoute> {
            ScheduledRemindersScreen(onBack = { navController.popBackStack() })
        }
    }
}
