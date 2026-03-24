package com.ernos.mobile

import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.lifecycle.lifecycleScope
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.ernos.mobile.modelhub.ModelHubScreen
import com.ernos.mobile.settings.SettingsScreen
import com.ernos.mobile.settings.SettingsViewModel
import com.ernos.mobile.theme.ErnOSTheme
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    companion object {
        private const val TAG = "MainActivity"

        // Navigation destinations
        const val ROUTE_CHAT     = "chat"
        const val ROUTE_SETTINGS = "settings"
        const val ROUTE_HUB      = "model_hub"
    }

    private val chatViewModel:     ChatViewModel     by viewModels()
    private val settingsViewModel: SettingsViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            ErnOSApp(
                chatViewModel     = chatViewModel,
                settingsViewModel = settingsViewModel,
            )
        }
    }

    /**
     * On resume:
     * 1. Re-opens the ONNX session via [MemoryManager.runSessionSetup] so that
     *    Tier 2 semantic search is functional again after [onStop] closed it.
     * 2. Restores the KV cache that was serialized at the end of the previous
     *    session via [ChatViewModel.restoreKvCache].
     *
     * Both steps are needed on every foreground transition, not just the first.
     * [MemoryManager.runSessionSetup] is idempotent: if the ONNX model is
     * already loaded it reuses the existing session.
     */
    override fun onResume() {
        super.onResume()
        lifecycleScope.launch {
            try {
                ErnOSApplication.memoryManager.runSessionSetup()
                Log.i(TAG, "Session setup complete on resume")
            } catch (e: Exception) {
                Log.w(TAG, "Session setup error on resume: ${e.message}")
            }

            try {
                val tokensRestored = chatViewModel.restoreKvCache()
                if (tokensRestored >= 0) {
                    Log.i(TAG, "KV cache restored: $tokensRestored tokens")
                }
            } catch (e: Exception) {
                Log.w(TAG, "Failed to restore KV cache: ${e.message}")
            }
        }
    }

    /**
     * On stop (app moved to background), trigger session teardown so the
     * KV-cache tag and session-end event are persisted even if the process
     * is later killed by the OS.
     */
    override fun onStop() {
        super.onStop()
        lifecycleScope.launch {
            try {
                chatViewModel.triggerSessionTeardown()
            } catch (e: Exception) {
                Log.w(TAG, "Failed to trigger session teardown: ${e.message}")
            }
        }
    }
}

// ── Root composable with nav graph ─────────────────────────────────────────────

@Composable
fun ErnOSApp(
    chatViewModel:     ChatViewModel,
    settingsViewModel: SettingsViewModel,
) {
    val themeChoice       by settingsViewModel.themeChoice
    val dynamicColor      by settingsViewModel.dynamicColor
    val customPrimaryArgb by settingsViewModel.customPrimaryColor

    ErnOSTheme(
        themeChoice       = themeChoice,
        dynamicColor      = dynamicColor,
        customPrimaryArgb = customPrimaryArgb,
    ) {
        val navController = rememberNavController()

        NavHost(
            navController = navController,
            startDestination = MainActivity.ROUTE_CHAT,
        ) {

            composable(MainActivity.ROUTE_CHAT) {
                ChatScreen(
                    vm          = chatViewModel,
                    onOpenSettings  = { navController.navigate(MainActivity.ROUTE_SETTINGS) },
                    onOpenModelHub  = { navController.navigate(MainActivity.ROUTE_HUB) },
                )
            }

            composable(MainActivity.ROUTE_SETTINGS) {
                SettingsScreen(
                    onBack         = { navController.popBackStack() },
                    onNCtxChanged  = { nCtx -> chatViewModel.reloadCurrentModel(nCtx) },
                    vm             = settingsViewModel,
                )
            }

            composable(MainActivity.ROUTE_HUB) {
                ModelHubScreen(
                    onBack       = { navController.popBackStack() },
                    onModelPath  = { path ->
                        chatViewModel.loadModel(path)
                        navController.popBackStack()
                    },
                )
            }
        }
    }
}
