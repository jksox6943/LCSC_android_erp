package com.example.lcsc_android_erp.core.printer

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.Context
import android.graphics.Bitmap
import com.example.lcsc_android_erp.R
import java.util.Locale
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class BondedPrinter(
    val name: String,
    val address: String,
)

enum class PrinterConnectionState {
    DISCONNECTED,
    CONNECTING,
    CONNECTED,
}

data class Q5PrinterState(
    val bluetoothAvailable: Boolean = false,
    val bluetoothEnabled: Boolean = false,
    val bondedPrinters: List<BondedPrinter> = emptyList(),
    val connectionState: PrinterConnectionState = PrinterConnectionState.DISCONNECTED,
    val connectionSummary: String = "",
    val connectedAddress: String? = null,
    val connectedName: String? = null,
    val deviceInfo: Q5DeviceInfo? = null,
    val isPrinting: Boolean = false,
)

class Q5PrinterManager(
    private val appContext: Context
) {
    private val bluetoothAdapter: BluetoothAdapter? by lazy {
        appContext.getSystemService(BluetoothManager::class.java)?.adapter
    }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val _state = MutableStateFlow(
        Q5PrinterState(
            bluetoothAvailable = bluetoothAdapter != null,
            bluetoothEnabled = bluetoothAdapter?.isEnabled == true,
            connectionSummary = appContext.getString(R.string.printer_status_idle)
        )
    )
    val state: StateFlow<Q5PrinterState> = _state.asStateFlow()

    @Volatile
    private var receiveBuffer = ByteArray(0)

    @Volatile
    private var client: Q5BluetoothClient? = null

    fun refreshBondedPrinters(hasBluetoothPermission: Boolean) {
        val adapter = bluetoothAdapter
        if (adapter == null) {
            _state.update {
                it.copy(
                    bluetoothAvailable = false,
                    bluetoothEnabled = false,
                    bondedPrinters = emptyList(),
                    connectionSummary = appContext.getString(R.string.printer_bluetooth_not_supported)
                )
            }
            return
        }
        val bluetoothEnabled = adapter.isEnabled
        if (!hasBluetoothPermission) {
            _state.update {
                it.copy(
                    bluetoothAvailable = true,
                    bluetoothEnabled = bluetoothEnabled,
                    bondedPrinters = emptyList()
                )
            }
            return
        }
        val bondedPrinters = readBondedPrinters(adapter)
        _state.update {
            it.copy(
                bluetoothAvailable = true,
                bluetoothEnabled = bluetoothEnabled,
                bondedPrinters = bondedPrinters
            )
        }
    }

    @SuppressLint("MissingPermission")
    fun connect(address: String, hasBluetoothPermission: Boolean) {
        val adapter = bluetoothAdapter
        if (adapter == null) {
            _state.update {
                it.copy(
                    connectionState = PrinterConnectionState.DISCONNECTED,
                    connectionSummary = appContext.getString(R.string.printer_bluetooth_not_supported)
                )
            }
            return
        }
        if (!hasBluetoothPermission) {
            _state.update {
                it.copy(
                    connectionState = PrinterConnectionState.DISCONNECTED,
                    connectionSummary = appContext.getString(R.string.printer_permission_required)
                )
            }
            return
        }
        if (!adapter.isEnabled) {
            _state.update {
                it.copy(
                    bluetoothEnabled = false,
                    connectionState = PrinterConnectionState.DISCONNECTED,
                    connectionSummary = appContext.getString(R.string.printer_bluetooth_disabled)
                )
            }
            return
        }
        val normalizedAddress = address.trim().uppercase(Locale.ROOT)
        if (normalizedAddress.isBlank()) {
            _state.update {
                it.copy(
                    connectionState = PrinterConnectionState.DISCONNECTED,
                    connectionSummary = appContext.getString(R.string.printer_connect_address_required)
                )
            }
            return
        }
        receiveBuffer = ByteArray(0)
        val targetPrinter = _state.value.bondedPrinters.firstOrNull { it.address == normalizedAddress }
        _state.update {
            it.copy(
                bluetoothAvailable = true,
                bluetoothEnabled = true,
                connectionState = PrinterConnectionState.CONNECTING,
                connectionSummary = appContext.getString(R.string.printer_status_connecting, targetPrinter?.name ?: normalizedAddress),
                connectedAddress = normalizedAddress,
                connectedName = targetPrinter?.name,
                deviceInfo = null
            )
        }
        client = Q5BluetoothClient(
            adapter = adapter,
            onStateChanged = { connectionState, message ->
                scope.launch {
                    handleConnectionStateChanged(
                        connectionState = connectionState,
                        message = message,
                        address = normalizedAddress,
                        fallbackName = targetPrinter?.name
                    )
                }
            },
            onBytesReceived = { bytes ->
                scope.launch {
                    handleIncomingBytes(bytes)
                }
            }
        ).also { it.connect(normalizedAddress) }
    }

    fun disconnect() {
        client?.disconnect()
        client = null
        _state.update {
            it.copy(
                bluetoothEnabled = bluetoothAdapter?.isEnabled == true,
                connectionState = PrinterConnectionState.DISCONNECTED,
                connectionSummary = appContext.getString(R.string.printer_status_disconnected),
                connectedAddress = null,
                connectedName = null,
                deviceInfo = null,
                isPrinting = false
            )
        }
    }

    fun printBitmap(bitmap: Bitmap, onCompleted: (String?) -> Unit) {
        val currentClient = client
        if (currentClient == null || _state.value.connectionState != PrinterConnectionState.CONNECTED) {
            onCompleted(appContext.getString(R.string.printer_not_connected))
            return
        }
        if (_state.value.isPrinting) {
            onCompleted(appContext.getString(R.string.printer_print_in_progress))
            return
        }
        _state.update { it.copy(isPrinting = true) }
        scope.launch(Dispatchers.IO) {
            val result = runCatching {
                Q5ImageEncoder.buildBitmapPrintChunks(bitmap).forEach { chunk ->
                    if (chunk.bytes.isNotEmpty()) {
                        currentClient.send(chunk.bytes)
                    }
                    if (chunk.delayAfterMs > 0) {
                        Thread.sleep(chunk.delayAfterMs)
                    }
                }
            }
            withContext(Dispatchers.Main) {
                _state.update { it.copy(isPrinting = false) }
                onCompleted(result.exceptionOrNull()?.message)
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun readBondedPrinters(adapter: BluetoothAdapter): List<BondedPrinter> {
        return adapter.bondedDevices
            .orEmpty()
            .map { device ->
                BondedPrinter(
                    name = device.name ?: appContext.getString(R.string.printer_unknown_device),
                    address = device.address
                )
            }
            .sortedWith(
                compareBy<BondedPrinter>(
                    { !it.name.startsWith("Q5", ignoreCase = true) },
                    { it.name },
                    { it.address }
                )
            )
    }

    private fun handleConnectionStateChanged(
        connectionState: PrinterConnectionState,
        message: String,
        address: String,
        fallbackName: String?
    ) {
        when (connectionState) {
            PrinterConnectionState.CONNECTING -> {
                val targetName = fallbackName ?: _state.value.connectedName ?: address
                _state.update {
                    it.copy(
                        connectionState = connectionState,
                        connectionSummary = appContext.getString(
                            R.string.printer_status_connecting,
                            targetName
                        ),
                        connectedAddress = address,
                        connectedName = targetName
                    )
                }
            }

            PrinterConnectionState.CONNECTED -> {
                val targetName = fallbackName ?: _state.value.connectedName ?: address
                _state.update {
                    it.copy(
                        bluetoothEnabled = bluetoothAdapter?.isEnabled == true,
                        connectionState = connectionState,
                        connectionSummary = appContext.getString(
                            R.string.printer_status_connected,
                            targetName
                        ),
                        connectedAddress = address,
                        connectedName = targetName
                    )
                }
                queryDeviceInfo()
            }

            PrinterConnectionState.DISCONNECTED -> {
                val summaryRes = if (message.contains("failed", ignoreCase = true)) {
                    R.string.printer_status_connect_failed
                } else {
                    R.string.printer_status_disconnected
                }
                _state.update {
                    it.copy(
                        bluetoothEnabled = bluetoothAdapter?.isEnabled == true,
                        connectionState = connectionState,
                        connectionSummary = appContext.getString(summaryRes),
                        connectedAddress = null,
                        connectedName = null,
                        deviceInfo = null,
                        isPrinting = false
                    )
                }
            }
        }
    }

    private fun handleIncomingBytes(bytes: ByteArray) {
        receiveBuffer += bytes
        val (frames, remaining) = Q5Protocol.tryExtractFrames(receiveBuffer)
        receiveBuffer = remaining
        frames.forEach { frame ->
            if (Q5Protocol.isAck(frame)) {
                return@forEach
            }
            when (frame.command) {
                0x35 -> {
                    val deviceInfo = Q5Protocol.parseDeviceInfo(frame) ?: return@forEach
                    _state.update {
                        it.copy(
                            connectedName = deviceInfo.name,
                            deviceInfo = deviceInfo,
                            connectionSummary = appContext.getString(R.string.printer_status_connected, deviceInfo.name)
                        )
                    }
                }
            }
        }
    }

    private fun queryDeviceInfo() {
        scope.launch(Dispatchers.IO) {
            runCatching {
                client?.send(Q5Protocol.queryDeviceInfo)
            }
        }
    }
}
