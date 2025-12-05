package com.example.awwab

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.navigation.compose.rememberNavController
import com.example.awwab.navigation.navGraph
import com.example.awwab.ui.theme.AwwabTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        enableEdgeToEdge()

        setContent {
            val navController = rememberNavController()
            val scope = rememberCoroutineScope()

            AwwabTheme {
                Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->

                    // Move any heavy work to IO thread
                    LaunchedEffect(Unit) {
                        scope.launch {
                            withContext(Dispatchers.IO) {
                                // Example: load data, initialize DB, network, etc.
                                // heavyInitialization()
                            }
                        }
                    }

                    // Keep navGraph composable lightweight
                    navGraph(
                        modifier = Modifier.padding(innerPadding),
                        navcontroller = navController
                    )
                }
            }
        }
    }
}
