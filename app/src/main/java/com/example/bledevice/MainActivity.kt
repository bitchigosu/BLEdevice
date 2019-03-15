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
import java.util.concurrent.Future
import kotlin.experimental.and
import kotlin.experimental.or
import kotlin.math.log

class MainActivity : AppCompatActivity(), View.OnClickListener {
    private val TAG = "MainActivity"
    private val REQUEST_COARSE_LOCATION = 2
    private var mConnectionState = STATE_DISCONNECTED
    private val UUID_GLUCOSE_MEASUREMENT: UUID = UUID.fromString("00002A00-0000-1000-8000-00805F9B34FB")
    private val desiredTransmitCharacteristicUUID: UUID = UUID.fromString("436AA6E9-082E-4CE8-A08B-01D81F195B24")
    private val desiredReceiveCharacteristicUUID: UUID = UUID.fromString("436A0C82-082E-4CE8-A08B-01D81F195B24")
    private val desiredServiceUUID: UUID = UUID.fromString("436A62C0-082E-4CE8-A08B-01D81F195B24")
    private val descriptorUUID: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")


    private lateinit var mGatt: BluetoothGatt
    private lateinit var mService: BluetoothGattService
    private lateinit var mWriteCharacteristic: BluetoothGattCharacteristic
    private lateinit var mReadCharacteristic: BluetoothGattCharacteristic

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

        const val MmollToMgdl = 18.0182
        const val MgdlToMmoll = 1 / MmollToMgdl
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
        sendButton.setOnClickListener {
            sendCommand()
        }
    }

    override fun onClick(v: View?) {
        if (!mBluetoothAdapter!!.isEnabled) {
            mLeDeviceAdapter.clear()
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
                    broadcastUpdate(intentAction)
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
                    broadcastUpdate(intentAction)
                }
            }
        }


        override fun onServicesDiscovered(gatt: BluetoothGatt?, status: Int) {
            mGatt = gatt!!
            val services: MutableList<BluetoothGattService>? = gatt.services
            mService = gatt.getService(desiredServiceUUID)
            mWriteCharacteristic = mService.getCharacteristic(desiredTransmitCharacteristicUUID)
            mReadCharacteristic = mService.getCharacteristic(desiredReceiveCharacteristicUUID)


            for (i in 0 until services!!.size) {
                runOnUiThread {
                    uuid_textView.text = "" + uuid_textView.text + "\n" + services[i].uuid
                }
                Log.d(TAG, "onServicesDiscovered: ${services[i].uuid}")
            }
        }

        override fun onCharacteristicRead(
            gatt: BluetoothGatt?,
            characteristic: BluetoothGattCharacteristic?,
            status: Int
        ) {
            when (status) {
                BluetoothGatt.GATT_SUCCESS -> {
                    broadcastUpdate(ACTION_DATA_AVAILABLE, characteristic)
                    for (i in 0 until 12) {
                        Log.d(
                            TAG, "onCharacteristicRead: ${characteristic!!
                                .getFloatValue(BluetoothGattCharacteristic.FORMAT_UINT8, 0)}"
                        )
                    }
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
                    broadcastUpdate(ACTION_DATA_WRITTEN, characteristic)
                    Log.d(
                        TAG, "on" +
                                "CharacteristicWrite: SUCCESS!!"
                    )
                }
                else -> {
                    Log.d(TAG, "onCharacteristicWrite: Failed :C")
                }
            }
        }

        override fun onCharacteristicChanged(gatt: BluetoothGatt?, characteristic: BluetoothGattCharacteristic?) {
            Log.d(TAG, "onCharacteristicChanged: ${characteristic.toString()}")
            broadcastUpdate(ACTION_DATA_AVAILABLE, characteristic)
        }

        private fun broadcastUpdate(action: String) {
            val intent = Intent(action)
            sendBroadcast(intent)
        }

        private fun broadcastUpdate(action: String, characteristic: BluetoothGattCharacteristic?) {
            val intent = Intent(action)
            when (characteristic!!.uuid) {
                desiredTransmitCharacteristicUUID -> {
                    var offset = 0
                    val flags = characteristic.getIntValue(BluetoothGattCharacteristic.FORMAT_UINT8, offset)
                    offset += 1

                    val timeOffsetPresent = (flags and 0x01) > 0
                    val typeAndLocationPresent = (flags and 0x02) > 0
                    val concentrationUnit = if ((flags and 0x04) > 0) "mol/L" else "kg/L"
                    val sensorStatusAnnunciationPresent = (flags and 0x08) > 0
                    offset += 2
                    offset += 7
                    for (i in 0 until 13)
                        Log.d(
                            TAG,
                            "broadcastUpdate: INT VALUES: ${characteristic.getIntValue(
                                BluetoothGattCharacteristic.FORMAT_UINT8,
                                i
                            )}"
                        )
                    for (i in 0 until 13)
                        Log.d(
                            TAG,
                            "broadcastUpdate: FLOAT VALUES: ${characteristic.getFloatValue(
                                BluetoothGattCharacteristic.FORMAT_SFLOAT,
                                i
                            )}"
                        )
                    Log.d(TAG, "broadcastUpdate: STRING VALUE: ${characteristic.getStringValue(0)}")

                    if (timeOffsetPresent) {
                        val timeOffset = characteristic.getIntValue(BluetoothGattCharacteristic.FORMAT_SINT16, offset)
                        offset += 2
                    }

                    if (typeAndLocationPresent) {
                        var glucose = characteristic.getFloatValue(BluetoothGattCharacteristic.FORMAT_SFLOAT, 11)
                        Log.d(TAG, "broadcastUpdate: $glucose")
                        glucose = if (concentrationUnit == "mol/L") {
                            Math.round(glucose * 10000 / MmollToMgdl).toFloat()
                        } else {
                            Math.round(glucose * 10000 * MmollToMgdl).toFloat()
                        }

                        Log.d(TAG, "broadcastUpdate: Received glucose ${glucose}")
                        intent.putExtra(EXTRA_DATA, glucose)
                    }

                }
                else -> {
                    Log.d(TAG, "broadcastUpdate: No matches")
                }
            }

        }
    }

    private fun sendCommand() {
        val value = "010d0e0127".toByteArray(Charsets.UTF_8)
        Log.d(TAG, "sendCommand: $value")
        mWriteCharacteristic.value = value
        Log.d(TAG, "sendCommand: ${mWriteCharacteristic.value}")
        val b = mGatt.writeCharacteristic(mWriteCharacteristic)
        Log.d(TAG, "sendCommand: $b")
    }

    private fun enableNotifications(gatt: BluetoothGatt) {
        val service = gatt.getService(desiredServiceUUID)
        val characteristic = service.getCharacteristic(desiredReceiveCharacteristicUUID)
        val descriptor = characteristic.getDescriptor(descriptorUUID)
        descriptor.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
        gatt.writeDescriptor(descriptor)
    }
}





