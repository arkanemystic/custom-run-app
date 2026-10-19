package dev.pacer.ui

import androidx.compose.runtime.Composable

// Filled in by stage 7.
@Composable
fun HistoryScreen(onOpen: (Long) -> Unit, onBack: () -> Unit) {
    androidx.compose.material3.TextButton(onClick = onBack) { androidx.compose.material3.Text("Back") }
}

@Composable
fun SummaryScreen(runId: Long, onDone: () -> Unit) {
    androidx.compose.material3.TextButton(onClick = onDone) { androidx.compose.material3.Text("Run $runId saved. Done") }
}
