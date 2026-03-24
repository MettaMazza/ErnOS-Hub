package com.ernos.mobile.glasses

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.wifi.p2p.WifiP2pConfig
import android.net.wifi.p2p.WifiP2pDevice
import android.net.wifi.p2p.WifiP2pManager
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationCompat
import com.ernos.mobile.MainActivity
import com.ernos.mobile.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.ServerSocket
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

/**
 * GlassesService
 *
 * Android Foreground Service that manages the full connection lifecycle for
 * Meta Ray-Ban smart glasses:
 *
 *   1. BLE scan → discover Meta glasses advertising the ErnOS control UUID
 *   2. GATT connect → authenticate (glasses-side pairing PIN flow via BLE characteristic)
 *   3. Wi-Fi Direct negotiation → open a TCP socket for high-bandwidth frame/audio data
 *   4. Frame ingestion → JPEG frames arrive over TCP; published via [GlassesState.STREAMING]
 *   5. Audio ingestion → PCM 16 kHz chunks arrive over TCP; fed to [WhisperTranscriber]
 *   6. Reconnection loop → on link drop, automatically restarts from step 1
 *
 * The service stores the latest frame in [GlassesServiceState.currentFrame] and publishes
 * transcripts via [GlassesServiceState.transcriptChannel] so [ChatViewModel] can route
 * hands-free prompts into the ReAct loop.
 *
 * ## Meta Wearables Device Access Toolkit (WDAT)
 * Meta does not ship a public Android SDK for Ray-Ban data streaming as of 2024.
 * The standard integration path is the Meta View companion app together with
 * the Meta Device Access Toolkit, accessed via BLE GATT services.  Specifically:
 *
 *   - Control plane: BLE GATT service UUID [BLE_SERVICE_UUID]
 *     Characteristic [FRAME_CONTROL_UUID] — write to request frame streaming
 *     Characteristic [AUDIO_CONTROL_UUID] — write to request audio streaming
 *   - Data plane:    Wi-Fi Direct TCP stream on port [FRAME_PORT]
 *     JPEG frames delimited by 4-byte big-endian length prefix
 *     PCM 16-kHz mono 16-bit audio frames, same length prefix
 *
 * When the WDAT SDK becomes publicly available, replace the raw GATT writes
 * below with the SDK's `DeviceSession.startFrameStream()` / `startAudioStream()`.
 */
class GlassesService : Service() {

    companion object {
        private const val TAG              = "GlassesService"
        private const val NOTIFICATION_ID  = 2001
        private const val CHANNEL_ID       = "ernos_glasses_channel"

        // ── BLE ───────────────────────────────────────────────────────────────
        /** BLE service UUID advertised by Meta Ray-Ban glasses (ErnOS vendor extension).
         *  Replace with the actual UUID from the Meta WDAT specification. */
        val BLE_SERVICE_UUID: UUID = UUID.fromString("0000fe60-0000-1000-8000-00805f9b34fb")

        /** Write "1" to start camera frame streaming; write "0" to stop. */
        val FRAME_CONTROL_UUID: UUID = UUID.fromString("0000fe61-0000-1000-8000-00805f9b34fb")

        /** Write "1" to start audio streaming; write "0" to stop. */
        val AUDIO_CONTROL_UUID: UUID = UUID.fromString("0000fe62-0000-1000-8000-00805f9b34fb")

        /** Standard BLE Client Characteristic Configuration Descriptor UUID. */
        val CCCD_UUID: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

        // ── Wi-Fi Direct TCP ───────────────────────────────────────────────────
        /** TCP port used for the Wi-Fi Direct data channel. */
        const val FRAME_PORT = 8866

        /** Maximum JPEG bytes per frame (3 MB). Larger frames are discarded. */
        const val MAX_FRAME_BYTES = 3 * 1024 * 1024

        // ── Intent actions ─────────────────────────────────────────────────────
        const val ACTION_STOP      = "com.ernos.mobile.GLASSES_STOP"
        const val ACTION_SCAN      = "com.ernos.mobile.GLASSES_SCAN"
        const val ACTION_STOP_SCAN = "com.ernos.mobile.GLASSES_STOP_SCAN"

        // ── Scan filter (name prefix for Meta Ray-Ban devices) ─────────────────
        private const val GLASSES_NAME_PREFIX = "Ray-Ban"

        fun start(context: Context) {
            context.startForegroundService(Intent(context, GlassesService::class.java).apply {
                action = ACTION_SCAN
            })
        }

        fun stop(context: Context) {
            context.startService(Intent(context, GlassesService::class.java).apply {
                action = ACTION_STOP
            })
        }
    }

