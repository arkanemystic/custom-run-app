package dev.pacer.data

import android.content.Context
import android.util.Log
import com.google.android.gms.tasks.Tasks
import com.google.android.gms.wearable.DataEvent
import com.google.android.gms.wearable.DataEventBuffer
import com.google.android.gms.wearable.DataMapItem
import com.google.android.gms.wearable.PutDataMapRequest
import com.google.android.gms.wearable.Wearable
import com.google.android.gms.wearable.WearableListenerService
import dev.pacer.container
import dev.pacer.core.RunArchive
import dev.pacer.core.SyncConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.tasks.await

private const val CONFIG_PATH = "/config"
private const val RUN_PREFIX = "/run/"

/**
 * Keeps the watch's copy of presets and cue settings current. A data item is only stored
 * locally and synced by Wear OS when a watch is connected; unchanged content isn't re-sent.
 */
@OptIn(FlowPreview::class)
fun publishWatchConfig(context: Context, scope: CoroutineScope) {
    val c = context.container
    val data = Wearable.getDataClient(context.applicationContext)
    scope.launch {
        combine(c.presets.presets, c.settings.settings) { presets, s ->
            SyncConfig(presets, s.voice, s.haptics, s.coaching, s.mileCues, s.coachSpacingSec, s.freeTargetPaceSec).encode()
        }.distinctUntilChanged().debounce(2_000).collect { text ->
            try {
                val req = PutDataMapRequest.create(CONFIG_PATH).apply { dataMap.putString("config", text) }.asPutDataRequest()
                data.putDataItem(req).await()
            } catch (e: Exception) {
                // No Wear OS / Galaxy Wearable app on this phone: nothing to sync to.
                Log.i("WatchSync", "Config not published: ${e.message}")
            }
        }
    }
}

/** Receives runs recorded on the watch, adds them to History, then deletes the item as the receipt. */
class PhoneDataListener : WearableListenerService() {
    override fun onDataChanged(events: DataEventBuffer) {
        val data = Wearable.getDataClient(this)
        val incoming = events.filter { it.type == DataEvent.TYPE_CHANGED && it.dataItem.uri.path?.startsWith(RUN_PREFIX) == true }
            .map { it.dataItem.uri to DataMapItem.fromDataItem(it.dataItem).dataMap.getAsset("run") }
        // Called on a background thread, so blocking here is fine.
        for ((uri, asset) in incoming) {
            try {
                if (asset != null) {
                    val bytes = Tasks.await(data.getFdForAsset(asset)).inputStream.use { it.readBytes() }
                    val archive = RunArchive.decode(bytes.decodeToString())
                    runBlocking { container.runs.importArchive(archive) }
                }
                Tasks.await(data.deleteDataItems(uri))
            } catch (e: Exception) {
                Log.w("WatchSync", "Import of $uri failed; the watch will re-send", e)
            }
        }
    }
}
