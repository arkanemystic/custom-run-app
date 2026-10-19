package dev.pacer.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.pacer.core.RunSession.PaceStatus
import dev.pacer.core.formatDuration
import dev.pacer.core.formatMiles
import dev.pacer.core.formatMmSs
import dev.pacer.core.formatPace
import dev.pacer.core.formatRemaining
import dev.pacer.service.RunService
import dev.pacer.service.RunState
import dev.pacer.ui.theme.PacerAmber
import dev.pacer.ui.theme.PacerGreen
import dev.pacer.ui.theme.PacerRed
import kotlinx.coroutines.launch
import org.maplibre.android.geometry.LatLng

@Composable
fun LiveRunScreen(state: RunState.Running) {
    var tab by rememberSaveable { mutableIntStateOf(0) }
    Column(Modifier.fillMaxSize().safeDrawingPadding()) {
        PrimaryTabRow(selectedTabIndex = tab) {
            Tab(selected = tab == 0, onClick = { tab = 0 }, text = { Text("Run") })
            Tab(selected = tab == 1, onClick = { tab = 1 }, text = { Text("Map") })
        }
        Box(Modifier.weight(1f)) {
            // Only the selected tab is composed, so the map (and its tile loading) exists only while shown.
            if (tab == 0) Stats(state) else RunMap(
                track = RunService.track,
                trackSize = state.trackSize,
                position = if (state.lat != null && state.lon != null) LatLng(state.lat, state.lon) else null,
                modifier = Modifier.fillMaxSize(),
            )
        }
        Controls(state.snapshot.paused)
    }
}

@Composable
private fun Stats(state: RunState.Running) {
    val s = state.snapshot
    Column(
        Modifier.fillMaxSize().padding(horizontal = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.SpaceEvenly,
    ) {
        s.interval?.let { iv ->
            if (iv.finished) {
                Text("Workout complete", fontSize = 28.sp, color = PacerGreen)
            } else {
                Text(iv.interval!!.label, fontSize = 28.sp, fontWeight = FontWeight.SemiBold)
                Text(formatRemaining(iv.remainingMs), fontSize = 64.sp, fontWeight = FontWeight.Bold)
                iv.next?.let { Text("Next: ${it.label}", color = MaterialTheme.colorScheme.onSurfaceVariant) }
            }
        }
        Metric("Pace /mi", formatPace(s.livePaceSec), big = true)
        s.targetPaceSec?.let { target ->
            val (text, color) = when (s.paceStatus) {
                PaceStatus.TOO_SLOW -> "▲ Speed up" to PacerAmber
                PaceStatus.TOO_FAST -> "▼ Slow down" to PacerRed
                PaceStatus.IN_BAND -> "● On pace" to PacerGreen
                PaceStatus.NONE -> "" to Color.Unspecified
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Target ${formatMmSs(target)} ±${s.toleranceSec}s  ", fontSize = 20.sp)
                Text(text, fontSize = 22.sp, fontWeight = FontWeight.Bold, color = color)
            }
        }
        Row(Modifier.fillMaxWidth()) {
            Metric(if (s.lastMile.isSinceStart) "Pace since start" else "Last mile", formatPace(s.lastMile.secPerMile), Modifier.weight(1f))
            Metric("Avg pace", formatPace(s.avgPaceSec), Modifier.weight(1f))
        }
        Row(Modifier.fillMaxWidth()) {
            Metric("Miles", formatMiles(s.distanceM), Modifier.weight(1f))
            Metric("Time", formatDuration(s.movingMs), Modifier.weight(1f))
        }
        s.lastSplitMs?.let { Text("Last split ${formatDuration(it)}", color = MaterialTheme.colorScheme.onSurfaceVariant) }
        if (s.paused) Text("PAUSED", fontSize = 28.sp, color = PacerAmber, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun Controls(paused: Boolean) {
    val context = LocalContext.current
    Row(Modifier.fillMaxWidth().padding(16.dp), horizontalArrangement = Gap) {
        Button(
            onClick = { RunService.send(context, if (paused) RunService.ACTION_RESUME else RunService.ACTION_PAUSE) },
            modifier = Modifier.weight(1f).height(72.dp),
            shape = RoundedCornerShape(20.dp),
        ) { Text(if (paused) "Resume" else "Pause", fontSize = 24.sp) }
        HoldToStop(Modifier.weight(1f).height(72.dp)) { RunService.send(context, RunService.ACTION_STOP) }
    }
}

/** Stop only fires after a 1.5 s hold, so a pocket tap or brush can't end the run. */
@Composable
private fun HoldToStop(modifier: Modifier, onStop: () -> Unit) {
    val progress = remember { Animatable(0f) }
    val scope = rememberCoroutineScope()
    Box(
        modifier
            .clip(RoundedCornerShape(20.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerHighest)
            .pointerInput(Unit) {
                awaitEachGesture {
                    awaitFirstDown()
                    val job = scope.launch {
                        progress.animateTo(1f, tween(1500, easing = LinearEasing))
                        onStop()
                    }
                    do {
                        val e = awaitPointerEvent()
                    } while (e.changes.any { it.pressed })
                    if (progress.value < 1f) {
                        job.cancel()
                        scope.launch { progress.snapTo(0f) }
                    }
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        Box(Modifier.fillMaxHeight().fillMaxWidth(progress.value).align(Alignment.CenterStart).background(PacerRed))
        Text(if (progress.value > 0f) "Keep holding…" else "Hold to stop", fontSize = 20.sp)
    }
    Spacer(Modifier)
}
