package com.podmix.ui.components

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Cast
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Home
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.navigation.NavHostController
import androidx.navigation.compose.currentBackStackEntryAsState
import com.podmix.navigation.Screen
import com.podmix.ui.theme.AccentPrimary
import com.podmix.ui.theme.Background
import com.podmix.ui.theme.SurfaceSecondary
import com.podmix.ui.theme.TextSecondary

private data class NavItem(val label: String, val route: String, val icon: ImageVector)

@Composable
fun BottomNavBar(navController: NavHostController) {
    val items = listOf(
        NavItem("Podcasts", Screen.Podcasts.route, Icons.Default.Home),
        NavItem("Favoris", Screen.Favorites.route, Icons.Default.Favorite),
        NavItem("Cast", Screen.Cast.route, Icons.Default.Cast),
    )
    val navBackStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = navBackStackEntry?.destination?.route

    NavigationBar(containerColor = SurfaceSecondary) {
        items.forEach { item ->
            NavigationBarItem(
                selected = currentRoute == item.route,
                onClick = {
                    navController.navigate(item.route) {
                        popUpTo(Screen.Podcasts.route) { saveState = true }
                        launchSingleTop = true
                        restoreState = true
                    }
                },
                icon = { Icon(item.icon, contentDescription = item.label) },
                label = { Text(item.label) },
                colors = NavigationBarItemDefaults.colors(
                    selectedIconColor = AccentPrimary,
                    selectedTextColor = AccentPrimary,
                    unselectedIconColor = TextSecondary,
                    unselectedTextColor = TextSecondary,
                    indicatorColor = Background
                )
            )
        }
    }
}
