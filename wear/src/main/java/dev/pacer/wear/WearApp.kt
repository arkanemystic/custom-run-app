package dev.pacer.wear

import android.app.Application
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class WearApp : Application() {
    lateinit var store: RunStore
        private set
    lateinit var sync: WearSync
        private set

    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onCreate() {
        super.onCreate()
        store = RunStore(this)
        sync = WearSync(this, store)
        appScope.launch { sync.catchUp() }
    }
}
