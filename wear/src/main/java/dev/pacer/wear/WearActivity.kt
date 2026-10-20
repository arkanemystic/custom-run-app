package dev.pacer.wear

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.PowerManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import androidx.wear.ambient.AmbientLifecycleObserver
import androidx.wear.compose.material.Chip
import androidx.wear.compose.material.ChipDefaults
import androidx.wear.compose.material.CircularProgressIndicator
import androidx.wear.compose.material.ListHeader
import androidx.wear.compose.material.MaterialTheme
import androidx.wear.compose.material.ScalingLazyColumn
import androidx.wear.compose.material.Text
import androidx.wear.compose.material.TimeText
import androidx.wear.compose.material.ToggleChip
import androidx.wear.compose.material.ToggleChipDefaults
import androidx.wear.compose.material.items
import dev.pacer.core.Fix
import dev.pacer.core.Plan
import dev.pacer.core.RunSession
import dev.pacer.core.RunSession.PaceStatus
import dev.pacer.core.formatDuration
import dev.pacer.core.formatMiles
import dev.pacer.core.formatMmSs
import dev.pacer.core.formatPace
import dev.pacer.core.formatRemaining
import dev.pacer.core.paceSecPerMile
import kotlinx.coroutines.launch

private val Green = Color(0xFF00E676)
private val Amber = Color(0xFFFFB300)
private val Red = Color(0xFFFF5252)

class WearActivity : ComponentActivity() {
    private var ambient by mutableStateOf(false)
    /** In ambient mode the screen only redraws on the system's once-a-minute update. */
    private var ambientSnapshot by mutableStateOf<RunSession.Snapshot?>(null)
    private var ambientTick by mutableLongStateOf(0)

    private val ambientCallback = object : AmbientLifecycleObserver.AmbientLifecycleCallback {
        override fun onEnterAmbient(ambientDetails: AmbientLifecycleObserver.AmbientDetails) {
            ambientSnapshot = (WearRunService.state.value as? WearRunState.Running)?.snapshot
            ambient = true
        }
        override fun onUpdateAmbient() {
            ambientSnapshot = (WearRunService.state.value as? WearRunState.Running)?.snapshot
            ambientTick++
        }
        override fun onExitAmbient() { ambient = false }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        lifecycle.addObserver(AmbientLifecycleObserver(this, ambientCallback))
        lifecycleScope.launch {
            if (WearRunService.state.value == WearRunState.Idle && (application as WearApp).store.readActive() != null) {
                WearRunService.recover(this@WearActivity)
            }
        }
        setContent {
            MaterialTheme {
                Box(Modifier.fillMaxSize().background(Color.Black)) {
                    if (ambient) AmbientRun(ambientSnapshot) else Screens()
                }
            }
        }
    }
}

@Composable
private fun Screens() {
    val state by WearRunService.state.collectAsStateWithLifecycle()
    when (val s = state) {
        is WearRunState.Running -> LiveRun(s.snapshot)
        is WearRunState.Finished -> Done(s)
        WearRunState.Idle -> Home()
    }
}

