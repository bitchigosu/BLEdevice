package com.example.bledevice

import android.Manifest
import android.bluetooth.*
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.support.v7.app.AppCompatActivity
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.View
import kotlinx.android.synthetic.main.activity_main.*
import java.nio.charset.Charset
import java.util.*

class MainActivity : AppCompatActivity(), View.OnClickListener {
    private val TAG = "MainActivity"
    private val REQUEST_COARSE_LOCATION = 2
    private var mConnectionState = STATE_DISCONNECTED
    private val UUID_GLUCOSE_MEASUREMENT: UUID = UUID.fromString("00002A00-0000-1000-8000-00805F9B34FB")
    private val desiredTransmitCharacteristicUUID: UUID = UUID.fromString("436AA6E9-082E-4CE8-A08B-01D81F195B24")
    private val desiredReceiveCharacteristicUUID: UUID = UUID.fromString("436A0C82-082E-4CE8-A08B-01D81F195B24")
    private val desiredServiceUUID: UUID = UUID.fromString("436A62C0-082E-4CE8-A08B-01D81F195B24")
    private lateinit var writeCharacteristic: BluetoothGattCharacteristic
    private lateinit var readCharacteristic: BluetoothGattCharacteristic
    private var responseString: String = ""
    private lateinit var response: String
    private var startIndex: Int = 0
    private var endIndex: Int = 0
    private var nowGlucoseIndex2: Int = 0
    private var nowGlucoseIndex3: Int = 0
    private var nowGlucoseOffset: Int = 0

    private lateinit var mGatt: BluetoothGatt

    companion object {
        private const val SCAN_PERIOD: Long = 10000
        private const val STATE_DISCONNECTED = 0
        private const val STATE_CONNECTING = 1
        private const val STATE_CONNECTED = 2
        const val ACTION_GATT_CONNECTED = "com.example.bluetooth.le.ACTION_GATT_CONNECTED"
        const val ACTION_GATT_DISCONNECTED = "com.example.bluetooth.le.ACTION_GATT_DISCONNECTED"
        const val ACTION_GATT_SERVICES_DISCOVERED =
            "com.example.bluetooth.le.ACTION_GATT_SERVICES_DISCOVERED"
        const val ACTION_DATA_AVAILABLE = "com.example.bluetooth.le.ACTION_DATA_AVAILABLE"
        const val EXTRA_DATA = "com.example.bluetooth.le.EXTRA_DATA"
        const val ACTION_DATA_WRITTEN = "com.example.bluetooth.ACTION_DATA_WRITTEN"
    }

    enum class BlueComms(s: String) {
        initalState(" "),
        getNowDataIndex("010d0e0103"),
        getNowGlucoseData("9999999999"),
        getTrendData("010d0f02030c"),

        getSerialNumber("010d0e0100"),
        getPatchInfo("010d0900"),
        getSensorTime("010d0e0127")
    }

    enum class BlueResponseComms(s: String) {
        patchInfoResponsePrefix("8bd9"),
        singleBlockInfoResponsePrefix("8bde"),
        multipleBlockInfoResponsePrefix("8bdf"),
        sensorTimeResponsePrefix("8bde27"),
        bluconACKResponse("8b0a00"),
        bluconNACKResponsePrefix("8b1a02")
    }

    private var currentComm: BlueComms = BlueComms.initalState

    private var mBluetoothAdapter: BluetoothAdapter? = null
    private var mBluetoothManager: BluetoothManager? = null
    private var mScanning = false
    var mBluetoothGatt: BluetoothGatt? = null
    private lateinit var mHandler: Handler
    private lateinit var mLeDeviceAdapter: LeDeviceListAdapter
    private lateinit var mGattCharacteristic: MutableList<BluetoothGattCharacteristic>


    private val mLeScanCallback = BluetoothAdapter.LeScanCallback { device, _, _ ->
        runOnUiThread {
            mLeDeviceAdapter.addDevice(device)
            mLeDeviceAdapter.notifyDataSetChanged()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            if (checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
                requestPermissions(arrayOf(Manifest.permission.ACCESS_COARSE_LOCATION), REQUEST_COARSE_LOCATION)
            }
        }

        mBluetoothManager = getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager?
        mBluetoothAdapter = mBluetoothManager?.adapter
        mHandler = Handler()
        mLeDeviceAdapter = LeDeviceListAdapter(this)
        listview.adapter = mLeDeviceAdapter
        listview.setOnItemClickListener { parent, view, position, id ->
            val device: BluetoothDevice = mLeDeviceAdapter.getDevice(position)
            mBluetoothGatt = device
                .connectGatt(this, false, mGattCallback)
        }
        searchButton.isEnabled = true
        searchButton.setOnClickListener(this)
    }

    override fun onClick(v: View?) {
        if (!mBluetoothAdapter!!.isEnabled) {
            mBluetoothAdapter.takeIf {
                it!!.isEnabled
            }?.apply {
                val enableBtIntent = Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE)
                startActivityForResult(enableBtIntent, 1)
            }
        }

