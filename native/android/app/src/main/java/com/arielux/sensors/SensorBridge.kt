package com.arielux.sensors

import android.Manifest
import android.app.Activity
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.content.Context
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.net.wifi.ScanResult as WifiScanResult
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.telephony.TelephonyManager
import android.util.Log
import android.webkit.JavascriptInterface
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import kotlin.math.sqrt

/**
 * Native phone-sensor / radio-environment bridge exposed to the WebView as
 * `window.AndroidSensors`. Mirrors the origin-restricted injection model of
 * PlayBillingInterface: the host Activity (MainActivity) injects this
 * interface ONLY while the loaded URL origin is the trusted app origin, and
 * strips it for any off-origin URL — a @JavascriptInterface is reachable from
 * any page otherwise.
 *
 * @JavascriptInterface methods return JSON strings (a WebView interface cannot
 * reliably hand complex objects back to JS). The JS wrapper
 * (src/lib/sensors/sensorBridge.js) parses them.
 *
 * HONESTY: every method reports what the hardware + granted permissions
 * actually allow. An unavailable sensor/permission returns an explicit error
 * or available:false — never a fabricated reading. Scan results stay on the
 * device; nothing is uploaded. The user may explicitly share a reading with
 * Arielux from the page (it is saved as a memory the companion can read).
 *
 * The phone CANNOT scan arbitrary radio frequencies; cellular/RF data here is
 * modem-reported cellular signal only. A general RF / SDR path is provided by
 * the existing External Connectivity registry (pair external hardware there).
 *
 * NOTE: written without an available Kotlin compiler. Verify accessor names /
 * API levels against the pinned targetSdk (36) at build time, and verify on a
 * real device before claiming any capability works.
 */
