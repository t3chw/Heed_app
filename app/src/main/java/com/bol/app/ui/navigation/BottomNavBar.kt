package com.bol.app.ui.navigation

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.NavHostController
import androidx.navigation.compose.currentBackStackEntryAsState
import com.bol.app.ui.theme.BolMono
import com.bol.app.ui.theme.HeedTheme

data class BottomNavItem(
    val label: String,
    val icon: ImageVector,
    val route: Any
)

val bottomNavItems = listOf(
    BottomNavItem("Home", Icons.Default.Home, HomeRoute),
    BottomNavItem("Search", Icons.Default.Search, SearchRoute),
    BottomNavItem("History", Icons.Default.History, HistoryRoute),
    BottomNavItem("Settings", Icons.Default.Settings, SettingsRoute),
)

@Composable
fun BottomNavBar(navController: NavHostController) {
    val currentEntry = navController.currentBackStackEntryAsState().value
    val currentDestination = currentEntry?.destination

    // The tab that "owns" the current screen stays highlighted on its detail screens (Instagram / TikTok pattern).
    // Switching tabs pops back to Home and pushes the tab's root, so the back stack is Home, at most one other tab
    // root, then detail screens: that other root, if present, is the owner (RecordingDetail reached from History or
    // from Search lights the tab in the user's path back). Asked through getBackStackEntry, because reading the
    // whole back stack (currentBackStack) is internal to the navigation library.
    val activeTabRoute: Any? = remember(currentEntry) {
        if (currentEntry == null) return@remember null
        bottomNavItems.drop(1).firstOrNull { tab ->
            runCatching { navController.getBackStackEntry(tab.route) }.isSuccess
        }?.route ?: HomeRoute
    }

    // Surface fills width and lets its background extend behind the Android system
    // navigation bar. `navigationBarsPadding()` on the inner Row keeps the pills and
    // their labels above the 3-button / gesture-pill area so they don't collide with
    // system UI (fixes the "labels cut under the dark grey strip" bug).
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.background,
    ) {
        Column {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(1.dp)
                    .background(MaterialTheme.colorScheme.outlineVariant),
            )
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .navigationBarsPadding()
                    .padding(horizontal = 14.dp)
                    .padding(top = 6.dp, bottom = 6.dp),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically,
            ) {
            bottomNavItems.forEach { item ->
                val selected = item.route == activeTabRoute
                val atTabRoot = currentDestination?.hasRoute(item.route::class) == true
                NavPill(
                    label = item.label,
                    icon = item.icon,
                    selected = selected,
                    onClick = {
                        when {
                            !selected -> {
                                // Switching tabs — preserve current tab's stack so
                                // coming back lands on its last detail screen.
                                navController.navigate(item.route) {
                                    popUpTo(HomeRoute) { saveState = true }
                                    launchSingleTop = true
                                    restoreState = true
                                }
                            }
                            !atTabRoot -> {
                                // Already on this tab but at a detail screen —
                                // pop back to the tab root (Instagram / TikTok).
                                navController.popBackStack(item.route, inclusive = false)
                            }
                            // else: already at the tab root, no-op.
                        }
                    },
                )
                }
            }
        }
    }
}

@Composable
private fun NavPill(
    label: String,
    icon: ImageVector,
    selected: Boolean,
    onClick: () -> Unit,
) {
    // Active: a small brand-soft pill behind the icon only (not the label), so the bar stays slim and narrow;
    // inactive: muted. The whole column is the touch target.
    val activeBg = HeedTheme.colors.brandSoft
    val activeFg = HeedTheme.colors.textPrimary
    val idleFg = HeedTheme.colors.textMuted
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(12.dp),
        color = Color.Transparent,
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 2.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Box(
                modifier = Modifier
                    .size(width = 48.dp, height = 28.dp)
                    .background(if (selected) activeBg else Color.Transparent, RoundedCornerShape(14.dp)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = label,
                    tint = if (selected) activeFg else idleFg,
                    modifier = Modifier.size(20.dp),
                )
            }
            Spacer(Modifier.size(2.dp))
            Text(
                text = label.uppercase(),
                style = BolMono.copy(
                    color = if (selected) activeFg else idleFg,
                    fontSize = 10.sp,
                ),
            )
        }
    }
}
