package dev.pacer.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.pacer.BuildConfig
import dev.pacer.container
import dev.pacer.core.PresetEditor
import dev.pacer.core.formatMmSs
import dev.pacer.data.Settings
import kotlinx.coroutines.launch

@Composable
fun SettingsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val repo = context.container.settings
    val s by repo.settings.collectAsStateWithLifecycle(Settings())
    val scope = rememberCoroutineScope()
    Column(Modifier.fillMaxSize().safeDrawingPadding().padding(16.dp).verticalScroll(rememberScrollState())) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onBack) { Text("Back") }
            Text("Settings", style = MaterialTheme.typography.headlineSmall)
        }
        ToggleRow("Voice cues", s.voice, { scope.launch { repo.setVoice(it) } })
        ToggleRow("Haptics", s.haptics, { scope.launch { repo.setHaptics(it) } })
        ToggleRow("Pace coaching", s.coaching, { scope.launch { repo.setCoaching(it) } }, "Speed up / slow down cues")
        ToggleRow("Mile cues", s.mileCues, { scope.launch { repo.setMileCues(it) } }, "Split and average pace at each mile")
        Stepper("Min time between coaching cues", "${s.coachSpacingSec} s", { scope.launch { repo.setCoachSpacing(s.coachSpacingSec + it * 5) } })

        Text("Strava", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(top = 24.dp))
        Text(
            "Upload runs from the summary screen: Export GPX saves the file, then Upload to Strava opens " +
                "strava.com/upload/select where you pick it.",
            style = MaterialTheme.typography.bodySmall,
        )

        if (BuildConfig.DEBUG) {
            Text("Testing", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(top = 24.dp))
            ToggleRow(
                "Mock location", s.mockLocation, { scope.launch { repo.setMockLocation(it) } },
                "Next run replays a synthetic route instead of GPS",
            )
            if (s.mockLocation) {
                val gpx = context.container.mockGpxFile
                var hasGpx by remember { mutableStateOf(gpx.exists()) }
                val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
                    if (uri != null) scope.launch {
                        withContext(Dispatchers.IO) {
                            context.contentResolver.openInputStream(uri)?.use { input -> gpx.outputStream().use { input.copyTo(it) } }
                        }
                        hasGpx = gpx.exists()
                    }
                }
                if (hasGpx) {
                    Text("Replaying a GPX file", style = MaterialTheme.typography.bodyMedium)
                    TextButton(onClick = { gpx.delete(); hasGpx = false }) { Text("Use synthetic loop instead") }
                } else {
                    Stepper("Synthetic loop pace", "${formatMmSs(s.mockPaceSec)} /mi", {
                        scope.launch { repo.setMockPace(PresetEditor.pace(s.mockPaceSec, it * 15)) }
                    })
                    TextButton(onClick = { picker.launch(arrayOf("*/*")) }) { Text("Replay a GPX file instead…") }
                }
            }
        }
        Text("Pacer ${BuildConfig.VERSION_NAME}", style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 24.dp))
    }
}
