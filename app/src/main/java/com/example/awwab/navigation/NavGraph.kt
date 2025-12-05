package com.example.awwab.navigation

import CameraComposeActivity
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import com.example.awwab.screens.HomeScreen

@Composable
fun navGraph(
    navcontroller : NavHostController,
    modifier : Modifier = Modifier
) {
    NavHost(
        navController = navcontroller,
        startDestination = Screen.Home.route,
        modifier = modifier)
    {
        composable(route = Screen.Home.route) {
            HomeScreen(navController = navcontroller)
        }
        composable(route = Screen.Camera.route) {
            CameraComposeActivity()
        }
    }
}