package dev.pacer.wear

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.PowerManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.Color
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
import androidx.wear.compose.foundation.lazy.ScalingLazyColumn
import androidx.wear.compose.foundation.lazy.ScalingLazyListState
import androidx.wear.compose.foundation.lazy.items
import androidx.wear.compose.foundation.lazy.rememberScalingLazyListState
import androidx.wear.compose.foundation.pager.HorizontalPager
import androidx.wear.compose.foundation.pager.PagerState
import androidx.wear.compose.foundation.pager.rememberPagerState
import androidx.wear.compose.foundation.rotary.RotaryScrollableDefaults
import androidx.wear.compose.foundation.rotary.rotaryScrollable
import androidx.wear.compose.material.Chip
import androidx.wear.compose.material.ChipDefaults
import androidx.wear.compose.material.CircularProgressIndicator
import androidx.wear.compose.material.HorizontalPageIndicator
import androidx.wear.compose.material.Icon
import androidx.wear.compose.material.MaterialTheme
import androidx.wear.compose.material.PageIndicatorState
import androidx.wear.compose.material.Text
import androidx.wear.compose.material.ToggleChip
import androidx.wear.compose.material.ToggleChipDefaults
import dev.pacer.core.Fix
import dev.pacer.core.IntervalKind
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
private val Blue = Color(0xFF40C4FF)
private val Amber = Color(0xFFFFB300)
private val Red = Color(0xFFFF5252)
private val Dim = Color(0xFF9E9E9E)
private val ButtonGrey = Color(0xFF262626)

class WearActivity : ComponentActivity() {
    private var ambient by mutableStateOf(false)
    /** In ambient mode the screen only redraws on the system's once-a-minute update. */
    private var ambientState by mutableStateOf<WearRunState.Running?>(null)

    private val ambientCallback = object : AmbientLifecycleObserver.AmbientLifecycleCallback {
        override fun onEnterAmbient(ambientDetails: AmbientLifecycleObserver.AmbientDetails) {
            ambientState = WearRunService.state.value as? WearRunState.Running
            ambient = true
        }
        override fun onUpdateAmbient() {
            ambientState = WearRunService.state.value as? WearRunState.Running
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
                    val a = ambientState
                    if (ambient) {
                        if (a != null) Stats(a.plan, a.snapshot, ambient = true)
                        else Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text("Pacer", color = Dim) }
                    } else {
                        Screens()
                    }
                }
            }
        }
    }
}

@Composable
private fun Screens() {
    val state by WearRunService.state.collectAsStateWithLifecycle()
    when (val s = state) {
        is WearRunState.Running -> LiveRun(s)
        is WearRunState.Finished -> Done(s)
        WearRunState.Idle -> Home()
    }
}

/** Bezel/crown scrolling for a list: needs focus, which this requests once the list is on screen. */
@Composable
private fun Modifier.rotaryList(state: ScalingLazyListState): Modifier {
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }
    return rotaryScrollable(RotaryScrollableDefaults.behavior(state), focus)
}

// ---------------------------------------------------------------- Home

