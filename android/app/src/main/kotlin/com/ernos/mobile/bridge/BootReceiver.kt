package com.ernos.mobile.bridge

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.datastore.preferences.core.stringPreferencesKey
import com.ernos.mobile.memory.Tier5Scratchpad
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * BootReceiver
 *
 * Listens for [Intent.ACTION_BOOT_COMPLETED] and conditionally re-starts
 * [BridgeService] if the user had bridges configured in Tier 5 Scratchpad.
 */
class BootReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "BootReceiver"

        val KEY_DISCORD_TOKEN       = stringPreferencesKey("bridge_discord_token")
        val KEY_TELEGRAM_TOKEN      = stringPreferencesKey("bridge_telegram_token")
        val KEY_WA_PHONE_ID         = stringPreferencesKey("bridge_wa_phone_id")
        val KEY_WA_ACCESS_TOKEN     = stringPreferencesKey("bridge_wa_access_token")
        val KEY_WA_VERIFY_TOKEN     = stringPreferencesKey("bridge_wa_verify_token")
        /** App secret from Meta Developer Console — used for HMAC signature verification. */
        val KEY_WA_APP_SECRET       = stringPreferencesKey("bridge_wa_app_secret")
        val KEY_CUSTOM_RESPONSE_URL = stringPreferencesKey("bridge_custom_response_url")
        val KEY_CUSTOM_SECRET       = stringPreferencesKey("bridge_custom_secret")
        val KEY_OFFLOAD_HOST        = stringPreferencesKey("bridge_offload_host")
        val KEY_OFFLOAD_SECRET      = stringPreferencesKey("bridge_offload_secret")
        val KEY_BRIDGE_AUTO_START   = stringPreferencesKey("bridge_auto_start")
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        Log.i(TAG, "Boot completed — checking bridge auto-start setting")

        val scratchpad = Tier5Scratchpad(context)

        scope.launch {
            try {
                val autoStart = scratchpad.getOrDefault(KEY_BRIDGE_AUTO_START, "false")
                if (autoStart != "true") {
                    Log.i(TAG, "Bridge auto-start disabled — not restarting BridgeService")
                    return@launch
                }

                val discordToken      = scratchpad.get(KEY_DISCORD_TOKEN) ?: ""
                val telegramToken     = scratchpad.get(KEY_TELEGRAM_TOKEN) ?: ""
                val waPhoneId         = scratchpad.get(KEY_WA_PHONE_ID) ?: ""
                val waAccessToken     = scratchpad.get(KEY_WA_ACCESS_TOKEN) ?: ""
                val waVerifyToken     = scratchpad.get(KEY_WA_VERIFY_TOKEN) ?: ""
                val waAppSecret       = scratchpad.get(KEY_WA_APP_SECRET) ?: ""
                val customResponseUrl = scratchpad.get(KEY_CUSTOM_RESPONSE_URL) ?: ""
                val customSecret      = scratchpad.get(KEY_CUSTOM_SECRET) ?: ""
                val offloadHost       = scratchpad.get(KEY_OFFLOAD_HOST) ?: ""
                val offloadSecret     = scratchpad.get(KEY_OFFLOAD_SECRET) ?: ""

                val serviceIntent = Intent(context, BridgeService::class.java).apply {
                    if (discordToken.isNotBlank())
                        putExtra(BridgeService.EXTRA_DISCORD_TOKEN,       discordToken)
                    if (telegramToken.isNotBlank())
                        putExtra(BridgeService.EXTRA_TELEGRAM_TOKEN,      telegramToken)
                    if (waPhoneId.isNotBlank())
                        putExtra(BridgeService.EXTRA_WA_PHONE_ID,         waPhoneId)
                    if (waAccessToken.isNotBlank())
                        putExtra(BridgeService.EXTRA_WA_ACCESS_TOKEN,     waAccessToken)
                    if (waVerifyToken.isNotBlank())
                        putExtra(BridgeService.EXTRA_WA_VERIFY_TOKEN,     waVerifyToken)
                    if (waAppSecret.isNotBlank())
                        putExtra(BridgeService.EXTRA_WA_APP_SECRET,       waAppSecret)
                    if (customResponseUrl.isNotBlank())
                        putExtra(BridgeService.EXTRA_CUSTOM_RESPONSE_URL, customResponseUrl)
                    if (customSecret.isNotBlank())
                        putExtra(BridgeService.EXTRA_CUSTOM_SECRET,       customSecret)
                    if (offloadHost.isNotBlank())
                        putExtra(BridgeService.EXTRA_OFFLOAD_HOST,        offloadHost)
                    if (offloadSecret.isNotBlank())
                        putExtra(BridgeService.EXTRA_OFFLOAD_SECRET,      offloadSecret)
                }

                Log.i(TAG, "Auto-starting BridgeService after boot")
                context.startForegroundService(serviceIntent)
            } catch (e: Exception) {
                Log.e(TAG, "BootReceiver error: ${e.message}", e)
            }
        }
    }
}
