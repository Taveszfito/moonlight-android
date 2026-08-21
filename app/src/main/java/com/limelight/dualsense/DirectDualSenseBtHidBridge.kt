// SPDX-License-Identifier: LicenseRef-AGPL-3.0-only-OpenSSL

package com.limelight.dualsense

import android.annotation.SuppressLint
import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothProfile
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.InputDevice
import androidx.annotation.RequiresApi
import org.lsposed.hiddenapibypass.HiddenApiBypass
import java.lang.reflect.Method
import java.io.Closeable
import java.util.Locale
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

@RequiresApi(Build.VERSION_CODES.P)
class DirectDualSenseBtHidBridge(context: Context)
{
    companion object
    {
        private const val TAG = "DirectDualSenseBtHidBridge"
        private const val LOG_LIMIT = 400
        private const val REPORT_TYPE_INPUT_FALLBACK: Byte = 1
        private const val REPORT_TYPE_OUTPUT_FALLBACK: Byte = 2
        private const val REPORT_TYPE_FEATURE_FALLBACK: Byte = 3
        private const val HID_HOST_PROFILE = 4
        private const val ACTION_HANDSHAKE_FALLBACK = "android.bluetooth.input.profile.action.HANDSHAKE"
        private const val ACTION_REPORT_FALLBACK = "android.bluetooth.input.profile.action.REPORT"
        private const val EXTRA_REPORT_FALLBACK = "android.bluetooth.BluetoothHidHost.extra.REPORT"
        private const val EXTRA_REPORT_ID_FALLBACK = "android.bluetooth.BluetoothHidHost.extra.REPORT_ID"
        private const val EXTRA_REPORT_TYPE_FALLBACK = "android.bluetooth.BluetoothHidHost.extra.REPORT_TYPE"
        private const val EXTRA_STATUS_FALLBACK = "android.bluetooth.BluetoothHidHost.extra.STATUS"
        private const val EXTRA_REPORT_LEGACY = "android.bluetooth.BluetoothInputDevice.extra.REPORT"
        private const val EXTRA_REPORT_ID_LEGACY = "android.bluetooth.BluetoothInputDevice.extra.REPORT_ID"
        private const val EXTRA_REPORT_TYPE_LEGACY = "android.bluetooth.BluetoothInputDevice.extra.REPORT_TYPE"
        private val HEX_DIGITS = charArrayOf('0', '1', '2', '3', '4', '5', '6', '7', '8', '9', 'A', 'B', 'C', 'D', 'E', 'F')
        private val INPUT_DEVICE_BLUETOOTH_ADDRESS_REGEX = Regex("bluetoothAddress=([0-9A-Fa-f:]{17})")
    }

    enum class OutputTransportMode(val transportName: String)
    {
        AUTO("auto"),
        SEND_DATA_RAW("sendData(raw)"),
        SEND_DATA_HEX("sendData"),
        SET_REPORT_RAW("setReport(raw)"),
        SET_REPORT_HEX("setReport");

        fun isAuto(): Boolean = this == AUTO
    }

    data class Status(
        val hiddenApiReady: Boolean,
        val proxyReady: Boolean,
        val permissionGranted: Boolean,
        val connectedDeviceName: String?,
        val connectedDeviceCount: Int,
        val setReportReady: Boolean,
        val sendDataReady: Boolean,
        val getReportReady: Boolean,
        val featureReportReady: Boolean,
        val lastSendStatus: String?,
        val lastError: String?,
        val logText: String,
    )

    data class TransportDiagnostics(
        val selectedDeviceName: String?,
        val selectedDeviceAddress: String?,
        val connectedDeviceCount: Int,
        val cachedTransportMode: String?,
        val sendDataRawReady: Boolean,
        val sendDataHexReady: Boolean,
        val setReportRawReady: Boolean,
        val setReportHexReady: Boolean,
        val getReportReady: Boolean,
        val sendDataRawSignature: String?,
        val sendDataHexSignature: String?,
        val setReportRawSignature: String?,
        val setReportHexSignature: String?,
        val getReportSignature: String?,
    )

    data class SendResult(
        val success: Boolean,
        val transport: String,
        val message: String,
    )

    data class ReportResult(
        val success: Boolean,
        val reportId: Int,
        val reportType: Int,
        val report: ByteArray?,
        val message: String,
    )

    private data class AttemptResult(
        val mode: OutputTransportMode,
        val transport: String,
        val success: Boolean,
        val message: String,
    )

    private data class PendingReport(
        val reportType: Int,
        val reportId: Int,
        val latch: CountDownLatch,
        val result: AtomicReference<ByteArray?>,
    )

    private data class PendingHandshake(
        val latch: CountDownLatch,
        val status: AtomicReference<Int?>,
    )

    fun interface ReportListener
    {
        fun onReport(reportType: Int, reportId: Int, report: ByteArray)
    }

    private val appContext = context.applicationContext
    private val mainHandler = Handler(Looper.getMainLooper())
    private var hidDispatchThread: android.os.HandlerThread? = null
    private var hidHandler: Handler? = null