@Composable
private fun Home() {
    val context = LocalContext.current
    val store = (context.applicationContext as WearApp).store
    val cfg = remember { store.config() }
    val synced = remember { store.hasPhoneConfig() }
    var selected by remember { mutableLongStateOf(store.selectedId) }
    var hasLocation by remember {
        mutableStateOf(context.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED)
    }
    val fix = setupFix(hasLocation)

    fun plan(): Plan = cfg.workouts.firstOrNull { it.id == selected }?.let { Plan.Intervals(it) } ?: Plan.Free(cfg.freeTargetPaceSec)
    fun start() {
        WearRunService.start(context, plan())
        if (context.getSystemService(PowerManager::class.java).isPowerSaveMode) {
            android.widget.Toast.makeText(context, "Battery Saver may pause GPS", android.widget.Toast.LENGTH_LONG).show()
        }
    }
    val notif = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { start() }
    val loc = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        hasLocation = it[Manifest.permission.ACCESS_FINE_LOCATION] == true
    }

    val list = rememberScalingLazyListState(initialCenterItemIndex = 1)
    ScalingLazyColumn(
        state = list,
        modifier = Modifier.fillMaxSize().rotaryList(list),
        rotaryScrollableBehavior = null,
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        item {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text("Pacer", fontSize = 18.sp, fontWeight = FontWeight.Bold)
                Text(
                    when {
                        !hasLocation -> "Location off"
                        fix == null -> "● Finding GPS…"
                        else -> "● GPS ±${fix.accuracyM.toInt()} m"
                    },
                    color = when {
                        fix == null -> Dim
                        fix.accuracyM <= 20 -> Green
                        else -> Amber
                    },
                    fontSize = 12.sp,
                )
            }
        }
        item {
            Chip(
                onClick = {
                    when {
                        !hasLocation -> loc.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
                        context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED ->
                            notif.launch(Manifest.permission.POST_NOTIFICATIONS)
                        else -> start()
                    }
                },
                label = {
                    Text(
                        if (hasLocation) "Start" else "Allow location",
                        fontSize = 20.sp, fontWeight = FontWeight.Bold,
                        modifier = Modifier.fillMaxWidth(), textAlign = TextAlign.Center,
                    )
                },
                colors = ChipDefaults.primaryChipColors(backgroundColor = Green, contentColor = Color.Black),
                modifier = Modifier.fillMaxWidth().height(56.dp),
            )
        }
        items(cfg.workouts, key = { it.id }) { w ->
            WorkoutChip(w.name, if (w.repeats > 0) "${w.repeats} × ${formatMmSs(w.run.durationSec)}" else "", selected == w.id) {
                selected = w.id; store.selectedId = w.id
            }
        }
        item {
            WorkoutChip("Free run", cfg.freeTargetPaceSec?.let { "Target ${formatMmSs(it)}/mi" } ?: "No intervals", selected == -1L) {
                selected = -1; store.selectedId = -1
            }
        }
        item {
            Text(
                if (synced) "Edit workouts on the phone" else "Open Pacer on the phone to sync workouts",
                fontSize = 11.sp, color = Dim, textAlign = TextAlign.Center, modifier = Modifier.padding(horizontal = 12.dp),
            )
        }
    }
}

@Composable
private fun WorkoutChip(name: String, sub: String, checked: Boolean, onClick: () -> Unit) {
    ToggleChip(
        checked = checked,
        onCheckedChange = { onClick() },
        label = { Text(name, maxLines = 1) },
        secondaryLabel = if (sub.isNotEmpty()) ({ Text(sub, maxLines = 1) }) else null,
        toggleControl = { Icon(ToggleChipDefaults.radioIcon(checked), contentDescription = null) },
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

// ---------------------------------------------------------------- Live run

private const val PAGE_STATS = 0
private const val PAGE_CONTROLS = 1

/**
 * Two pages: stats and controls. Turn the bezel, swipe, or press the Back button to switch; the
 * dots at the bottom show where you are.
 */
@Composable
private fun LiveRun(r: WearRunState.Running) {
    val pager = rememberPagerState { 2 }
    val scope = rememberCoroutineScope()
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }
    BackHandler {
        scope.launch { pager.animateScrollToPage(if (pager.currentPage == PAGE_STATS) PAGE_CONTROLS else PAGE_STATS) }
    }
    Box(Modifier.fillMaxSize()) {
        HorizontalPager(
            state = pager,
            modifier = Modifier.fillMaxSize().rotaryScrollable(RotaryScrollableDefaults.snapBehavior(pager), focus),
            rotaryScrollableBehavior = null,
        ) { page ->
            if (page == PAGE_STATS) {
                Stats(r.plan, r.snapshot, ambient = false)
            } else {
                Controls(r.snapshot) { scope.launch { pager.animateScrollToPage(PAGE_STATS) } }
            }
        }
        HorizontalPageIndicator(
            pageIndicatorState = pager.indicatorState(),
            modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 6.dp),
        )
    }
}

