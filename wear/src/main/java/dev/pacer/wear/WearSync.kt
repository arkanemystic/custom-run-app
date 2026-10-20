package dev.pacer.wear

import android.content.Context
import android.net.Uri
import android.util.Log
import com.google.android.gms.wearable.Asset
import com.google.android.gms.wearable.DataEvent
import com.google.android.gms.wearable.DataEventBuffer
import com.google.android.gms.wearable.DataMapItem
import com.google.android.gms.wearable.PutDataMapRequest
import com.google.android.gms.wearable.Wearable
import com.google.android.gms.wearable.WearableListenerService
import kotlinx.coroutines.tasks.await
import java.io.File

/** Paths shared with the phone app's PhoneDataListener. */
object SyncPaths {
    const val CONFIG = "/config"
    const val RUN_PREFIX = "/run/"
}

/**
 * Hands finished runs to the Wear Data Layer. Data items are delivered whenever the phone is next
 * reachable (Bluetooth or Wi-Fi), so nothing here waits on a connection or polls.
 */
class WearSync(context: Context, private val store: RunStore) {
    private val data = Wearable.getDataClient(context.applicationContext)

    suspend fun send(file: File) {
        try {
            val req = PutDataMapRequest.create(SyncPaths.RUN_PREFIX + file.nameWithoutExtension).apply {
                dataMap.putAsset("run", Asset.createFromBytes(file.readBytes()))
            }.asPutDataRequest().setUrgent()
            data.putDataItem(req).await()
        } catch (e: Exception) {
            Log.w("WearSync", "Queueing run failed; will retry on next launch", e)
        }
    }

    /** Re-queues anything the phone hasn't confirmed, and picks up presets sent while we weren't listening. */
    suspend fun catchUp() {
        store.outboxFiles().forEach { send(it) }
        try {
            val items = data.getDataItems(Uri.Builder().scheme("wear").path(SyncPaths.CONFIG).build()).await()
            items.use { buf -> buf.firstOrNull()?.let { DataMapItem.fromDataItem(it).dataMap.getString("config") } }
                ?.let(store::saveConfig)
        } catch (e: Exception) {
            Log.w("WearSync", "Config fetch failed", e)
        }
    }
}

/** Receives presets/settings from the phone, and the phone's "got it" (deletion) for each run. */
class WearDataListener : WearableListenerService() {
    override fun onDataChanged(events: DataEventBuffer) {
        val store = (application as WearApp).store
        for (e in events) {
            val path = e.dataItem.uri.path ?: continue
            when {
                path == SyncPaths.CONFIG && e.type == DataEvent.TYPE_CHANGED ->
                    DataMapItem.fromDataItem(e.dataItem).dataMap.getString("config")?.let { runCatching { store.saveConfig(it) } }
                path.startsWith(SyncPaths.RUN_PREFIX) && e.type == DataEvent.TYPE_DELETED ->
                    store.removeFromOutbox(path.removePrefix(SyncPaths.RUN_PREFIX))
            }
        }
    }
}