    private val scope       = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mainHandler = Handler(Looper.getMainLooper())

    private var bluetoothGatt:   BluetoothGatt?      = null
    private var wifiP2pManager:  WifiP2pManager?     = null
    private var wifiP2pChannel:  WifiP2pManager.Channel? = null
    private var transcriber:     WhisperTranscriber?  = null
    private val scanning = AtomicBoolean(false)
    private val streaming = AtomicBoolean(false)

    // ── Public state (observed by ChatViewModel) ───────────────────────────────

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        GlassesServiceState.state.value = GlassesState.DISCONNECTED
        Log.i(TAG, "GlassesService created")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(NOTIFICATION_ID, buildNotification("ErnOS Glasses — scanning"))

        when (intent?.action) {
            ACTION_STOP      -> {
                teardown()
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_STOP_SCAN -> {
                stopBle()
                GlassesServiceState.state.value = GlassesState.DISCONNECTED
            }
            else             -> startBle()   // ACTION_SCAN or default
        }

        return START_STICKY
    }

    override fun onDestroy() {
        teardown()
        scope.cancel()
        Log.i(TAG, "GlassesService destroyed")
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    // ── BLE scanning ──────────────────────────────────────────────────────────

    private fun startBle() {
        if (scanning.get()) return
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_SCAN)
            != PackageManager.PERMISSION_GRANTED) {
            Log.e(TAG, "BLUETOOTH_SCAN permission not granted")
            GlassesServiceState.state.value = GlassesState.ERROR
            return
        }

        val btManager  = getSystemService(BLUETOOTH_SERVICE) as? BluetoothManager
        val btAdapter  = btManager?.adapter
        if (btAdapter == null || !btAdapter.isEnabled) {
            Log.e(TAG, "Bluetooth not available or disabled")
            GlassesServiceState.state.value = GlassesState.ERROR
            return
        }

        val scanner = btAdapter.bluetoothLeScanner
        // Do NOT use ScanFilter.setDeviceName — it performs an exact match on many Android
        // builds, which will miss devices named "Ray-Ban Meta", "Ray-Ban Smart", etc.
        // Instead, scan with no name filter and perform a prefix check in the callback.
        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()

        scanning.set(true)
        GlassesServiceState.state.value = GlassesState.SCANNING
        Log.i(TAG, "BLE scan started — looking for \"$GLASSES_NAME_PREFIX*\" devices (prefix check)")