    private fun getHidHandler(): Handler {
        var handler = hidHandler
        if (handler == null) {
            val thread = android.os.HandlerThread("DualSenseHidDispatch").apply { start() }
            hidDispatchThread = thread
            handler = Handler(thread.looper)
            hidHandler = handler
        }
        return handler
    }
    private val logLines = ArrayDeque<String>()
    private val fullLogBuilder = StringBuilder()
    private val logStartMs = SystemClock.elapsedRealtime()
    private val hexCharBuffer = ThreadLocal<CharArray>()
    private var statusListener: (() -> Unit)? = null
    private var profileProxy: BluetoothProfile? = null
    private var proxyRequested = false
    private var connectedDevices: List<BluetoothDevice> = emptyList()
    private var selectedDevice: BluetoothDevice? = null
    private var hiddenApiReady = false
    private var hidHostClass: Class<*>? = null
    private var setReportMethod: Method? = null
    private var sendDataMethod: Method? = null
    private var setReportBytesMethod: Method? = null
    private var sendDataBytesMethod: Method? = null
    private var getReportMethod: Method? = null
    private var reportTypeInput: Byte = REPORT_TYPE_INPUT_FALLBACK
    private var reportTypeOutput: Byte = REPORT_TYPE_OUTPUT_FALLBACK
    private var reportTypeFeature: Byte = REPORT_TYPE_FEATURE_FALLBACK
    private var actionHandshake: String = ACTION_HANDSHAKE_FALLBACK
    private var actionReport: String = ACTION_REPORT_FALLBACK
    private var extraReport: String = EXTRA_REPORT_FALLBACK
    private var extraReportId: String = EXTRA_REPORT_ID_FALLBACK
    private var extraReportType: String = EXTRA_REPORT_TYPE_FALLBACK
    private var extraStatus: String = EXTRA_STATUS_FALLBACK
    private var reportReceiverRegistered = false
    private var cachedTransportMode: OutputTransportMode? = null
    private var lastSendStatus: String? = null
    private var lastError: String? = null
    @Volatile private var isClosed = false
    private val pendingReports = ArrayList<PendingReport>()
    private val pendingHandshakes = ArrayList<PendingHandshake>()
    private val reportListeners = ArrayList<ReportListener>()

    private val bluetoothAdapter: BluetoothAdapter? = BluetoothAdapter.getDefaultAdapter()