@Composable
private fun Home() {
    val context = LocalContext.current
    val store = (context.applicationContext as WearApp).store
    val cfg = remember { store.config() }
    var selected by remember { mutableLongStateOf(store.selectedId) }
    var hasLocation by remember {
        mutableStateOf(context.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED)
    }
    val fix = setupFix(hasLocation)

    fun plan(): Plan = cfg.workouts.firstOrNull { it.id == selected }?.let { Plan.Intervals(it) } ?: Plan.Free(cfg.freeTargetPaceSec)
    val notif = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { WearRunService.start(context, plan()) }
    val loc = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        hasLocation = it[Manifest.permission.ACCESS_FINE_LOCATION] == true
    }

    ScalingLazyColumn(Modifier.fillMaxSize()) {
        item { ListHeader { Text("Pacer") } }
        item {
            Text(
                when {
                    !hasLocation -> "Location needed"
                    fix == null -> "Location: searching…"
                    else -> "Location ±${fix.accuracyM.toInt()} m"
                },
                color = if (fix != null) Green else Color.LightGray,
                fontSize = 13.sp,
            )
        }
        item {
            Chip(
                onClick = {
                    when {
                        !hasLocation -> loc.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
                        context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED ->
                            notif.launch(Manifest.permission.POST_NOTIFICATIONS)
                        else -> {
                            val pm = context.getSystemService(PowerManager::class.java)
                            // Battery Saver on a watch can stop location with the screen off; the phone
                            // app warns in a dialog, here the label does.
                            WearRunService.start(context, plan())
                            if (pm.isPowerSaveMode) android.widget.Toast.makeText(context, "Battery Saver is on", android.widget.Toast.LENGTH_LONG).show()
                        }
                    }
                },
                label = { Text(if (hasLocation) "Start" else "Allow location", fontSize = 20.sp, fontWeight = FontWeight.Bold) },
                colors = ChipDefaults.primaryChipColors(backgroundColor = Green, contentColor = Color.Black),
                modifier = Modifier.fillMaxWidth(),
            )
        }
        items(cfg.workouts) { w ->
            WorkoutChip(w.name, describe(w.run.durationSec, w.repeats), selected == w.id) { selected = w.id; store.selectedId = w.id }
        }
        item {
            WorkoutChip("Free run", cfg.freeTargetPaceSec?.let { "Target ${formatMmSs(it)}/mi" } ?: "No intervals", selected == -1L) {
                selected = -1; store.selectedId = -1
            }
        }
        item {
            Text(
                if (store.hasPhoneConfig()) "Edit workouts in the phone app" else "Open Pacer on your phone to sync workouts",
                fontSize = 11.sp, color = Color.Gray, textAlign = TextAlign.Center, modifier = Modifier.padding(8.dp),
            )
        }
    }
}

private fun describe(runSec: Int, repeats: Int) = if (repeats > 0) "$repeats × ${formatMmSs(runSec)}" else ""

@Composable
private fun WorkoutChip(name: String, sub: String, checked: Boolean, onClick: () -> Unit) {
    ToggleChip(
        checked = checked,
        onCheckedChange = { onClick() },
        label = { Text(name, maxLines = 1) },
        secondaryLabel = { Text(sub, maxLines = 1) },
        toggleControl = { androidx.wear.compose.material.Icon(ToggleChipDefaults.radioIcon(checked), null) },
        modifier = Modifier.fillMaxWidth(),
    )
}

/** Balanced, 5 s location only while the home screen is started. */
@Composable
private fun setupFix(enabled: Boolean): Fix? {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    var fix by remember { mutableStateOf<Fix?>(null) }
    DisposableEffect(enabled, lifecycle) {
        if (!enabled) return@DisposableEffect onDispose { }
        val src = WearLocation(context)
        val obs = LifecycleEventObserver { _, e ->
            when (e) {
                Lifecycle.Event.ON_START -> src.start(GpsMode.SETUP) { fix = it }
                Lifecycle.Event.ON_STOP -> src.stop()
                else -> Unit
            }
        }
        lifecycle.addObserver(obs)
        onDispose { lifecycle.removeObserver(obs); src.stop() }
    }
    return fix
}

@Composable
private fun LiveRun(s: RunSession.Snapshot) {
    val pager = rememberPagerState { 2 }
    HorizontalPager(pager, Modifier.fillMaxSize()) { page ->
        if (page == 0) Stats(s, color = true) else Controls(s.paused)
    }
}

