package com.example.awwab.screens

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.navigation.NavController
import androidx.navigation.NavHostController
import com.example.awwab.navigation.Screen

@Composable
fun HomeScreen(navController: NavHostController) {

    Button(
        onClick = {
            navController.navigate(Screen.Camera.route)

        },
modifier = Modifier.fillMaxWidth()
        )
    {
        Text(text = "Go to Camera")
    }
}