    private val reportReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?)
        {
            if(intent == null)
                return
            if(intent.action == actionHandshake)
            {
                val status = intent.hiddenIntExtra(extraStatus, -1)
                    .takeIf { it >= 0 }
                    ?: intent.hiddenIntExtra(EXTRA_STATUS_FALLBACK, -1)
                handleIncomingHandshake(status)
                return
            }
            if(intent.action != actionReport)
                return
            val reportBytes = intent.getByteArrayExtra(extraReport)
                ?: intent.getByteArrayExtra(EXTRA_REPORT_FALLBACK)
                ?: intent.getByteArrayExtra(EXTRA_REPORT_LEGACY)
                ?: return
            val reportId = intent.hiddenIntExtra(extraReportId, -1)
                .takeIf { it >= 0 }
                ?: intent.hiddenIntExtra(EXTRA_REPORT_ID_FALLBACK, -1).takeIf { it >= 0 }
                ?: intent.hiddenIntExtra(EXTRA_REPORT_ID_LEGACY, -1)
            val reportType = intent.hiddenIntExtra(extraReportType, -1)
                .takeIf { it >= 0 }
                ?: intent.hiddenIntExtra(EXTRA_REPORT_TYPE_FALLBACK, -1).takeIf { it >= 0 }
                ?: intent.hiddenIntExtra(EXTRA_REPORT_TYPE_LEGACY, -1)
            handleIncomingReport(reportType, reportId, reportBytes)
        }
    }

    private val serviceListener = object : BluetoothProfile.ServiceListener {
        override fun onServiceConnected(profile: Int, proxy: BluetoothProfile)
        {
            if(profile != HID_HOST_PROFILE)
                return
            profileProxy = proxy
            proxyRequested = false
            appendLog("HID_HOST proxy ready: ${proxy.javaClass.name}")
            resolveHiddenMembers(proxy)
            refreshConnectedDevices()
            notifyStatusChanged()
        }

        override fun onServiceDisconnected(profile: Int)
        {
            if(profile != HID_HOST_PROFILE)
                return
            appendLog("HID_HOST proxy disconnected")
            resetProxyState()
            notifyStatusChanged()
        }
    }

    fun setStatusListener(listener: (() -> Unit)?)
    {
        statusListener = listener
    }

    fun start()
    {
        refresh()
    }

    fun close()
    {
        isClosed = true
        unregisterReportReceiver()
        val proxy = profileProxy
        if(proxy != null)
            bluetoothAdapter?.closeProfileProxy(HID_HOST_PROFILE, proxy)
        resetProxyState()
        hidDispatchThread?.quitSafely()
        hidDispatchThread = null
        hidHandler = null
    }

    fun appendDebugLog(message: String, notify: Boolean = true)
    {
        appendLog(message)
        if(notify)
            notifyStatusChanged()
    }

    fun getStatus(permissionGranted: Boolean): Status
    {
        val previewLog = snapshotPreviewLog()
        return Status(
            hiddenApiReady = hiddenApiReady,
            proxyReady = profileProxy != null,
            permissionGranted = permissionGranted,
            connectedDeviceName = selectedDevice?.displayName(),
            connectedDeviceCount = connectedDevices.size,
            setReportReady = setReportMethod != null || setReportBytesMethod != null,
            sendDataReady = sendDataMethod != null || sendDataBytesMethod != null,
            getReportReady = getReportMethod != null,
            featureReportReady = getReportMethod != null && (setReportMethod != null || setReportBytesMethod != null),
            lastSendStatus = lastSendStatus,
            lastError = lastError,
            logText = previewLog,
        )
    }

    fun getFullLogText(): String = snapshotFullLog()

    fun getSelectedDevice(): BluetoothDevice? = selectedDevice

    fun addReportListener(listener: ReportListener): Closeable
    {
        registerReportReceiver()
        synchronized(reportListeners) {
            reportListeners += listener
        }
        return Closeable {
            synchronized(reportListeners) {
                reportListeners.remove(listener)
            }
        }
    }

    @SuppressLint("MissingPermission")
    fun getTransportDiagnostics(): TransportDiagnostics
    {
        val device = selectedDevice
        return TransportDiagnostics(
            selectedDeviceName = device?.displayName(),
            selectedDeviceAddress = runCatching { device?.address }.getOrNull(),
            connectedDeviceCount = connectedDevices.size,
            cachedTransportMode = cachedTransportMode?.transportName,
            sendDataRawReady = sendDataBytesMethod != null,
            sendDataHexReady = sendDataMethod != null,
            setReportRawReady = setReportBytesMethod != null,
            setReportHexReady = setReportMethod != null,
            getReportReady = getReportMethod != null,
            sendDataRawSignature = sendDataBytesMethod?.let { methodSignature(it) },
            sendDataHexSignature = sendDataMethod?.let { methodSignature(it) },
            setReportRawSignature = setReportBytesMethod?.let { methodSignature(it) },
            setReportHexSignature = setReportMethod?.let { methodSignature(it) },
            getReportSignature = getReportMethod?.let { methodSignature(it) },
        )
    }

    fun requestFeatureReport(reportId: Int, bufferSize: Int = 63, timeoutMs: Long = 900L): ReportResult
    {
        return requestReport(reportTypeFeature, reportId, bufferSize, timeoutMs)
    }

    fun requestInputReport(reportId: Int = 0x31, bufferSize: Int = 77, timeoutMs: Long = 500L): ReportResult
    {
        return requestReport(reportTypeInput, reportId, bufferSize, timeoutMs)
    }

    @SuppressLint("MissingPermission")
    fun sendFeatureReport(reportBytes: ByteArray): SendResult
    {
        if(isClosed) return failedResult("Bridge is closed")
        refreshConnectedDevices(logChanges = false)
        val proxy = profileProxy ?: return failedResult("HID host proxy is not ready")
        val device = selectedDevice ?: return failedResult("No connected DualSense-like HID device found")
        val reportId = reportBytes.firstOrNull()?.toInt()?.and(0xFF) ?: -1
        registerReportReceiver()
        appendLog("Prepared ${reportBytes.size} feature bytes for report 0x${"%02X".format(reportId)}: ${reportBytes.previewHex(12)}")

        setReportMethod?.let { method ->
            sendFeatureReportAttempt("setReport(feature)") {
                method.invoke(proxy, device, reportTypeArgument(method, reportTypeFeature), reportBytes.toAsciiHex()) as? Boolean ?: false
            }?.let { return it }
        }

        setReportBytesMethod?.let { method ->
            sendFeatureReportAttempt("setReport(feature raw)") {
                method.invoke(proxy, device, reportTypeArgument(method, reportTypeFeature), reportBytes) as? Boolean ?: false
            }?.let { return it }
        }

        return failedResult(lastError ?: "No working HID feature setReport method was available")
    }

    private fun sendFeatureReportAttempt(name: String, action: () -> Boolean): SendResult?
    {
        val pending = PendingHandshake(CountDownLatch(1), AtomicReference(null))
        synchronized(pendingHandshakes) {
            pendingHandshakes += pending
        }
        val attempt = runTransportAttempt(name) { action() }
        if(!attempt.success)
        {
            synchronized(pendingHandshakes) {
                pendingHandshakes.remove(pending)
            }
            return null
        }

        val observedHandshake = pending.latch.await(120L, TimeUnit.MILLISECONDS)
        synchronized(pendingHandshakes) {
            pendingHandshakes.remove(pending)
        }
        val status = pending.status.get()
        if(observedHandshake && status != null)
        {
            appendLog("$name handshake status=$status")
            if(status != 0)
            {
                lastError = "$name rejected by HID handshake status=$status"
                appendLog(lastError!!)
                return null
            }
        }
        else
        {
            appendLog("$name accepted; no HID handshake observed")
        }
        return SendResult(true, attempt.transport, attempt.message)
    }

    fun hasTransportMode(mode: OutputTransportMode): Boolean
    {
        return when(mode)
        {
            OutputTransportMode.AUTO -> true
            OutputTransportMode.SEND_DATA_RAW -> sendDataBytesMethod != null
            OutputTransportMode.SEND_DATA_HEX -> sendDataMethod != null
            OutputTransportMode.SET_REPORT_RAW -> setReportBytesMethod != null
            OutputTransportMode.SET_REPORT_HEX -> setReportMethod != null
        }
    }

    fun resolveBestAvailableTransport(vararg preferredModes: OutputTransportMode): OutputTransportMode
    {
        cachedTransportMode?.takeIf { preferredModes.contains(it) && hasTransportMode(it) }?.let { return it }
        preferredModes.firstOrNull { hasTransportMode(it) }?.let { return it }
        return OutputTransportMode.AUTO
    }

    @SuppressLint("MissingPermission")
    fun refresh()
    {
        if(Build.VERSION.SDK_INT < Build.VERSION_CODES.P)
        {
            lastError = "Android 9 or newer is required for this experimental path"
            appendLog(lastError!!)
            notifyStatusChanged()
            return
        }

        val adapter = bluetoothAdapter
        if(adapter == null)
        {
            lastError = "Bluetooth adapter unavailable"
            appendLog(lastError!!)
            notifyStatusChanged()
            return
        }

        hiddenApiReady = initHiddenApiBypass()
        if(profileProxy == null)
        {
            if(proxyRequested)
            {
                appendLog("Still waiting for HID_HOST proxy")
                notifyStatusChanged()
                return
            }
            proxyRequested = true
            val ok = runCatching {
                adapter.getProfileProxy(appContext, serviceListener, HID_HOST_PROFILE)
            }.getOrElse { throwable ->
                proxyRequested = false
                lastError = throwable.rootMessage()
                appendLog("getProfileProxy(HID_HOST) failed: ${lastError}")
                false
            }
            if(ok)
            {
                appendLog("Requested HID_HOST proxy")
            }
            else if(lastError == null)
            {
                proxyRequested = false
                lastError = "getProfileProxy(HID_HOST) returned false"
                appendLog(lastError!!)
            }
            notifyStatusChanged()
            return
        }

        resolveHiddenMembers(profileProxy!!)
        refreshConnectedDevices()
        notifyStatusChanged()
    }

    @SuppressLint("MissingPermission")
    private fun requestReport(reportType: Byte, reportId: Int, bufferSize: Int, timeoutMs: Long): ReportResult
    {
        if(isClosed)
            return ReportResult(false, reportId, reportType.toInt() and 0xFF, null, "Bridge is closed")
        refreshConnectedDevices(logChanges = false)
        val proxy = profileProxy ?: return ReportResult(false, reportId, reportType.toInt() and 0xFF, null, "HID host proxy is not ready")
        val device = selectedDevice ?: return ReportResult(false, reportId, reportType.toInt() and 0xFF, null, "No connected DualSense-like HID device found")
        val method = getReportMethod ?: return ReportResult(false, reportId, reportType.toInt() and 0xFF, null, "BluetoothHidHost.getReport unavailable")
        registerReportReceiver()

        val pending = PendingReport(reportType.toInt() and 0xFF, reportId and 0xFF, CountDownLatch(1), AtomicReference(null))
        synchronized(pendingReports) {
            pendingReports += pending
        }

        val accepted = runCatching {
            method.invoke(proxy, device, reportTypeArgument(method, reportType), reportIdArgument(method, reportId), bufferSize) as? Boolean ?: false
        }.getOrElse { throwable ->
            synchronized(pendingReports) {
                pendingReports.remove(pending)
            }
            lastError = throwable.rootMessage()
            appendLog("getReport(0x${"%02X".format(reportId)}) failed: $lastError")
            return ReportResult(false, reportId, reportType.toInt() and 0xFF, null, lastError ?: "getReport failed")
        }

        if(!accepted)
        {
            synchronized(pendingReports) {
                pendingReports.remove(pending)
            }
            appendLog("getReport(0x${"%02X".format(reportId)}) returned false")
            return ReportResult(false, reportId, reportType.toInt() and 0xFF, null, "getReport returned false")
        }

        val delivered = pending.latch.await(timeoutMs, TimeUnit.MILLISECONDS)
        synchronized(pendingReports) {
            pendingReports.remove(pending)
        }
        val report = pending.result.get()
        return if(delivered && report != null)
        {
            ReportResult(true, reportId, reportType.toInt() and 0xFF, report, "received ${report.size} bytes")
        }
        else
        {
            appendLog("Timed out waiting for report 0x${"%02X".format(reportId)}")
            ReportResult(false, reportId, reportType.toInt() and 0xFF, null, "Timed out waiting for report")
        }
    }

    @SuppressLint("MissingPermission")
    fun sendOutputReport(
        reportBytes: ByteArray,
        streaming: Boolean = false,
        preferInterrupt: Boolean? = null,
        transportMode: OutputTransportMode = OutputTransportMode.AUTO,
    ): SendResult
    {
        if(isClosed) return failedResult("Bridge is closed", notify = false)
        if(!streaming || selectedDevice == null)
            refreshConnectedDevices(logChanges = !streaming)
        val proxy = profileProxy ?: return failedResult("HID host proxy is not ready", notify = !streaming)
        val device = selectedDevice ?: return failedResult("No connected DualSense-like HID device found", notify = !streaming)
        val reportId = reportBytes.firstOrNull()?.toInt()?.and(0xFF) ?: -1

        if(!streaming)
            appendLog("Prepared ${reportBytes.size} report bytes for report 0x${"%02X".format(reportId)}: ${reportBytes.previewHex(12)}")

        var payload: String? = null
        val payloadProvider = {
            payload ?: reportBytes.toAsciiHex().also { payload = it }
        }

        val attempts = mutableListOf<AttemptResult>()
        val orderedAttempts = resolveOrderedAttempts(
            proxy = proxy,
            device = device,
            reportBytes = reportBytes,
            payloadProvider = payloadProvider,
            streaming = streaming,
            reportId = reportId,
            preferInterrupt = preferInterrupt,
            transportMode = transportMode,
        )

        for(resolveAttempt in orderedAttempts)
        {
            val attempt = resolveAttempt()
            if(attempt != null)
            {
                attempts += attempt
                successfulResult(device, attempt, streaming)?.let { return it }
            }
        }

        if(attempts.isEmpty())
            return failedResult("No working HID output method was available", notify = !streaming)

        if(!streaming)
            notifyStatusChanged()
        return failedResult(lastError ?: attempts.last().message, notify = !streaming)
    }

    @SuppressLint("MissingPermission")
    fun sendOutputReportFast(
        reportBytes: ByteArray,
        transportMode: OutputTransportMode,
    ): Boolean
    {
        if(isClosed)
            return false

        val mode = if(transportMode.isAuto()) {
            cachedTransportMode?.takeIf { hasTransportMode(it) } ?: resolveBestAvailableTransport(
                OutputTransportMode.SEND_DATA_RAW,
                OutputTransportMode.SET_REPORT_RAW,
                OutputTransportMode.SEND_DATA_HEX,
                OutputTransportMode.SET_REPORT_HEX,
            )
        } else {
            transportMode
        }

        val proxy = profileProxy ?: return false
        val device = selectedDevice ?: run {
            refreshConnectedDevices(logChanges = false)
            selectedDevice
        } ?: return false

        return runFastTransportAttempt(proxy, device, reportBytes, mode)
    }

    private fun resolveOrderedAttempts(
        proxy: BluetoothProfile,
        device: BluetoothDevice,
        reportBytes: ByteArray,
        payloadProvider: () -> String,
        streaming: Boolean,
        reportId: Int,
        preferInterrupt: Boolean?,
        transportMode: OutputTransportMode,
    ): List<() -> AttemptResult?>
    {
        if(!transportMode.isAuto())
            return listOfNotNull(buildAttempt(proxy, device, reportBytes, payloadProvider, streaming, transportMode))

        val interruptFirst = preferInterrupt ?: (reportId != 0x31)
        val preferredOrder = if(interruptFirst)
            listOf(
                OutputTransportMode.SEND_DATA_RAW,
                OutputTransportMode.SEND_DATA_HEX,
                OutputTransportMode.SET_REPORT_RAW,
                OutputTransportMode.SET_REPORT_HEX,
            )
        else
            listOf(
                OutputTransportMode.SET_REPORT_RAW,
                OutputTransportMode.SET_REPORT_HEX,
                OutputTransportMode.SEND_DATA_RAW,
                OutputTransportMode.SEND_DATA_HEX,
            )
        val orderedModes = buildList {
            cachedTransportMode?.takeIf { hasTransportMode(it) }?.let { add(it) }
            preferredOrder.forEach { mode ->
                if(mode !in this)
                    add(mode)
            }
        }
        return orderedModes.mapNotNull { mode ->
            buildAttempt(proxy, device, reportBytes, payloadProvider, streaming, mode)
        }
    }

    private fun buildAttempt(
        proxy: BluetoothProfile,
        device: BluetoothDevice,
        reportBytes: ByteArray,
        payloadProvider: () -> String,
        streaming: Boolean,
        transportMode: OutputTransportMode,
    ): (() -> AttemptResult?)?
    {
        return when(transportMode)
        {
            OutputTransportMode.AUTO -> null
            OutputTransportMode.SEND_DATA_RAW -> sendDataBytesMethod?.let { method ->
                {
                    runTransportAttempt(transportMode.transportName, logResult = !streaming) {
                        method.invoke(proxy, device, reportBytes) as? Boolean ?: false
                    }.copy(mode = transportMode)
                }
            }
            OutputTransportMode.SEND_DATA_HEX -> sendDataMethod?.let { method ->
                {
                    runTransportAttempt(transportMode.transportName, logResult = !streaming) {
                        method.invoke(proxy, device, payloadProvider()) as? Boolean ?: false
                    }.copy(mode = transportMode)
                }
            }
            OutputTransportMode.SET_REPORT_RAW -> setReportBytesMethod?.let { method ->
                {
                    runTransportAttempt(transportMode.transportName, logResult = !streaming) {
                        method.invoke(proxy, device, reportTypeArgument(method, reportTypeOutput), reportBytes) as? Boolean ?: false
                    }.copy(mode = transportMode)
                }
            }
            OutputTransportMode.SET_REPORT_HEX -> setReportMethod?.let { method ->
                {
                    runTransportAttempt(transportMode.transportName, logResult = !streaming) {
                        method.invoke(proxy, device, reportTypeArgument(method, reportTypeOutput), payloadProvider()) as? Boolean ?: false
                    }.copy(mode = transportMode)
                }
            }
        }
    }

    private fun runFastTransportAttempt(
        proxy: BluetoothProfile,
        device: BluetoothDevice,
        reportBytes: ByteArray,
        transportMode: OutputTransportMode,
    ): Boolean
    {
        return when(transportMode)
        {
            OutputTransportMode.AUTO -> false
            OutputTransportMode.SEND_DATA_RAW -> sendDataBytesMethod?.let { method ->
                runCatching {
                    method.invoke(proxy, device, reportBytes) as? Boolean ?: false
                }.getOrElse { throwable ->
                    lastError = throwable.rootMessage()
                    false
                }
            } ?: false
            OutputTransportMode.SEND_DATA_HEX -> sendDataMethod?.let { method ->
                runCatching {
                    method.invoke(proxy, device, reportBytes.toAsciiHex()) as? Boolean ?: false
                }.getOrElse { throwable ->
                    lastError = throwable.rootMessage()
                    false
                }
            } ?: false
            OutputTransportMode.SET_REPORT_RAW -> setReportBytesMethod?.let { method ->
                runCatching {
                    method.invoke(proxy, device, reportTypeArgument(method, reportTypeOutput), reportBytes) as? Boolean ?: false
                }.getOrElse { throwable ->
                    lastError = throwable.rootMessage()
                    false
                }
            } ?: false
            OutputTransportMode.SET_REPORT_HEX -> setReportMethod?.let { method ->
                runCatching {
                    method.invoke(proxy, device, reportTypeArgument(method, reportTypeOutput), reportBytes.toAsciiHex()) as? Boolean ?: false
                }.getOrElse { throwable ->
                    lastError = throwable.rootMessage()
                    false
                }
            } ?: false
        }
    }

    @SuppressLint("MissingPermission")
    private fun refreshConnectedDevices(logChanges: Boolean = true)
    {
        val proxy = profileProxy ?: return
        val devices = runCatching { proxy.connectedDevices }
            .getOrElse { throwable ->
                lastError = throwable.rootMessage()
                if(logChanges)
                    appendLog("connectedDevices failed: ${lastError}")
                emptyList()
            }
        connectedDevices = devices
        selectedDevice = pickLikelyDualSense(devices) ?: devices.firstOrNull()
        if(logChanges)
        {
            if(devices.isNotEmpty())
                appendLog("Connected HID devices: ${devices.joinToString("; ") { it.displayName() }}")
            appendLog(
                if(selectedDevice != null)
                    "Selected HID device: ${selectedDevice!!.displayName()} (${devices.size} connected)"
                else
                    "No connected HID devices visible"
            )
        }
    }

    private fun initHiddenApiBypass(): Boolean
    {
        val success = runCatching {
            HiddenApiBypass.addHiddenApiExemptions(
                "Landroid/bluetooth/BluetoothHidHost;",
                "Landroid/bluetooth/BluetoothAdapter;",
                "Landroid/bluetooth/BluetoothDevice;",
            )
        }.getOrElse { throwable ->
            lastError = throwable.rootMessage()
            appendLog("HiddenApiBypass init failed: ${lastError}")
            false
        }

        if(success)
            appendLog("HiddenApiBypass ready")
        return success
    }

    private fun resolveHiddenMembers(proxy: BluetoothProfile)
    {
        val candidateClasses = LinkedHashSet<Class<*>>()
        candidateClasses += proxy.javaClass
        runCatching { Class.forName("android.bluetooth.BluetoothHidHost") }
            .getOrNull()
            ?.let(candidateClasses::add)
        hidHostClass = candidateClasses.lastOrNull() ?: proxy.javaClass
        appendLog("Resolving HID host members on ${candidateClasses.joinToString(" -> ") { it.name }}")

        if(setReportMethod == null)
        {
            setReportMethod = findCompatibleMethod(candidateClasses, "setReport") { parameterTypes ->
                parameterTypes.size == 3 &&
                    BluetoothDevice::class.java.isAssignableFrom(parameterTypes[0]) &&
                    isReportTypeParameter(parameterTypes[1]) &&
                    parameterTypes[2] == String::class.java
            }
            appendLog(
                if(setReportMethod != null)
                    "Found BluetoothHidHost.setReport: ${methodSignature(setReportMethod!!)}"
                else
                    "BluetoothHidHost.setReport unavailable"
            )
        }

        if(sendDataMethod == null)
        {
            sendDataMethod = findCompatibleMethod(candidateClasses, "sendData") { parameterTypes ->
                parameterTypes.size == 2 &&
                    BluetoothDevice::class.java.isAssignableFrom(parameterTypes[0]) &&
                    parameterTypes[1] == String::class.java
            }
            appendLog(
                if(sendDataMethod != null)
                    "Found BluetoothHidHost.sendData: ${methodSignature(sendDataMethod!!)}"
                else
                    "BluetoothHidHost.sendData unavailable"
            )
        }

        if(setReportBytesMethod == null)
        {
            setReportBytesMethod = findCompatibleMethod(candidateClasses, "setReport") { parameterTypes ->
                parameterTypes.size == 3 &&
                    BluetoothDevice::class.java.isAssignableFrom(parameterTypes[0]) &&
                    isReportTypeParameter(parameterTypes[1]) &&
                    parameterTypes[2] == ByteArray::class.java
            }
            appendLog(
                if(setReportBytesMethod != null)
                    "Found BluetoothHidHost.setReport(raw): ${methodSignature(setReportBytesMethod!!)}"
                else
                    "BluetoothHidHost.setReport(raw) unavailable"
            )
        }

        if(sendDataBytesMethod == null)
        {
            sendDataBytesMethod = findCompatibleMethod(candidateClasses, "sendData") { parameterTypes ->
                parameterTypes.size == 2 &&
                    BluetoothDevice::class.java.isAssignableFrom(parameterTypes[0]) &&
                    parameterTypes[1] == ByteArray::class.java
            }
            appendLog(
                if(sendDataBytesMethod != null)
                    "Found BluetoothHidHost.sendData(raw): ${methodSignature(sendDataBytesMethod!!)}"
                else
                    "BluetoothHidHost.sendData(raw) unavailable"
            )
        }

        if(getReportMethod == null)
        {
            getReportMethod = findCompatibleMethod(candidateClasses, "getReport") { parameterTypes ->
                parameterTypes.size == 4 &&
                    BluetoothDevice::class.java.isAssignableFrom(parameterTypes[0]) &&
                    isReportTypeParameter(parameterTypes[1]) &&
                    isReportTypeParameter(parameterTypes[2]) &&
                    (parameterTypes[3] == Int::class.javaPrimitiveType || parameterTypes[3] == Int::class.javaObjectType)
            }
            appendLog(
                if(getReportMethod != null)
                    "Found BluetoothHidHost.getReport: ${methodSignature(getReportMethod!!)}"
                else
                    "BluetoothHidHost.getReport unavailable"
            )
        }

        reportTypeInput = findReportType(candidateClasses, "REPORT_TYPE_INPUT", REPORT_TYPE_INPUT_FALLBACK)
        reportTypeOutput = findReportType(candidateClasses, "REPORT_TYPE_OUTPUT", REPORT_TYPE_OUTPUT_FALLBACK)
        reportTypeFeature = findReportType(candidateClasses, "REPORT_TYPE_FEATURE", REPORT_TYPE_FEATURE_FALLBACK)
        actionHandshake = findStaticString(candidateClasses, "ACTION_HANDSHAKE", ACTION_HANDSHAKE_FALLBACK)
        actionReport = findStaticString(candidateClasses, "ACTION_REPORT", ACTION_REPORT_FALLBACK)
        extraReport = findStaticString(candidateClasses, "EXTRA_REPORT", EXTRA_REPORT_FALLBACK)
        extraReportId = findStaticString(candidateClasses, "EXTRA_REPORT_ID", EXTRA_REPORT_ID_FALLBACK)
        extraReportType = findStaticString(candidateClasses, "EXTRA_REPORT_TYPE", EXTRA_REPORT_TYPE_FALLBACK)
        extraStatus = findStaticString(candidateClasses, "EXTRA_STATUS", EXTRA_STATUS_FALLBACK)
        appendLog("REPORT_TYPE_INPUT=$reportTypeInput OUTPUT=$reportTypeOutput FEATURE=$reportTypeFeature")
    }

    private fun findCompatibleMethod(classes: Iterable<Class<*>>, name: String, matcher: (Array<Class<*>>) -> Boolean): Method?
    {
        for(clazz in classes)
        {
            var current: Class<*>? = clazz
            while(current != null)
            {
                val currentClass = current
                val match = runCatching {
                    currentClass.declaredMethods.firstOrNull { method ->
                        method.name == name && matcher(method.parameterTypes)
                    }?.apply {
                        isAccessible = true
                    }
                }.onFailure { throwable ->
                    lastError = throwable.rootMessage()
                }.getOrNull()
                if(match != null)
                    return match
                current = currentClass.superclass
            }
        }
        return null
    }

    private fun isReportTypeParameter(parameterType: Class<*>): Boolean
    {
        return parameterType == Byte::class.javaPrimitiveType ||
            parameterType == Byte::class.javaObjectType ||
            parameterType == Int::class.javaPrimitiveType ||
            parameterType == Int::class.javaObjectType
    }

    private fun reportTypeArgument(method: Method, reportType: Byte): Any
    {
        return when(method.parameterTypes[1])
        {
            Byte::class.javaPrimitiveType, Byte::class.javaObjectType -> reportType
            else -> reportType.toInt() and 0xFF
        }
    }

    private fun reportIdArgument(method: Method, reportId: Int): Any
    {
        return when(method.parameterTypes[2])
        {
            Byte::class.javaPrimitiveType, Byte::class.javaObjectType -> reportId.toByte()
            else -> reportId and 0xFF
        }
    }

    private fun methodSignature(method: Method): String
    {
        val parameters = method.parameterTypes.joinToString(", ") { parameterType ->
            parameterType.simpleName.ifEmpty { parameterType.name }
        }
        return "${method.declaringClass.simpleName}.${method.name}($parameters)"
    }

    private fun findReportType(classes: Iterable<Class<*>>, fieldName: String, fallback: Byte): Byte
    {
        for(clazz in classes)
        {
            val fromHiddenApi = runCatching {
                HiddenApiBypass.getStaticFields(clazz)
                    .firstOrNull { it.name == fieldName }
                    ?.let { field ->
                        field.isAccessible = true
                        (field.get(null) as Number).toByte()
                    }
            }.getOrNull()
            if(fromHiddenApi != null)
                return fromHiddenApi

            val fromReflection = runCatching {
                clazz.getDeclaredField(fieldName).let { field ->
                    field.isAccessible = true
                    (field.get(null) as Number).toByte()
                }
            }.getOrNull()
            if(fromReflection != null)
                return fromReflection
        }
        return fallback
    }

    private fun findStaticString(classes: Iterable<Class<*>>, fieldName: String, fallback: String): String
    {
        for(clazz in classes)
        {
            val fromHiddenApi = runCatching {
                HiddenApiBypass.getStaticFields(clazz)
                    .firstOrNull { it.name == fieldName }
                    ?.let { field ->
                        field.isAccessible = true
                        field.get(null) as? String
                    }
            }.getOrNull()
            if(fromHiddenApi != null)
                return fromHiddenApi

            val fromReflection = runCatching {
                clazz.getDeclaredField(fieldName).let { field ->
                    field.isAccessible = true
                    field.get(null) as? String
                }
            }.getOrNull()
            if(fromReflection != null)
                return fromReflection
        }
        return fallback
    }

    private fun registerReportReceiver()
    {
        if(reportReceiverRegistered)
            return
        val filter = IntentFilter(actionReport).apply {
            addAction(actionHandshake)
        }
        runCatching {
            val handler = getHidHandler()
            if(Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
                appContext.registerReceiver(reportReceiver, filter, Manifest.permission.BLUETOOTH_CONNECT, handler, Context.RECEIVER_EXPORTED)
            else
                appContext.registerReceiver(reportReceiver, filter, Manifest.permission.BLUETOOTH_CONNECT, handler)
            reportReceiverRegistered = true
            appendLog("Registered HID report receiver: $actionReport / $actionHandshake on background thread")
        }.onFailure { throwable ->
            lastError = throwable.rootMessage()
            appendLog("Failed to register HID report receiver: $lastError")
        }
    }

    private fun unregisterReportReceiver()
    {
        if(!reportReceiverRegistered)
            return
        runCatching {
            appContext.unregisterReceiver(reportReceiver)
        }
        reportReceiverRegistered = false
    }

    private fun handleIncomingReport(reportTypeExtra: Int, reportIdExtra: Int, reportBytes: ByteArray)
    {
        val inferredReportId = when
        {
            reportIdExtra >= 0 -> reportIdExtra and 0xFF
            reportBytes.isNotEmpty() -> reportBytes[0].toInt() and 0xFF
            else -> -1
        }
        val rawId = reportBytes.firstOrNull()?.toInt()?.and(0xFF) ?: -1
        val normalizedType = if(reportTypeExtra >= 0) reportTypeExtra and 0xFF else -1
        val pending = synchronized(pendingReports) {
            pendingReports.firstOrNull { candidate ->
                val typeMatches = normalizedType < 0 || candidate.reportType == normalizedType
                val idMatches = reportIdExtra < 0 && pendingReports.size == 1 ||
                    candidate.reportId == inferredReportId ||
                    candidate.reportId == rawId
                typeMatches && idMatches
            }?.also { pendingReports.remove(it) }
        }
        if(pending != null)
        {
            val normalized = normalizeIncomingReport(pending.reportId, reportBytes)
            pending.result.set(normalized)
            pending.latch.countDown()
        }

		val listenerReportId = when
		{
			inferredReportId >= 0 -> inferredReportId
			rawId >= 0 -> rawId
			else -> -1
		}
        val normalizedForListeners = if(listenerReportId >= 0)
            normalizeIncomingReport(listenerReportId, reportBytes)
        else
            reportBytes
        val listeners = synchronized(reportListeners) { reportListeners.toList() }
        if(listeners.isNotEmpty())
        {
            for(listener in listeners)
            {
                runCatching {
                    listener.onReport(normalizedType, listenerReportId, normalizedForListeners.copyOf())
                }.onFailure { throwable ->
                    appendLog("HID report listener failed: ${throwable.rootMessage()}")
                }
            }
        }
    }

    private fun handleIncomingHandshake(status: Int)
    {
        appendLog("Received HID handshake status=$status")
        val pending = synchronized(pendingHandshakes) {
            pendingHandshakes.firstOrNull()?.also { pendingHandshakes.remove(it) }
        }
        if(pending != null)
        {
            pending.status.set(status)
            pending.latch.countDown()
        }
    }

    private fun normalizeIncomingReport(reportId: Int, reportBytes: ByteArray): ByteArray
    {
        if(reportBytes.isNotEmpty() && (reportBytes[0].toInt() and 0xFF) == (reportId and 0xFF))
            return reportBytes
        if(reportId !in 0..0xFF)
            return reportBytes
        val normalized = ByteArray(reportBytes.size + 1)
        normalized[0] = reportId.toByte()
        System.arraycopy(reportBytes, 0, normalized, 1, reportBytes.size)
        return normalized
    }

    private fun pickLikelyDualSense(devices: List<BluetoothDevice>): BluetoothDevice?
    {
        val activeInputAddresses = activeDualSenseInputBluetoothAddresses()
        return devices.maxByOrNull { device ->
            var score = 0
            val name = device.safeName()
            if(device.address?.uppercase(Locale.US) in activeInputAddresses)
                score += 1000
            if(name.contains("dualsense", ignoreCase = true))
                score += 100
            if(name.contains("wireless controller", ignoreCase = true))
                score += 90
            if(name.contains("playstation", ignoreCase = true))
                score += 50
            score
        }
    }

    private fun activeDualSenseInputBluetoothAddresses(): Set<String>
    {
        val addresses = LinkedHashSet<String>()
        runCatching {
            for(deviceId in InputDevice.getDeviceIds())
            {
                val inputDevice = InputDevice.getDevice(deviceId) ?: continue
                val name = inputDevice.name ?: ""
                val sources = inputDevice.sources
                val isController =
                    sources and InputDevice.SOURCE_GAMEPAD == InputDevice.SOURCE_GAMEPAD ||
                        sources and InputDevice.SOURCE_JOYSTICK == InputDevice.SOURCE_JOYSTICK
                val isLikelyDualSense =
                    name.contains("dualsense", ignoreCase = true) ||
                        name.contains("wireless controller", ignoreCase = true)
                if(!isController && !isLikelyDualSense)
                    continue

                val match = INPUT_DEVICE_BLUETOOTH_ADDRESS_REGEX.find(inputDevice.toString()) ?: continue
                addresses += match.groupValues[1].uppercase(Locale.US)
            }
        }
        return addresses
    }

    private fun failedResult(message: String, notify: Boolean = true): SendResult
    {
        lastSendStatus = null
        lastError = message
        appendLog(message)
        if(notify)
            notifyStatusChanged()
        return SendResult(false, "none", message)
    }

    private fun successfulResult(device: BluetoothDevice, attempt: AttemptResult, streaming: Boolean): SendResult?
    {
        if(!attempt.success)
            return null
        val message = "HID output accepted by ${attempt.transport} for ${device.displayName()}"
        cachedTransportMode = attempt.mode
        lastSendStatus = message
        lastError = null
        if(!streaming)
        {
            appendLog(message)
            notifyStatusChanged()
        }
        return SendResult(true, attempt.transport, message)
    }

    private fun runTransportAttempt(name: String, logResult: Boolean = true, action: () -> Boolean): AttemptResult
    {
        val result = runCatching(action)
        if(result.isSuccess && result.getOrDefault(false))
        {
            if(logResult)
                appendLog("$name returned true")
            return AttemptResult(OutputTransportMode.AUTO, name, true, "$name returned true")
        }

        val throwable = result.exceptionOrNull()
        if(throwable != null)
        {
            lastError = throwable.rootMessage()
            if(logResult)
                appendLog("$name failed: ${lastError}")
            return AttemptResult(OutputTransportMode.AUTO, name, false, lastError ?: "$name failed")
        }

        if(logResult)
            appendLog("$name returned false")
        return AttemptResult(OutputTransportMode.AUTO, name, false, "$name returned false")
    }

    private fun appendLog(message: String)
    {
        val line = message.trim()
        if(line.isEmpty())
            return
        val elapsedMs = SystemClock.elapsedRealtime() - logStartMs
        val prefixedLine = String.format("[%7.3fs] %s", elapsedMs / 1000.0, line)
        synchronized(logLines) {
            if(logLines.lastOrNull() == prefixedLine)
                return
            if(logLines.size >= LOG_LIMIT)
                logLines.removeFirst()
            logLines.addLast(prefixedLine)
            if(fullLogBuilder.isNotEmpty())
                fullLogBuilder.append('\n')
            fullLogBuilder.append(prefixedLine)
        }
        Log.d(TAG, prefixedLine)
    }

    private fun snapshotPreviewLog(): String = synchronized(logLines) {
        if(logLines.isEmpty()) "" else logLines.joinToString("\n")
    }

    private fun snapshotFullLog(): String = synchronized(logLines) {
        fullLogBuilder.toString()
    }

    private fun notifyStatusChanged()
    {
        mainHandler.post {
            statusListener?.invoke()
        }
    }

    private fun resetProxyState()
    {
        profileProxy = null
        proxyRequested = false
        connectedDevices = emptyList()
        selectedDevice = null
        hidHostClass = null
        setReportMethod = null
        sendDataMethod = null
        setReportBytesMethod = null
        sendDataBytesMethod = null
        getReportMethod = null
        cachedTransportMode = null
        synchronized(pendingReports) {
            pendingReports.clear()
        }
        synchronized(pendingHandshakes) {
            pendingHandshakes.clear()
        }
        synchronized(reportListeners) {
            reportListeners.clear()
        }
    }

    @SuppressLint("MissingPermission")
    private fun BluetoothDevice.safeName(): String
    {
        return runCatching { name ?: address ?: "Unknown device" }
            .getOrDefault(address ?: "Unknown device")
    }

    @SuppressLint("MissingPermission")
    private fun BluetoothDevice.displayName(): String
    {
        return runCatching {
            val deviceName = name ?: "Unknown device"
            val deviceAddress = address ?: "unknown"
            "$deviceName ($deviceAddress)"
        }.getOrDefault("Unknown device")
    }

    private fun Throwable.rootMessage(): String
    {
        var current: Throwable = this
        while(current.cause != null)
            current = current.cause!!
        return current.message ?: current.javaClass.simpleName
    }

    private fun ByteArray.previewHex(maxBytes: Int): String
    {
        if(isEmpty())
            return ""
        val byteCount = minOf(size, maxBytes)
        val chars = CharArray(byteCount * 3 - 1)
        var charIndex = 0
        for(i in 0 until byteCount)
        {
            val value = this[i].toInt() and 0xFF
            chars[charIndex++] = HEX_DIGITS[(value ushr 4) and 0x0F]
            chars[charIndex++] = HEX_DIGITS[value and 0x0F]
            if(i + 1 < byteCount)
                chars[charIndex++] = ' '
        }
        return buildString {
            append(chars, 0, chars.size)
            if(size > maxBytes)
                append(" ...")
        }
    }

    private fun ByteArray.toAsciiHex(): String
    {
        val requiredChars = size * 2
        var chars = hexCharBuffer.get()
        if(chars == null || chars.size < requiredChars)
        {
            chars = CharArray(requiredChars)
            hexCharBuffer.set(chars)
        }
        var index = 0
        for(byte in this)
        {
            chars[index++] = HEX_DIGITS[(byte.toInt() ushr 4) and 0x0F]
            chars[index++] = HEX_DIGITS[byte.toInt() and 0x0F]
        }
        return String(chars, 0, requiredChars)
    }

    private fun Intent.hiddenIntExtra(name: String, defaultValue: Int): Int
    {
        val value = extras?.get(name) ?: return defaultValue
        return when(value)
        {
            is Byte -> value.toInt() and 0xFF
            is Short -> value.toInt() and 0xFFFF
            is Int -> value
            is Long -> value.toInt()
            else -> defaultValue
        }
    }
}