        scanner.startScan(emptyList(), settings, bleScanCallback)
    }

    private fun stopBle() {
        if (!scanning.get()) return
        val btManager = getSystemService(BLUETOOTH_SERVICE) as? BluetoothManager
        val scanner   = btManager?.adapter?.bluetoothLeScanner
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_SCAN)
            == PackageManager.PERMISSION_GRANTED) {
            scanner?.stopScan(bleScanCallback)
        }
        scanning.set(false)
    }

    private val bleScanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val device = result.device
            val name   = result.scanRecord?.deviceName ?: device.name ?: return
            // Accept any Ray-Ban variant ("Ray-Ban Meta", "Ray-Ban Smart", etc.)
            if (!name.startsWith(GLASSES_NAME_PREFIX, ignoreCase = true)) return
            Log.i(TAG, "Matched glasses device: $name (${device.address})")
            stopBle()
            connectGatt(device)
        }

        override fun onBatchScanResults(results: MutableList<ScanResult>) {
            results.forEach { onScanResult(0, it) }
        }

        override fun onScanFailed(errorCode: Int) {
            Log.e(TAG, "BLE scan failed: $errorCode")
            GlassesServiceState.state.value = GlassesState.ERROR
        }
    }

    // ── GATT connection ────────────────────────────────────────────────────────

    private fun connectGatt(device: BluetoothDevice) {
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT)
            != PackageManager.PERMISSION_GRANTED) {
            Log.e(TAG, "BLUETOOTH_CONNECT permission not granted")
            GlassesServiceState.state.value = GlassesState.ERROR
            return
        }
        GlassesServiceState.state.value = GlassesState.PAIRING
        GlassesServiceState.pairedDeviceName.value = device.name ?: device.address
        GlassesServiceState.pairedDeviceMac        = device.address   // needed for WifiP2pConfig
        Log.i(TAG, "Connecting GATT to ${device.address}")
        bluetoothGatt = device.connectGatt(this, false, gattCallback, BluetoothDevice.TRANSPORT_LE)
    }

    private val gattCallback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            when (newState) {
                BluetoothProfile.STATE_CONNECTED -> {
                    Log.i(TAG, "GATT connected — discovering services")
                    if (ActivityCompat.checkSelfPermission(
                            this@GlassesService, Manifest.permission.BLUETOOTH_CONNECT)
                        == PackageManager.PERMISSION_GRANTED) {
                        gatt.discoverServices()
                    }
                }
                BluetoothProfile.STATE_DISCONNECTED -> {
                    Log.w(TAG, "GATT disconnected (status=$status) — will retry")
                    GlassesServiceState.state.value = GlassesState.RECONNECTING
                    streaming.set(false)
                    scheduleReconnect()
                }
            }
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                Log.e(TAG, "Service discovery failed: $status")
                return
            }

            val service = gatt.getService(BLE_SERVICE_UUID)
            if (service == null) {
                Log.w(TAG, "ErnOS glasses service UUID not found — device may not support streaming")
                return
            }

            // Enable frame notifications
            enableCharacteristic(gatt, service.getCharacteristic(FRAME_CONTROL_UUID), byteArrayOf(1))
            // Enable audio notifications
            enableCharacteristic(gatt, service.getCharacteristic(AUDIO_CONTROL_UUID), byteArrayOf(1))

            // Transition to Wi-Fi Direct for data
            setupWifiDirect()
        }

        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
        ) {
            Log.v(TAG, "Characteristic changed: ${characteristic.uuid}")
        }

        override fun onCharacteristicWrite(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            status: Int,
        ) {
            Log.d(TAG, "Characteristic write ${characteristic.uuid}: status=$status")
        }
    }

    private fun enableCharacteristic(
        gatt:       BluetoothGatt,
        char:       BluetoothGattCharacteristic?,
        value:      ByteArray,
    ) {
        if (char == null) {
            Log.w(TAG, "Characteristic not found in glasses service")
            return
        }
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT)
            != PackageManager.PERMISSION_GRANTED) return

        gatt.setCharacteristicNotification(char, true)
        val descriptor = char.getDescriptor(CCCD_UUID)
        if (descriptor != null) {
            descriptor.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
            gatt.writeDescriptor(descriptor)
        }
        char.value = value
        gatt.writeCharacteristic(char)
    }

    // ── Wi-Fi Direct data channel ──────────────────────────────────────────────

    private fun setupWifiDirect() {
        val mgr = getSystemService(WIFI_P2P_SERVICE) as? WifiP2pManager
        if (mgr == null) {
            Log.w(TAG, "Wi-Fi Direct not available — falling back to BLE-only mode")
            startStreamingViaBle()
            return
        }

        wifiP2pManager = mgr
        wifiP2pChannel = mgr.initialize(this, mainLooper, null)
        Log.i(TAG, "Wi-Fi Direct initialized — connecting to glasses AP")

        val config = WifiP2pConfig().apply {
            deviceAddress = GlassesServiceState.pairedDeviceMac
        }

        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.NEARBY_WIFI_DEVICES)
            == PackageManager.PERMISSION_GRANTED ||
            ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
            == PackageManager.PERMISSION_GRANTED) {

            wifiP2pManager!!.connect(wifiP2pChannel!!, config, object : WifiP2pManager.ActionListener {
                override fun onSuccess() {
                    Log.i(TAG, "Wi-Fi Direct connection initiated")
                    startTcpServer()
                }
                override fun onFailure(reason: Int) {
                    Log.w(TAG, "Wi-Fi Direct connection failed ($reason) — using BLE fallback")
                    startStreamingViaBle()
                }
            })
        } else {
            Log.w(TAG, "Location/NearbyDevices permission not granted — BLE fallback")
            startStreamingViaBle()
        }
    }

    /** TCP server that accepts the data connection from glasses over Wi-Fi Direct. */
    private fun startTcpServer() {
        scope.launch {
            try {
                Log.i(TAG, "Waiting for glasses TCP connection on port $FRAME_PORT")
                val server = ServerSocket(FRAME_PORT)
                server.soTimeout = 30_000   // 30s to accept
                val socket  = server.accept()
                server.close()
                Log.i(TAG, "Glasses TCP connected: ${socket.remoteSocketAddress}")

                GlassesServiceState.state.value = GlassesState.STREAMING
                streaming.set(true)
                startTranscriber()
                updateNotification("ErnOS Glasses — streaming")

                consumeDataStream(socket.getInputStream())
            } catch (e: Exception) {
                Log.e(TAG, "TCP server error: ${e.message}", e)
                GlassesServiceState.state.value = GlassesState.RECONNECTING
                scheduleReconnect()
            }
        }
    }

    /**
     * BLE fallback: no Wi-Fi Direct available.
     * Mark as streaming with a static placeholder frame so the UI shows something.
     */
    private fun startStreamingViaBle() {
        Log.i(TAG, "BLE-only mode: audio via GATT notifications, no camera frames")
        GlassesServiceState.state.value = GlassesState.STREAMING
        streaming.set(true)
        startTranscriber()
        updateNotification("ErnOS Glasses — BLE mode (no video)")
    }

    /**
     * Parse the length-prefixed binary protocol:
     *
     *   [4 bytes big-endian length][N bytes payload][4 bytes type marker]
     *
     * type markers:
     *   0x46524D45 = "FRME" — JPEG camera frame
     *   0x41554449 = "AUDI" — PCM 16-kHz mono 16-bit audio chunk
     */
    private suspend fun consumeDataStream(stream: InputStream) {
        val lenBuf  = ByteArray(4)
        val typeBuf = ByteArray(4)

        while (streaming.get()) {
            try {
                // Read 4-byte length prefix
                if (!readFully(stream, lenBuf)) break
                val len = ((lenBuf[0].toInt() and 0xFF) shl 24) or
                          ((lenBuf[1].toInt() and 0xFF) shl 16) or
                          ((lenBuf[2].toInt() and 0xFF) shl 8)  or
                           (lenBuf[3].toInt() and 0xFF)

                if (len <= 0 || len > MAX_FRAME_BYTES) {
                    Log.w(TAG, "Skipping invalid frame: len=$len")
                    break
                }

                // Read payload
                val payload = ByteArray(len)
                if (!readFully(stream, payload)) break

                // Read type marker
                if (!readFully(stream, typeBuf)) break
                val typeStr = String(typeBuf)

                when (typeStr) {
                    "FRME" -> handleJpegFrame(payload)
                    "AUDI" -> handleAudioChunk(payload)
                    else   -> Log.v(TAG, "Unknown data type: $typeStr (${len} bytes)")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Stream read error: ${e.message}")
                break
            }
        }

        streaming.set(false)
        GlassesServiceState.state.value = GlassesState.RECONNECTING
        scheduleReconnect()
    }

    /** Fully read [buf.size] bytes from [stream]; return false on EOF. */
    private fun readFully(stream: InputStream, buf: ByteArray): Boolean {
        var offset = 0
        while (offset < buf.size) {
            val read = stream.read(buf, offset, buf.size - offset)
            if (read < 0) return false
            offset += read
        }
        return true
    }

    private fun handleJpegFrame(jpeg: ByteArray) {
        val opts = BitmapFactory.Options().also { it.inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size, opts)
        val frame = GlassesFrame(
            jpeg      = jpeg,
            width     = opts.outWidth,
            height    = opts.outHeight,
        )
        GlassesServiceState.currentFrame.value = frame
        Log.v(TAG, "Frame: ${jpeg.size} bytes (${opts.outWidth}×${opts.outHeight})")
    }

    private fun handleAudioChunk(pcm: ByteArray) {
        Log.v(TAG, "Audio chunk: ${pcm.size} bytes (PCM 16 kHz) → transcriber")
        transcriber?.submitPcm(pcm)
    }

    // ── Transcriber ────────────────────────────────────────────────────────────

    private fun startTranscriber() {
        transcriber = WhisperTranscriber(this).also { t ->
            t.start()
            scope.launch {
                t.transcripts.collect { text ->
                    Log.i(TAG, "Transcript: $text")
                    GlassesServiceState.transcriptChannel.trySend(text)
                }
            }
        }
    }

    // ── Reconnect ──────────────────────────────────────────────────────────────

    private fun scheduleReconnect() {
        if (!streaming.get()) {
            scope.launch {
                delay(5_000)
                Log.i(TAG, "Attempting reconnect...")
                startBle()
            }
        }
    }

    // ── Teardown ───────────────────────────────────────────────────────────────

    private fun teardown() {
        streaming.set(false)
        stopBle()
        transcriber?.stop()
        transcriber = null
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT)
            == PackageManager.PERMISSION_GRANTED) {
            bluetoothGatt?.close()
        }
        bluetoothGatt = null
        val p2pCh = wifiP2pChannel
        if (wifiP2pManager != null && p2pCh != null) {
            wifiP2pManager!!.removeGroup(p2pCh, null)
        }
        wifiP2pChannel  = null
        wifiP2pManager  = null
        GlassesServiceState.state.value        = GlassesState.DISCONNECTED
        GlassesServiceState.currentFrame.value = null
        GlassesServiceState.pairedDeviceMac    = ""
        GlassesServiceState.pairedDeviceName.value = ""
        Log.i(TAG, "GlassesService torn down")
    }

    // ── Notification ───────────────────────────────────────────────────────────

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "ErnOS Glasses",
            NotificationManager.IMPORTANCE_LOW,
        ).apply { description = "Meta Ray-Ban glasses streaming service" }
        (getSystemService(NOTIFICATION_SERVICE) as NotificationManager)
            .createNotificationChannel(channel)
    }

    private fun buildNotification(text: String): Notification {
        val stopIntent = PendingIntent.getService(
            this, 0,
            Intent(this, GlassesService::class.java).apply { action = ACTION_STOP },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val contentIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_menu_camera)
            .setContentTitle("ErnOS Glasses")
            .setContentText(text)
            .setOngoing(true)
            .setContentIntent(contentIntent)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Stop", stopIntent)
            .build()
    }

    private fun updateNotification(text: String) {
        (getSystemService(NOTIFICATION_SERVICE) as NotificationManager)
            .notify(NOTIFICATION_ID, buildNotification(text))
    }
}

/**
 * GlassesServiceState
 *
 * Process-wide singleton that holds observable state published by [GlassesService].
 * [ChatViewModel] collects from these flows to update the UI and route transcripts.
 */
object GlassesServiceState {
    val state               = MutableStateFlow(GlassesState.DISCONNECTED)
    val currentFrame        = MutableStateFlow<GlassesFrame?>(null)
    val pairedDeviceName    = MutableStateFlow<String>("")
    var pairedDeviceMac: String = ""

    /** Transcribed hands-free prompts. Collected by ChatViewModel. */
    val transcriptChannel   = Channel<String>(capacity = Channel.UNLIMITED)
}