class SensorBridge(
    private val context: Context,
    private val activityProvider: () -> Activity?
) {
    private val tag = "SensorBridge"
    private val mainHandler = Handler(Looper.getMainLooper())

    // BLE discovery results, keyed by address so RSSI/last-seen refresh.
    private val bleResults = ConcurrentHashMap<String, JSONObject>()
    @Volatile private var bleScanning = false
    @Volatile private var bleConnectedAddress: String? = null
    private var gatt: BluetoothGatt? = null

    @JavascriptInterface
    fun isAvailable(): Boolean = true

    @JavascriptInterface
    fun getCapabilities(): String {
        val o = ok()
        val sm = context.getSystemService(Context.SENSOR_SERVICE) as? SensorManager
        o.put("magnetometer", sm?.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD) != null)
        val bm = context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
        o.put("bluetooth", bm?.adapter != null)
        val wm = context.getSystemService(Context.WIFI_SERVICE) as? WifiManager
        o.put("wifi", wm != null)
        val tm = context.getSystemService(Context.TELEPHONY_SERVICE) as? TelephonyManager
        o.put("cellular", tm != null)
        o.put("permissions", permissionsJson())
        return o.toString()
    }

    // ---- Magnetometer (magnetic-field sensor) ----
    @JavascriptInterface
    fun getMagnetometer(): String = runBlocking {
        val sm = context.getSystemService(Context.SENSOR_SERVICE) as? SensorManager
        val sensor = sm?.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD)
        if (sm == null || sensor == null) return@runBlocking err("no_magnetometer")
        var result: FloatArray? = null
        val latch = CountDownLatch(1)
        val listener = object : SensorEventListener {
            override fun onSensorChanged(e: SensorEvent) {
                result = e.values.copyOf()
                latch.countDown()
            }
            override fun onAccuracyChanged(s: Sensor?, a: Int) {}
        }
        try {
            sm.registerListener(listener, sensor, SensorManager.SENSOR_DELAY_FASTEST)
            withTimeoutOrNull(2000) { latch.await() }
        } finally {
            sm.unregisterListener(listener)
        }
        val v = result ?: return@runBlocking err("no_reading")
        val mag = sqrt((v[0] * v[0] + v[1] * v[1] + v[2] * v[2]).toDouble()).toFloat()
        return@runBlocking ok()
            .put("available", true)
            .put("x", v[0]).put("y", v[1]).put("z", v[2])
            .put("magnitude", mag)
            .put("unit", "µT")
            .put("timestamp", System.currentTimeMillis())
            .put("note", "Phone magnetic-field sensor; not a calibrated broadband EMF meter.")
            .toString()
    }

    // ---- Bluetooth (BLE discovery + RSSI + connect) ----
    private fun bluetoothAdapter(): BluetoothAdapter? =
        (context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) { addResult(result) }
        override fun onBatchScanResults(results: MutableList<ScanResult>?) {
            results?.forEach { addResult(it) }
        }
        override fun onScanFailed(errorCode: Int) {
            Log.w(tag, "BLE scan failed: $errorCode")
            bleScanning = false
        }
    }

    private fun addResult(r: ScanResult) {
        val dev = r.device ?: return
        val addr = dev.address ?: return
        val name = try {
            if (hasPermission(Manifest.permission.BLUETOOTH_CONNECT)) dev.name else null
        } catch (e: SecurityException) { null }
        bleResults[addr] = JSONObject()
            .put("address", addr)
            .put("name", name ?: "Unknown")
            .put("rssi", r.rssi)
            .put("timestamp", System.currentTimeMillis())
    }

    @JavascriptInterface
    fun startBleScan(): String {
        val adapter = bluetoothAdapter() ?: return err("no_bluetooth")
        if (!adapter.isEnabled) return err("bluetooth_off")
        val scanner = adapter.bluetoothLeScanner ?: return err("no_scanner")
        if (!bleScanPermissionGranted()) return err("permission_required")
        bleResults.clear()
        bleScanning = true
        try {
            scanner.startScan(scanCallback)
        } catch (e: SecurityException) {
            bleScanning = false
            return err("permission_required")
        }
        return ok().put("scanning", true).toString()
    }

    @JavascriptInterface
    fun getBleResults(): String {
        val a = JSONArray()
        bleResults.values.sortedByDescending { it.optLong("timestamp", 0) }
            .forEach { a.put(it) }
        return ok().put("scanning", bleScanning).put("results", a).toString()
    }

    @JavascriptInterface
    fun stopBleScan(): String {
        val adapter = bluetoothAdapter()
        try { adapter?.bluetoothLeScanner?.stopScan(scanCallback) } catch (e: SecurityException) {}
        bleScanning = false
        return ok().toString()
    }

    @JavascriptInterface
    fun getBleStatus(): String =
        ok().put("scanning", bleScanning)
            .put("connected_address", bleConnectedAddress ?: JSONObject.NULL)
            .toString()

    @JavascriptInterface
    fun connectBle(address: String): String {
        if (address.isNullOrBlank()) return err("bad_address")
        if (!hasPermission(Manifest.permission.BLUETOOTH_CONNECT)) return err("permission_required")
        val adapter = bluetoothAdapter() ?: return err("no_bluetooth")
        val device = try { adapter.getRemoteDevice(address) } catch (e: Exception) { return err("bad_address") }
        try {
            gatt = device.connectGatt(context, false, object : BluetoothGattCallback() {
                override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
                    if (newState == BluetoothProfile.STATE_CONNECTED) bleConnectedAddress = address
                    else bleConnectedAddress = null
                }
            })
        } catch (e: SecurityException) { return err("permission_required") }
        return ok().put("connecting", address).toString()
    }

    @JavascriptInterface
    fun disconnectBle(): String {
        try { gatt?.disconnect(); gatt?.close() } catch (e: Exception) {}
        gatt = null
        bleConnectedAddress = null
        return ok().toString()
    }

    // ---- Wi-Fi (available networks; never passwords) ----
    @JavascriptInterface
    fun startWifiScan(): String {
        val wm = context.getSystemService(Context.WIFI_SERVICE) as? WifiManager ?: return err("no_wifi")
        if (!hasPermission(Manifest.permission.ACCESS_WIFI_STATE)) return err("permission_required")
        val started = try { wm.startScan() } catch (e: Exception) { false }
        return ok().put("started", started)
            .put("throttled_note", "Android throttles frequent Wi-Fi scans; repeated scans may return cached results.")
            .toString()
    }

    @JavascriptInterface
    fun getWifiResults(): String {
        val wm = context.getSystemService(Context.WIFI_SERVICE) as? WifiManager ?: return err("no_wifi")
        if (!hasPermission(Manifest.permission.ACCESS_WIFI_STATE)) return err("permission_required")
        val results: List<WifiScanResult> = try { wm.scanResults } catch (e: Exception) { emptyList() }
        val a = JSONArray()
        for (r in results) {
            val o = JSONObject()
            o.put("ssid", r.SSID ?: "")
            // BSSID is treated as sensitive; surface only with fine location granted.
            o.put("bssid", if (hasPermission(Manifest.permission.ACCESS_FINE_LOCATION)) (r.BSSID ?: "") else "")
            o.put("rssi", r.level)
            o.put("frequency", r.frequency)
            o.put("timestamp", r.timestamp / 1000)
            a.put(o)
        }
        return ok().put("results", a).toString()
    }

    // ---- Cellular / RF (public APIs only) ----
    @JavascriptInterface
    fun getCellularInfo(): String {
        val tm = context.getSystemService(Context.TELEPHONY_SERVICE) as? TelephonyManager
            ?: return err("no_cellular")
        if (!hasPermission(Manifest.permission.READ_PHONE_STATE)) return err("permission_required")
        val o = ok()
        try { o.put("carrier", tm.networkOperatorName ?: "") } catch (e: Exception) {}
        try { o.put("network_type", networkTypeName(tm)) } catch (e: Exception) {}
        try {
            val ss = tm.signalStrength
            o.put("signal_level", ss?.level ?: -1)  // 0..4, measured by the modem
            o.put("measured", true)
        } catch (e: Exception) {
            o.put("signal_level", -1)
            o.put("measured", false)
        }
        o.put("note", "Phone cannot scan arbitrary radio frequencies. This is modem-reported cellular signal only. For general RF, pair an external SDR via External Connectivity.")
        return o.toString()
    }

    private fun networkTypeName(tm: TelephonyManager): String {
        val t = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R)
            tm.dataNetworkType
        else
            @Suppress("DEPRECATION") tm.networkType
        return when (t) {
            TelephonyManager.NETWORK_TYPE_LTE -> "LTE"
            TelephonyManager.NETWORK_TYPE_HSPA -> "HSPA"
            TelephonyManager.NETWORK_TYPE_HSDPA -> "HSDPA"
            TelephonyManager.NETWORK_TYPE_UMTS -> "UMTS"
            TelephonyManager.NETWORK_TYPE_EDGE -> "EDGE"
            TelephonyManager.NETWORK_TYPE_GPRS -> "GPRS"
            TelephonyManager.NETWORK_TYPE_CDMA -> "CDMA"
            TelephonyManager.NETWORK_TYPE_EVDO_0 -> "EVDO"
            else -> "Unknown"
        }
    }

    // ---- Permissions ----
    @JavascriptInterface
    fun hasPermission(perm: String): Boolean =
        context.checkSelfPermission(perm) == PackageManager.PERMISSION_GRANTED

    @JavascriptInterface
    fun requestSensorsPermission(permJson: String): String {
        val perms = ArrayList<String>()
        try {
            val a = JSONArray(permJson)
            for (i in 0 until a.length()) perms.add(a.getString(i))
        } catch (e: Exception) { return err("bad_perms") }
        if (perms.isEmpty()) return err("bad_perms")
        val activity = activityProvider() ?: return err("no_activity")
        val needed = perms.filterNot { hasPermission(it) }
        if (needed.isNotEmpty()) {
            mainHandler.post { activity.requestPermissions(needed.toTypedArray(), RC_SENSORS) }
        }
        return ok().toString()
    }

    // ---- helpers ----
    private fun permissionsJson(): JSONObject {
        val o = JSONObject()
        val list = listOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.BLUETOOTH_SCAN,
            Manifest.permission.BLUETOOTH_CONNECT,
            Manifest.permission.ACCESS_WIFI_STATE,
            Manifest.permission.READ_PHONE_STATE
        )
        for (p in list) o.put(p, hasPermission(p))
        return o
    }

    private fun bleScanPermissionGranted(): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
            hasPermission(Manifest.permission.BLUETOOTH_SCAN) && hasPermission(Manifest.permission.BLUETOOTH_CONNECT)
        else
            hasPermission(Manifest.permission.ACCESS_FINE_LOCATION)

    private fun ok(): JSONObject = JSONObject().put("ok", true)
    private fun err(msg: String): String = JSONObject().put("ok", false).put("error", msg).toString()

    companion object {
        private const val RC_SENSORS = 1002
    }
}