private fun PagerState.indicatorState() = object : PageIndicatorState {
    override val pageOffset get() = currentPageOffsetFraction
    override val selectedPage get() = currentPage
    override val pageCount get() = this@indicatorState.pageCount
}

private fun kindColor(kind: IntervalKind?) = when (kind) {
    IntervalKind.RUN -> Green
    IntervalKind.WALK -> Blue
    null, IntervalKind.FREE -> Green
    else -> Dim
}

/**
 * Laid out for a round screen: the interval's progress runs around the edge, the numbers sit in
 * the middle where the circle is widest. Ambient: white on black, no ring, no fills.
 */
@Composable
private fun Stats(plan: Plan, s: RunSession.Snapshot, ambient: Boolean) {
    val iv = s.interval?.takeUnless { it.finished }
    val kind = iv?.interval?.kind
    val accent = if (ambient) Color.White else if (s.paused) Amber else kindColor(kind)
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        if (!ambient && iv != null) {
            CircularProgressIndicator(
                progress = (iv.elapsedInIntervalMs.toFloat() / iv.interval!!.durationMs).coerceIn(0f, 1f),
                modifier = Modifier.fillMaxSize().padding(3.dp),
                indicatorColor = accent,
                trackColor = Color(0xFF1C1C1C),
                strokeWidth = 6.dp,
            )
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(horizontal = 22.dp)) {
            val repeats = (plan as? Plan.Intervals)?.workout?.repeats ?: 0
            val label = when {
                s.paused -> "PAUSED"
                iv != null -> when (kind) {
                    IntervalKind.RUN -> "RUN ${iv.interval!!.rep}/$repeats"
                    IntervalKind.WALK -> "WALK ${iv.interval!!.rep}/$repeats"
                    else -> iv.interval!!.label.uppercase()
                }
                s.interval?.finished == true -> "WORKOUT DONE"
                else -> "FREE RUN"
            }
            Text(label, fontSize = 13.sp, fontWeight = FontWeight.Bold, color = accent, letterSpacing = 1.sp)
            Text(
                if (iv != null) formatRemaining(iv.remainingMs) else formatDuration(s.movingMs),
                fontSize = 26.sp, fontWeight = FontWeight.SemiBold,
            )
            Row(verticalAlignment = Alignment.Bottom) {
                Text(formatPace(s.livePaceSec), fontSize = 50.sp, fontWeight = if (ambient) FontWeight.Normal else FontWeight.Bold)
                Text(" /mi", fontSize = 12.sp, color = Dim, modifier = Modifier.padding(bottom = 10.dp))
            }
            s.targetPaceSec?.let { t ->
                val (arrow, c) = when (s.paceStatus) {
                    PaceStatus.TOO_SLOW -> "▲ FASTER" to Amber
                    PaceStatus.TOO_FAST -> "▼ SLOWER" to Red
                    PaceStatus.IN_BAND -> "● ON PACE" to Green
                    PaceStatus.NONE -> "TARGET" to Dim
                }
                Text("$arrow  ${formatMmSs(t)}", fontSize = 13.sp, fontWeight = FontWeight.Bold, color = if (ambient) Color.White else c)
            }
            Spacer(Modifier.height(4.dp))
            Row(horizontalArrangement = Arrangement.Center) {
                Text("${formatMiles(s.distanceM)} mi", fontSize = 15.sp)
                Spacer(Modifier.width(12.dp))
                Text("${formatPace(s.avgPaceSec)} avg", fontSize = 15.sp)
            }
            if (iv != null) Text(formatDuration(s.movingMs), fontSize = 12.sp, color = Dim)
        }
    }
}

