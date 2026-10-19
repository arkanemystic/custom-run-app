package dev.pacer

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import dev.pacer.core.Workout
import dev.pacer.service.RunService
import dev.pacer.service.RunState
import dev.pacer.ui.HomeScreen
import dev.pacer.ui.LiveRunScreen
import dev.pacer.ui.SettingsScreen
import dev.pacer.ui.WorkoutEditScreen
import dev.pacer.ui.WorkoutListScreen
import dev.pacer.ui.theme.PacerTheme
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        // A run in the database that no service owns means the process was killed mid-run.
        lifecycleScope.launch {
            if (RunService.state.value == RunState.Idle && container.runs.active() != null) RunService.recover(this@MainActivity)
        }
        setContent {
            PacerTheme {
                Surface(Modifier.fillMaxSize()) { PacerNav() }
            }
        }
    }
}

@Composable
private fun PacerNav() {
    val nav = rememberNavController()
    val state by RunService.state.collectAsStateWithLifecycle()
    // Per-run tweak of the selected preset, kept until the run starts or the preset changes.
    var draft by remember { mutableStateOf<Workout?>(null) }

    LaunchedEffect(state is RunState.Finished) {
        (state as? RunState.Finished)?.let {
            draft = null
            nav.navigate("summary/${it.runId}") { popUpTo("home") }
            RunService.acknowledgeFinished()
        }
    }

    val running = state as? RunState.Running
    if (running != null) {
        LiveRunScreen(running)
        return
    }

    NavHost(nav, startDestination = "home") {
        composable("home") {
            HomeScreen(
                draft = draft,
                onEditForRun = { nav.navigate("edit/${it.id}?forRun=true") ; draft = it },
                onWorkouts = { nav.navigate("workouts") },
                onHistory = { nav.navigate("history") },
                onSettings = { nav.navigate("settings") },
            )
        }
        composable("workouts") {
            WorkoutListScreen(onEdit = { nav.navigate("edit/$it") }, onBack = { nav.popBackStack() })
        }
        composable(
            "edit/{id}?forRun={forRun}",
            arguments = listOf(
                navArgument("id") { type = NavType.LongType },
                navArgument("forRun") { type = NavType.BoolType; defaultValue = false },
            ),
        ) { entry ->
            val forRun = entry.arguments!!.getBoolean("forRun")
            WorkoutEditScreen(
                presetId = entry.arguments!!.getLong("id"),
                forRun = if (forRun) draft else null,
                onUseForRun = { draft = it; nav.popBackStack() },
                onDone = { nav.popBackStack() },
            )
        }
        composable("settings") { SettingsScreen(onBack = { nav.popBackStack() }) }
        composable("history") { dev.pacer.ui.HistoryScreen(onOpen = { nav.navigate("summary/$it") }, onBack = { nav.popBackStack() }) }
        composable("summary/{id}", arguments = listOf(navArgument("id") { type = NavType.LongType })) { entry ->
            dev.pacer.ui.SummaryScreen(runId = entry.arguments!!.getLong("id"), onDone = { nav.popBackStack("home", false) })
        }
    }
}