        mScanning = true
        scanDevices(mScanning)
    }

    private fun pathInfo(): String {
        response = responseString
        startIndex = response.indexOf(response.first() + 4)
        endIndex = response.indexOf(response.first() + 25)
        val result = response.substring(startIndex, endIndex)
        return if (result.length == 22) result else ""
    }

    private fun getNowGlucoseDataCommand() {
        currentComm = BlueComms.getNowGlucoseData

    }

    private fun parseSingleBlockResponseIntoByteArray(response: String): String {
        val startIndex = response.indexOf(response.first() + 6)
        val endIndex = response.indexOf(response.first() + response.length - 1)
        return response.substring(startIndex, endIndex)
    }

    private fun blockNumberForNowGlucoseData(): String {
        val getNowDataIndexResponse = parseSingleBlockResponseIntoByteArray(responseString)
        nowGlucoseIndex2 =
            String.format("%02X", getNowDataIndexResponse[(getNowDataIndexResponse.length - 1) - 5]).toInt()
        nowGlucoseIndex2 = nowGlucoseIndex2 * 6 + 4
        nowGlucoseIndex2 -= 6
        if (nowGlucoseIndex2 < 4) {
            nowGlucoseIndex2 += 96
        }
        nowGlucoseOffset = nowGlucoseIndex2 % 8
        nowGlucoseIndex3 = 3 + (nowGlucoseIndex2 / 8)
        val hexString = String.format("%02X", nowGlucoseIndex3)
        return hexString
    }

    private fun nowGlucoseValue(): Int {
        val blockPairs = parseSingleBlockResponseIntoByteArray(responseString)
        val newGetGlucose = GlucoseDecoder().getGlucose(
            blockPairs.get(nowGlucoseOffset + 1).toString()
                    + blockPairs.get(nowGlucoseOffset).toString()
        )
        return newGetGlucose
    }

    private fun sendCommand() {
        //write
    }

    private fun writeCharacteristic(
        gatt: BluetoothGatt,
        serviceUUID: UUID,
        characteristicUUID: UUID,
        data: Int
    ) {
        val service: BluetoothGattService = gatt.getService(serviceUUID)
        val characteristic: BluetoothGattCharacteristic = service.getCharacteristic(characteristicUUID)
        // val data2: Int = 0xFF
        characteristic.setValue(data, BluetoothGattCharacteristic.FORMAT_UINT16, 0)

    }

    private fun scanDevices(enable: Boolean) {
        searchButton.isEnabled = !mScanning
        when (enable) {
            true -> {
                mHandler.postDelayed({
                    mScanning = false
                    mBluetoothAdapter?.stopLeScan(mLeScanCallback)
                    searchButton.isEnabled = true
                }, SCAN_PERIOD)
                mScanning = true
                mBluetoothAdapter?.startLeScan(mLeScanCallback)
            }
            else -> {
                mScanning = false
                mBluetoothAdapter?.stopLeScan(mLeScanCallback)
            }
        }
    }

    private val mGattCallback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(gatt: BluetoothGatt?, status: Int, newState: Int) {
            mGatt = gatt!!
            val intentAction: String
            when (newState) {
                BluetoothProfile.STATE_CONNECTED -> {
                    intentAction = ACTION_GATT_CONNECTED
                    mConnectionState = STATE_CONNECTED
                    Handler(Looper.getMainLooper()).postDelayed({
                        val ans: Boolean = gatt.discoverServices()
                        Log.d(TAG, "Connected to GATT server $ans.")
                    }, 1000)
                    Log.d(TAG, "Connected to GATT server.")
                }
                BluetoothProfile.STATE_DISCONNECTED -> {
                    intentAction = ACTION_GATT_DISCONNECTED
                    mConnectionState = STATE_DISCONNECTED
                    Log.d(TAG, "Disconnected from GATT server.")
                }
            }
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt?, status: Int) {
            mGatt = gatt!!
            val services: MutableList<BluetoothGattService>? = gatt.services
            for (i in 0 until services!!.size) {
                runOnUiThread {
                    uuid_textView.text = "" + uuid_textView.text + "\n" + services[i].uuid
                }
                Log.d(TAG, "onServicesDiscovered: ${services.get(i).uuid}")
                for (j in 0 until services[i].characteristics.size) {
                    //    gatt.readCharacteristic(services[i].characteristics[j])
                }
            }
            val customService: BluetoothGattService = gatt.getService(desiredServiceUUID)
            val customWriteCharacteristic = customService.getCharacteristic(desiredTransmitCharacteristicUUID)
            val customReadCharacteristic = customService.getCharacteristic(desiredReceiveCharacteristicUUID)

            when (status) {
                BluetoothGatt.GATT_SUCCESS -> {
                    gatt.readCharacteristic(customReadCharacteristic)
                } //broadcastUpdate(ACTION_GATT_SERVICES_DISCOVERED)
                else ->
                    Log.d(TAG, "onServicesDiscoveredStatus: $status")
            }
            customWriteCharacteristic.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
            customWriteCharacteristic.value = "010d0e0127".toByteArray(Charsets.UTF_8)
            val s = gatt.writeCharacteristic(customWriteCharacteristic)
            if (s) Log.d(TAG, "onServicesDiscovered: write is successful")
            else Log.d(TAG, "onServicesDiscovered: write failed")
            Log.d(TAG, "onServicesDiscovered: ${customWriteCharacteristic.value.toString(Charsets.UTF_8)}")
        }

        override fun onCharacteristicRead(
            gatt: BluetoothGatt?,
            characteristic: BluetoothGattCharacteristic?,
            status: Int
        ) {
            when (status) {
                BluetoothGatt.GATT_SUCCESS -> {
                    Log.d(TAG, "onCharacteristicRead: ${characteristic!!.value.toString(Charsets.UTF_8)}")
                }
            }

        }

        override fun onCharacteristicWrite(
            gatt: BluetoothGatt?,
            characteristic: BluetoothGattCharacteristic?,
            status: Int
        ) {
            when (status) {
                BluetoothGatt.GATT_SUCCESS -> {
                    // broadcastUpdate(ACTION_DATA_WRITTEN, characteristic)
                    Log.d(TAG, "onCharacteristicWrite: SUCCESS!!")
                }
                else -> {
                    Log.d(TAG, "onCharacteristicWrite: Failed :C")
                }
            }
        }

        override fun onCharacteristicChanged(gatt: BluetoothGatt?, characteristic: BluetoothGattCharacteristic?) {
            Log.d(TAG, "onCharacteristicChanged: ${characteristic.toString()}")
        }

        private fun broadcastUpdate(action: String) {
            val intent = Intent(action)
            sendBroadcast(intent)
        }

        private fun broadcastUpdate(action: String, characteristic: BluetoothGattCharacteristic?) {
            val intent = Intent(action)
            when (characteristic?.uuid) {
                desiredReceiveCharacteristicUUID -> {
                    val flag = characteristic.properties
                    val format = when (flag and 0x06) {
                        0x06 -> {
                            Log.d(TAG, "Heart rate format UINT16.")
                            BluetoothGattCharacteristic.FORMAT_UINT16
                        }
                        else -> {
                            Log.d(TAG, "Heart rate format UINT8.")
                            BluetoothGattCharacteristic.FORMAT_UINT8
                        }
                    }
                    for (i in 0 until 8) {
                        val heartRate = characteristic.getIntValue(BluetoothGattCharacteristic.FORMAT_UINT8, i)
                        runOnUiThread {
                            uuid_characteristics_textView.text =
                                " " + uuid_characteristics_textView.text + " " + heartRate
                        }
                    }
                    val heartRate = characteristic.getIntValue(BluetoothGattCharacteristic.FORMAT_UINT8, 7)
                    Log.d(TAG, String.format("Received heart rate: %d", heartRate))
                    intent.putExtra(EXTRA_DATA, (heartRate).toString())
                }
                else -> {
                    // For all other profiles, writes the data formatted in HEX.
                    val data: ByteArray? = characteristic!!.value
                    writeCharacteristic(
                        mGatt,
                        desiredServiceUUID,
                        desiredTransmitCharacteristicUUID,
                        9999999999.toInt()
                    )
                    if (data?.isNotEmpty() == true) {
                        val hexString: String = data.joinToString(separator = " ") {
                            String.format("%02X", it)
                        }
                        intent.putExtra(EXTRA_DATA, "$data\n$hexString")
                    }
                }
            }
        }

        fun octalToDecimal(octal: Int): Int {

            var decimal = 0

            var remain = octal
            var i = 0

            while (remain > 0) {

                decimal += remain % 10 * pow(8, i)
                remain /= 10

                i++
            }

            return decimal
        }

        private fun pow(a: Int, b: Int): Int {

            val _a = a.toDouble()
            val _b = b.toDouble()

            return Math.pow(_a, _b).toInt()
        }
    }
}

//    private val mGattUpdateReceiver = object : BroadcastReceiver() {
//        override fun onReceive(context: Context?, intent: Intent?) {
//            val action = intent!!.action
//            when (action) {
//                ACTION_GATT_CONNECTED -> {
//                    mConnected = true
//                    updateConnectionState(R.string.connected)
//                    (context as? Activity)?.invalidateOptionsMenu()
//                }
//                ACTION_GATT_DISCONNECTED -> {
//                    mConnected = false
//                    updateConnectionState(R.string.disconnected)
//                    (context as? Activity)?.invalidateOptionsMenu()
//                    clearUI()
//                }
//                ACTION_GATT_SERVICES_DISCOVERED -> {
//                    // Show all the supported services and characteristics on the
//                    // user interface.
//                    displayGattServices(mBluetoothLeService.getSupportedGattServices())
//                }
//                ACTION_DATA_AVAILABLE -> {
//                    displayData(intent.getStringExtra(BluetoothLeService.EXTRA_DATA))
//                }
//            }
//
//        }
//
//    }





