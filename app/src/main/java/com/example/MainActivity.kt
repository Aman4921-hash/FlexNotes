package com.example

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.ExperimentalAnimationApi
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.with
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import com.example.data.database.NoteEntity
import com.example.ui.CanvasScreen
import com.example.ui.HomeScreen
import com.example.ui.theme.MyApplicationTheme
import com.example.ui.viewmodel.NoteViewModel

sealed class Screen {
    object Home : Screen()
    data class Editor(val note: NoteEntity) : Screen()
}

class MainActivity : ComponentActivity() {

    private val noteViewModel: NoteViewModel by viewModels()

    @OptIn(ExperimentalAnimationApi::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        setContent {
            val isDarkMode by noteViewModel.isDarkMode.collectAsState()
            MyApplicationTheme(darkTheme = isDarkMode, dynamicColor = false) {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    var currentScreen by remember { mutableStateOf<Screen>(Screen.Home) }

                    // Custom transitions between bookshelf grid & drawing boards
                    AnimatedContent(
                        targetState = currentScreen,
                        transitionSpec = {
                            fadeIn() with fadeOut()
                        },
                        label = "ScreenTransition"
                    ) { screen ->
                        when (screen) {
                            is Screen.Home -> {
                                HomeScreen(
                                    viewModel = noteViewModel,
                                    onNoteClick = { note ->
                                        noteViewModel.loadNote(note)
                                        currentScreen = Screen.Editor(note)
                                    }
                                )
                            }
                            is Screen.Editor -> {
                                // Intercept system back press to save state and redirect to shelf
                                BackHandler {
                                    noteViewModel.clearActiveNote()
                                    currentScreen = Screen.Home
                                }

                                CanvasScreen(
                                    viewModel = noteViewModel,
                                    onBack = {
                                        noteViewModel.clearActiveNote()
                                        currentScreen = Screen.Home
                                    }
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
