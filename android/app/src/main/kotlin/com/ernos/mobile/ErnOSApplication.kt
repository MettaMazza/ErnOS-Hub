package com.ernos.mobile

import android.app.Application
import android.util.Log
import com.ernos.mobile.memory.MemoryManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * ErnOSApplication
 *
 * Exposes a process-wide [MemoryManager] singleton so that both
 * [ChatViewModel] and any other component always share the same instance —
 * guaranteeing that the ONNX model is loaded exactly once and that the same
 * Tier 2 session state is used for embedding and retrieval.
 *
 * Lifecycle:
 *   - [memoryManager] is initialised eagerly on [onCreate].
 *   - Session setup (homeostasis + ONNX load) runs asynchronously on IO.
 */
class ErnOSApplication : Application() {

    companion object {
        private const val TAG = "ErnOSApplication"

        /**
         * Process-wide [MemoryManager] singleton.
         *
         * Guaranteed non-null after [onCreate] returns.  Access from anywhere:
         *   val mm = (applicationContext as ErnOSApplication).memoryManager
         */
        @Volatile
        lateinit var memoryManager: MemoryManager
            private set
    }

    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onCreate() {
        super.onCreate()
        Log.i(TAG, "ErnOSApplication started")

        // Create the singleton before the session-setup coroutine so that
        // ChatViewModel can access it immediately even if setup is still running.
        memoryManager = MemoryManager(applicationContext)

        appScope.launch {
            try {
                memoryManager.runSessionSetup()
                Log.i(TAG, "MemoryManager session setup complete (ONNX=${memoryManager.tier2.isModelLoaded})")
            } catch (e: Exception) {
                Log.e(TAG, "MemoryManager session setup failed: ${e.message}", e)
            }
        }
    }
}