@Composable
private fun Controls(s: RunSession.Snapshot, onBackToStats: () -> Unit) {
    val context = LocalContext.current
    Column(
        Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(formatDuration(s.movingMs), fontSize = 16.sp, color = Dim)
        Spacer(Modifier.height(10.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.Top) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Box(
                    Modifier.size(72.dp).clip(CircleShape).background(if (s.paused) Green else Amber)
                        .clickable {
                            WearRunService.send(context, if (s.paused) WearRunService.ACTION_RESUME else WearRunService.ACTION_PAUSE)
                            onBackToStats()
                        },
                    contentAlignment = Alignment.Center,
                ) { Text(if (s.paused) "▶" else "❚❚", fontSize = 26.sp, color = Color.Black, fontWeight = FontWeight.Bold) }
                Text(if (s.paused) "Resume" else "Pause", fontSize = 13.sp, modifier = Modifier.padding(top = 4.dp))
            }
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                HoldToEnd { WearRunService.send(context, WearRunService.ACTION_STOP) }
                Text("Hold to end", fontSize = 13.sp, modifier = Modifier.padding(top = 4.dp))
            }
        }
        Spacer(Modifier.height(10.dp))
        Text("Back button: stats", fontSize = 11.sp, color = Dim)
    }
}

/** End needs a 1.5 s hold so a sleeve or a wet wrist can't stop the run; the ring fills while held. */
@Composable
private fun HoldToEnd(onEnd: () -> Unit) {
    val progress = remember { androidx.compose.animation.core.Animatable(0f) }
    val scope = rememberCoroutineScope()
    Box(
        Modifier.size(72.dp).clip(CircleShape).background(ButtonGrey).holdGesture(
            onPress = {
                scope.launch {
                    progress.animateTo(1f, androidx.compose.animation.core.tween(1500, easing = androidx.compose.animation.core.LinearEasing))
                    onEnd()
                }
            },
            onRelease = { if (progress.value < 1f) scope.launch { progress.snapTo(0f) } },
        ),
        contentAlignment = Alignment.Center,
    ) {
        CircularProgressIndicator(progress = progress.value, indicatorColor = Red, trackColor = Color(0xFF3A3A3A), strokeWidth = 5.dp, modifier = Modifier.fillMaxSize())
        Text("■", fontSize = 24.sp, color = Red)
    }
}

// ---------------------------------------------------------------- Done

@Composable
private fun Done(f: WearRunState.Finished) {
    val a = f.archive
    BackHandler { WearRunService.acknowledgeFinished() }
    val list = rememberScalingLazyListState(initialCenterItemIndex = 1)
    ScalingLazyColumn(
        state = list,
        modifier = Modifier.fillMaxSize().rotaryList(list),
        rotaryScrollableBehavior = null,
    ) {
        item { Text("Run saved", fontSize = 14.sp, color = Green, fontWeight = FontWeight.Bold) }
        item { Text("${formatMiles(a.distanceM)} mi", fontSize = 34.sp, fontWeight = FontWeight.Bold) }
        item { Text("${formatDuration(a.movingMs)} · ${formatPace(paceSecPerMile(a.movingMs, a.distanceM))}/mi", fontSize = 15.sp) }
        a.splitsMs.forEachIndexed { i, ms -> item { Text("Mile ${i + 1}   ${formatDuration(ms)}", fontSize = 14.sp) } }
        item {
            Text(
                "Copies to the phone's History next time they connect",
                fontSize = 11.sp, color = Dim, textAlign = TextAlign.Center, modifier = Modifier.padding(horizontal = 12.dp),
            )
        }
        item {
            Chip(
                onClick = { WearRunService.acknowledgeFinished() },
                label = { Text("Done", modifier = Modifier.fillMaxWidth(), textAlign = TextAlign.Center) },
                colors = ChipDefaults.secondaryChipColors(),
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}
