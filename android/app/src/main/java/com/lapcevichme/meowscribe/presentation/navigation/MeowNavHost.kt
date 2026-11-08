package com.lapcevichme.meowscribe.presentation.navigation

import androidx.compose.runtime.Composable
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.lapcevichme.meowscribe.domain.model.SessionStatus
import com.lapcevichme.meowscribe.presentation.feature.home.HomeScreen
import com.lapcevichme.meowscribe.presentation.feature.processing.ProcessingScreen
import com.lapcevichme.meowscribe.presentation.feature.record.RecordScreen
import com.lapcevichme.meowscribe.presentation.feature.settings.SettingsScreen
import com.lapcevichme.meowscribe.presentation.feature.transcript.TranscriptScreen

@Composable
fun MeowNavHost() {
    val nav = rememberNavController()
    NavHost(navController = nav, startDestination = HomeRoute) {
        composable<HomeRoute> {
            HomeScreen(
                onOpenSession = { session, query ->
                    if (session.status == SessionStatus.DONE) nav.navigate(TranscriptRoute(session.id, query))
                    else nav.navigate(ProcessingRoute(session.id))
                },
                onRecord = { nav.navigate(RecordRoute) },
                onSettings = { nav.navigate(SettingsRoute) },
            )
        }
        composable<ProcessingRoute> {
            ProcessingScreen(
                onBack = { nav.popBackStack() },
                onOpenTranscript = { id ->
                    nav.navigate(TranscriptRoute(id)) {
                        popUpTo<ProcessingRoute> { inclusive = true }
                    }
                },
            )
        }
        composable<RecordRoute> {
            RecordScreen(
                onBack = { nav.popBackStack() },
                onSaved = { id ->
                    nav.navigate(ProcessingRoute(id)) {
                        popUpTo<RecordRoute> { inclusive = true }
                    }
                },
                onSettings = { nav.navigate(SettingsRoute) },
            )
        }
        composable<SettingsRoute> {
            SettingsScreen(
                onBack = { nav.popBackStack() },
                onOpenSession = { id -> nav.navigate(TranscriptRoute(id)) },
            )
        }
        composable<TranscriptRoute> {
            TranscriptScreen(
                onBack = { nav.popBackStack() },
                onReprocessing = { id ->
                    nav.navigate(ProcessingRoute(id)) {
                        popUpTo<TranscriptRoute> { inclusive = true }
                    }
                },
            )
        }
    }
}
