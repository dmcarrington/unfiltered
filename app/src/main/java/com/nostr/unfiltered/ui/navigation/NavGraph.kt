package com.nostr.unfiltered.ui.navigation

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.nostr.unfiltered.ui.screens.auth.AuthScreen
import com.nostr.unfiltered.ui.screens.createpost.CreatePostScreen
import com.nostr.unfiltered.ui.screens.feed.FeedScreen
import com.nostr.unfiltered.ui.screens.profile.ProfileScreen
import com.nostr.unfiltered.ui.screens.search.SearchScreen
import com.nostr.unfiltered.viewmodel.AuthViewModel

sealed class Screen(val route: String) {
    object Auth : Screen("auth")
    object Feed : Screen("feed")
    object Profile : Screen("profile/{pubkey}") {
        fun createRoute(pubkey: String) = "profile/$pubkey"
    }
    object Search : Screen("search")
    object CreatePost : Screen("create_post")
    object Settings : Screen("settings")
}

@Composable
fun UnfilteredNavGraph(
    navController: NavHostController = rememberNavController(),
    authViewModel: AuthViewModel = hiltViewModel()
) {
    val isAuthenticated by authViewModel.isAuthenticated.collectAsState()

    val startDestination = if (isAuthenticated) Screen.Feed.route else Screen.Auth.route

    NavHost(
        navController = navController,
        startDestination = startDestination
    ) {
        composable(Screen.Auth.route) {
            AuthScreen(
                onAuthSuccess = {
                    navController.navigate(Screen.Feed.route) {
                        popUpTo(Screen.Auth.route) { inclusive = true }
                    }
                }
            )
        }

        composable(Screen.Feed.route) {
            FeedScreen(
                onProfileClick = { pubkey ->
                    navController.navigate(Screen.Profile.createRoute(pubkey))
                },
                onSearchClick = {
                    navController.navigate(Screen.Search.route)
                },
                onCreatePostClick = {
                    navController.navigate(Screen.CreatePost.route)
                },
                onSettingsClick = {
                    navController.navigate(Screen.Settings.route)
                }
            )
        }

        composable(
            route = Screen.Profile.route,
            arguments = listOf(navArgument("pubkey") { type = NavType.StringType })
        ) { backStackEntry ->
            val pubkey = backStackEntry.arguments?.getString("pubkey") ?: return@composable
            ProfileScreen(
                pubkey = pubkey,
                onBackClick = { navController.popBackStack() }
            )
        }

        composable(Screen.Search.route) {
            SearchScreen(
                onBackClick = { navController.popBackStack() },
                onUserClick = { pubkey ->
                    navController.navigate(Screen.Profile.createRoute(pubkey))
                }
            )
        }

        composable(Screen.CreatePost.route) {
            CreatePostScreen(
                onBackClick = { navController.popBackStack() },
                onPostSuccess = { navController.popBackStack() }
            )
        }

        composable(Screen.Settings.route) {
            PlaceholderScreen(title = "Settings", onBackClick = { navController.popBackStack() })
        }
    }
}

@Composable
private fun PlaceholderScreen(
    title: String,
    onBackClick: () -> Unit
) {
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.headlineMedium
            )
            Spacer(modifier = Modifier.height(16.dp))
            Text(
                text = "Coming in a future phase",
                style = MaterialTheme.typography.bodyMedium
            )
            Spacer(modifier = Modifier.height(24.dp))
            TextButton(onClick = onBackClick) {
                Text("Go Back")
            }
        }
    }
}
