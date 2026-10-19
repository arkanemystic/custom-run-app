package dev.pacer.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.pacer.container
import dev.pacer.core.PresetEditor
import dev.pacer.core.PresetEditor.DURATION_STEP_SEC
import dev.pacer.core.PresetEditor.PACE_STEP_SEC
import dev.pacer.core.Step
import dev.pacer.core.Workout
import dev.pacer.core.formatMmSs
import kotlinx.coroutines.launch

@Composable
fun WorkoutListScreen(onEdit: (Long) -> Unit, onBack: () -> Unit) {
    val context = LocalContext.current
    val presets by context.container.presets.presets.collectAsStateWithLifecycle(emptyList())
    Column(Modifier.fillMaxSize().safeDrawingPadding().padding(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onBack) { Text("Back") }
            Text("Workouts", style = MaterialTheme.typography.headlineSmall, modifier = Modifier.weight(1f))
            Button(onClick = { onEdit(0) }) { Text("New") }
        }
        LazyColumn(verticalArrangement = Gap, modifier = Modifier.padding(top = 12.dp)) {
            items(presets, key = { it.id }) { p ->
                Card(onClick = { onEdit(p.id) }, modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp)) {
                        Text(p.name, style = MaterialTheme.typography.titleMedium)
                        Text(describe(p), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
    }
}

/**
 * Edits a preset. With [forRun] set, edits apply to the upcoming run only ("Use for this run")
 * unless saved back to the preset.
 */
@Composable
fun WorkoutEditScreen(
    presetId: Long,
    forRun: Workout?,
    onUseForRun: (Workout) -> Unit,
    onDone: () -> Unit,
) {
    val context = LocalContext.current
    val repo = context.container.presets
    val scope = rememberCoroutineScope()
    val all by repo.presets.collectAsStateWithLifecycle(emptyList())
    var w by remember { mutableStateOf<Workout?>(forRun) }
    var confirmDelete by remember { mutableStateOf(false) }

    LaunchedEffect(presetId) {
        if (w == null) {
            w = if (presetId == 0L) {
                Workout.STARTER.copy(name = PresetEditor.uniqueName("New workout", all.map { it.name }))
            } else repo.get(presetId)
        }
    }
    val cur = w ?: return

    fun save(then: (Long) -> Unit) = scope.launch {
        val id = repo.save(cur)
        then(id)
    }

    Column(Modifier.fillMaxSize().safeDrawingPadding()) {
        Row(Modifier.padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onDone) { Text("Cancel") }
            Text(if (forRun != null) "Adjust for this run" else "Edit workout", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
        }
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp)) {
            OutlinedTextField(
                value = cur.name, onValueChange = { w = cur.copy(name = it) },
                label = { Text("Name") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
            )
            Section("Warm-up")
            StepEditor(cur.warmup) { w = cur.copy(warmup = it) }
            Section("Repeats")
            Stepper("Number of repeats", "${cur.repeats}", { w = cur.copy(repeats = PresetEditor.repeats(cur.repeats, it)) })
            Section("Run")
            StepEditor(cur.run) { w = cur.copy(run = it) }
            Section("Walk")
            StepEditor(cur.walk) { w = cur.copy(walk = it) }
            Section("Cool-down")
            StepEditor(cur.cooldown) { w = cur.copy(cooldown = it) }
            Section("Coaching band")
            Stepper("Tolerance (run, warm-up, cool-down)", "± ${cur.toleranceSec} s/mi", {
                w = cur.copy(toleranceSec = PresetEditor.tolerance(cur.toleranceSec, it * 5))
            })
            Stepper("Walk tolerance", "± ${cur.walkToleranceSec} s/mi", {
                w = cur.copy(walkToleranceSec = PresetEditor.tolerance(cur.walkToleranceSec, it * 5))
            })
            Text(describe(cur), style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(vertical = 12.dp))
            Spacer(Modifier.height(8.dp))
        }
        HorizontalDivider()
        val valid = PresetEditor.isValid(cur)
        Column(Modifier.padding(16.dp), verticalArrangement = Gap) {
            if (forRun != null) {
                Button(onClick = { onUseForRun(cur) }, enabled = valid, modifier = Modifier.fillMaxWidth()) { Text("Use for this run") }
                OutlinedButton(onClick = { save { onUseForRun(cur); } }, enabled = valid, modifier = Modifier.fillMaxWidth()) {
                    Text("Save to preset and use")
                }
            } else {
                Button(onClick = { save { onDone() } }, enabled = valid, modifier = Modifier.fillMaxWidth()) { Text("Save") }
                if (cur.id != 0L) {
                    Row(horizontalArrangement = Gap) {
                        OutlinedButton(onClick = {
                            scope.launch { repo.save(PresetEditor.duplicate(cur, all.map { it.name })); onDone() }
                        }, modifier = Modifier.weight(1f)) { Text("Duplicate") }
                        OutlinedButton(onClick = { confirmDelete = true }, modifier = Modifier.weight(1f)) { Text("Delete") }
                    }
                }
            }
        }
    }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Delete \"${cur.name}\"?") },
            confirmButton = { TextButton(onClick = { scope.launch { repo.delete(cur.id); onDone() } }) { Text("Delete") } },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun Section(title: String) {
    Text(title, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(top = 16.dp))
}

@Composable
private fun StepEditor(step: Step, onChange: (Step) -> Unit) {
    Stepper("Duration", formatMmSs(step.durationSec), {
        onChange(step.copy(durationSec = PresetEditor.duration(step.durationSec, it * DURATION_STEP_SEC)))
    })
    Stepper(
        "Target pace",
        step.targetPaceSec?.let { "${formatMmSs(it)} /mi" } ?: "None (no coaching)",
        { onChange(step.copy(targetPaceSec = PresetEditor.pace(step.targetPaceSec, it * PACE_STEP_SEC))) },
        trailing = {
            if (step.targetPaceSec != null) TextButton(onClick = { onChange(step.copy(targetPaceSec = null)) }) { Text("Clear") }
        },
    )
}