@Composable
private fun Stats(s: RunSession.Snapshot, color: Boolean) {
    Column(
        Modifier.fillMaxSize().padding(horizontal = 18.dp, vertical = 22.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.SpaceEvenly,
    ) {
        val iv = s.interval
        if (iv != null) {
            if (iv.finished) Text("Workout complete", fontSize = 14.sp, color = if (color) Green else Color.White)
            else Text("${iv.interval!!.label} · ${formatRemaining(iv.remainingMs)}", fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
        } else {
            Text(formatDuration(s.movingMs), fontSize = 16.sp)
        }
        Text(formatPace(s.livePaceSec), fontSize = 44.sp, fontWeight = FontWeight.Bold)
        s.targetPaceSec?.let { t ->
            val (label, c) = when (s.paceStatus) {
                PaceStatus.TOO_SLOW -> "▲ faster" to Amber
                PaceStatus.TOO_FAST -> "▼ slower" to Red
                PaceStatus.IN_BAND -> "● on pace" to Green
                PaceStatus.NONE -> "" to Color.White
            }
            Text("${formatMmSs(t)}  $label", fontSize = 14.sp, color = if (color) c else Color.White)
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
            Text("${formatMiles(s.distanceM)} mi", fontSize = 15.sp)
            Text("avg ${formatPace(s.avgPaceSec)}", fontSize = 15.sp)
        }
        if (iv != null) Text(formatDuration(s.movingMs) + if (s.paused) "  PAUSED" else "", fontSize = 13.sp, color = Color.LightGray)
        else if (s.paused) Text("PAUSED", fontSize = 13.sp, color = if (color) Amber else Color.White)
    }
}

/** Ambient: white on black, no fills, updated once a minute by the system. */
@Composable
private fun AmbientRun(s: RunSession.Snapshot?) {
    if (s == null) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text("Pacer", color = Color.Gray) }
    } else {
        Stats(s, color = false)
    }
}

@Composable
private fun Controls(paused: Boolean) {
    val context = LocalContext.current
    Column(
        Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(10.dp, Alignment.CenterVertically),
    ) {
        TimeText()
        Chip(
            onClick = { WearRunService.send(context, if (paused) WearRunService.ACTION_RESUME else WearRunService.ACTION_PAUSE) },
            label = { Text(if (paused) "Resume" else "Pause", fontSize = 18.sp) },
            colors = ChipDefaults.primaryChipColors(backgroundColor = Green, contentColor = Color.Black),
        )
        HoldToStop { WearRunService.send(context, WearRunService.ACTION_STOP) }
    }
}

/** Stop needs a 1.5 s hold, so a sleeve or a wet wrist can't end the run. */
@Composable
private fun HoldToStop(onStop: () -> Unit) {
    val progress = remember { Animatable(0f) }
    val scope = rememberCoroutineScope()
    Box(
        Modifier.size(72.dp).clip(CircleShape).background(Color(0xFF222222)).pointerInput(Unit) {
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
        CircularProgressIndicator(progress = progress.value, indicatorColor = Red, modifier = Modifier.fillMaxSize())
        Text("Hold\nstop", textAlign = TextAlign.Center, fontSize = 13.sp)
    }
}

@Composable
private fun Done(f: WearRunState.Finished) {
    val a = f.archive
    ScalingLazyColumn(Modifier.fillMaxSize()) {
        item { ListHeader { Text("Run saved") } }
        item { Text("${formatMiles(a.distanceM)} mi", fontSize = 28.sp, fontWeight = FontWeight.Bold) }
        item { Text("${formatDuration(a.movingMs)} · ${formatPace(paceSecPerMile(a.movingMs, a.distanceM))}/mi", fontSize = 15.sp) }
        if (a.splitsMs.isNotEmpty()) {
            item { Text("Splits " + a.splitsMs.joinToString(" ") { formatDuration(it) }, fontSize = 12.sp, textAlign = TextAlign.Center) }
        }
        item {
            Text(
                "Copies to the phone's History next time they connect",
                fontSize = 11.sp, color = Color.Gray, textAlign = TextAlign.Center, modifier = Modifier.padding(8.dp),
            )
        }
        item {
            Chip(onClick = { WearRunService.acknowledgeFinished() }, label = { Text("Done") }, modifier = Modifier.fillMaxWidth())
        }
    }
}
