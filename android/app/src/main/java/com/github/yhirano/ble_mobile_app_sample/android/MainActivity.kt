package com.github.yhirano.ble_mobile_app_sample.android

import android.Manifest
import android.bluetooth.*
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.content.pm.PackageManager
import android.os.Bundle
import android.util.Log
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.*


class MainActivity : AppCompatActivity() {

    /** 温度と湿度を表示するTextView */
    private val sensorTextView by lazy {
        findViewById<TextView>(R.id.sensorTextView)
    }

    /** RSSI(電波強度)を表示するTextView */
    private val rssiTextView by lazy {
        findViewById<TextView>(R.id.rssiTextView)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        if (allPermissionsGranted()) {
            // 位置情報のパーミッションが取得できている場合は、BLEのスキャンを開始
            bleScanStart()
        } else {
            // 位置情報のパーミッションが取得できていない場合は、位置情報の取得のパーミッションの許可を求める
            ActivityCompat.requestPermissions(
                this,
                REQUIRED_PERMISSIONS,
                REQUEST_CODE_PERMISSIONS
            )
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_CODE_PERMISSIONS) {
            if (allPermissionsGranted()) {
                // 位置情報のパーミッションが取得できている場合は、BLEのスキャンを開始
                bleScanStart()
            } else {
                sensorTextView.text = "パーミッションが許可されていません"
                rssiTextView.text = null
            }
        }
    }

    /** REQUIRED_PERMISSIONSで指定したパーミッション全てが許可済みかを取得する */
    private fun allPermissionsGranted() = REQUIRED_PERMISSIONS.all {
        ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
    }

    /** BLEのスキャンを開始 */
    private fun bleScanStart() {
        val manager: BluetoothManager = getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        val adapter = manager.adapter
        if (adapter == null) {
            sensorTextView.text = "Bluetoothがサポートされていません"
            rssiTextView.text = null
            return
        }
        if (!adapter.isEnabled) {
            sensorTextView.text = "Bluetoothの電源が入っていません"
            rssiTextView.text = null
            return
        }
        val bluetoothLeScanner = adapter.bluetoothLeScanner
        // "M5GO Env.Sensor Advertiser" というデバイス名のみの通知を受け取るように設定
        val scanFilter = ScanFilter.Builder()
            .setDeviceName("M5GO Env.Sensor Advertiser")
            .build()
        val scanSettings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_BALANCED)
            .build()
        Log.d(TAG, "Start BLE scan.")
        bluetoothLeScanner.startScan(listOf(scanFilter), scanSettings, scanCallback)
    }

    /** スキャンでデバイスが見つかった際のコールバック */
    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            super.onScanResult(callbackType, result)

            rssiTextView.text = "RSSI(受信信号強度) ${result.rssi}"

            // デバイスのGattサーバに接続
            val bluetoothGatt = result.device.connectGatt(this@MainActivity, false, gattCallback)
            val resultConnectGatt = bluetoothGatt.connect()
            if (resultConnectGatt) {
                Log.d(TAG, "Success to connect gatt.")
            } else {
                Log.w(TAG, "Failed to connect gatt.")
            }
        }
    }

    /** デバイスのGattサーバに接続された際のコールバック */
    private val gattCallback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(gatt: BluetoothGatt?, status: Int, newState: Int) {
            super.onConnectionStateChange(gatt, status, newState)

            if (gatt == null) {
                Log.w(TAG, "Gatt is empty. Maybe Bluetooth adapter not initialized.")
                return
            }

            if (newState == BluetoothGatt.STATE_CONNECTED) {
                Log.d(TAG, "Discover services.")
                // GATTサーバのサービスを探索する。
                // サービスが見つかったら onServicesDiscovered が呼ばれる。
                gatt.discoverServices()
            }
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt?, status: Int) {
            super.onServicesDiscovered(gatt, status)

            Log.d(TAG, "Services discovered.")

            if (status == BluetoothGatt.GATT_SUCCESS) {
                if (gatt == null) {
                    Log.w(TAG, "Gatt is empty. Maybe Bluetooth adapter not initialized.")
                    return
                }
                val service = gatt.getService(BLE_SERVICE_UUID)
                val characteristic = service?.getCharacteristic(BLE_CHARACTERISTIC_UUID)
                if (characteristic == null) {
                    Log.w(TAG, "Characteristic is empty. Maybe Bluetooth adapter not initialized.")
                    return
                }

                // Characteristic "0fc10cb8-0518-40dd-b5c3-c4637815de40" のNotifyを監視する。
                // 変化があったら onCharacteristicChanged が呼ばれる。
                gatt.setCharacteristicNotification(characteristic, true)
                val descriptor = characteristic.getDescriptor(
                    UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
                )
                descriptor.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                gatt.writeDescriptor(descriptor)
            }
        }

        override fun onCharacteristicChanged(gatt: BluetoothGatt?, characteristic: BluetoothGattCharacteristic?) {
            super.onCharacteristicChanged(gatt, characteristic)

            Log.v(TAG, "onCharacteristicChanged")

            this@MainActivity.runOnUiThread {
                val data = Data.parse(characteristic?.value) ?: return@runOnUiThread

                val sb = StringBuilder()
                sb.append("Temperature: ${String.format("%.2f", data.temperature)}\n")
                sb.append("Pressure: ${String.format("%.2f", data.pressure)}")
                sensorTextView.text = sb.toString()
            }
        }
    }

    /** 温度と気圧を持つデータクラス */
    private data class Data(val temperature: Float, val pressure: Float) {
        companion object {
            /**
             * BLEから飛んできたデータをDataクラスにパースする
             */
            fun parse(data: ByteArray?): Data? {
                if (data == null || data.size < 8) {
                    return null
                }

                val temperatureBytes = ByteBuffer.wrap(data, 0, 4)
                val pressureBytes = ByteBuffer.wrap(data, 4, 4)

                val temperature = temperatureBytes.order(ByteOrder.LITTLE_ENDIAN).int.toFloat() / 100.toFloat()
                val pressure = pressureBytes.order(ByteOrder.LITTLE_ENDIAN).int.toFloat() / 100.toFloat()
                return Data(temperature, pressure)
            }
        }
    }

    companion object {
        private val TAG = MainActivity::class.java.simpleName

        private const val REQUEST_CODE_PERMISSIONS = 10

        private val REQUIRED_PERMISSIONS = arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)

        /** BLEのサービスUUID */
        private val BLE_SERVICE_UUID = UUID.fromString("133fe8d4-5197-4675-9d76-d9bbf2450bb4")

        /** BLEのCharacteristic UUID */
        private val BLE_CHARACTERISTIC_UUID = UUID.fromString("0fc10cb8-0518-40dd-b5c3-c4637815de40")
    }
}
