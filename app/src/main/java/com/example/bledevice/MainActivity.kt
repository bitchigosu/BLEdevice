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
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock
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
    private val GLUCOSE_SERVICE = UUID.fromString("00001808-0000-1000-8000-00805f9b34fb")
    private val CURRENT_TIME_SERVICE = UUID.fromString("00001805-0000-1000-8000-00805f9b34fb")
    private val DEVICE_INFO_SERVICE = UUID.fromString("0000180a-0000-1000-8000-00805f9b34fb")
    private val CONTOUR_SERVICE = UUID.fromString("00000000-0002-11e2-9e96-0800200c9a66")

    private val CLIENT_CHARACTERISTIC_CONFIG = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
    private val GLUCOSE_CHARACTERISTIC = UUID.fromString("00002a18-0000-1000-8000-00805f9b34fb")
    private val CONTEXT_CHARACTERISTIC = UUID.fromString("00002a34-0000-1000-8000-00805f9b34fb")
    private val RECORDS_CHARACTERISTIC = UUID.fromString("00002a52-0000-1000-8000-00805f9b34fb")
    private val TIME_CHARACTERISTIC = UUID.fromString("00002a2b-0000-1000-8000-00805f9b34fb")
    private val DATE_TIME_CHARACTERISTIC = UUID.fromString("00002a08-0000-1000-8000-00805f9b34fb")

    private lateinit var mGatt: BluetoothGatt
    private lateinit var mService: BluetoothGattService
    private lateinit var mWriteCharacteristic: BluetoothGattCharacteristic
    private lateinit var mReadCharacteristic: BluetoothGattCharacteristic

    private var mBondingState: Int = 0

    private var mGetNowGlucoseDataCommand: Boolean = false
    private var mGetNowGlucoseDataIndexCommand: Boolean = false
    private var mCommunicationStarted: Boolean = false
    private val GET_DECODE_SERIAL_DELAY = 12 * 3600
    private val GET_SENSOR_AGE_DELAY = 3 * 3600
    private val BLUKON_GETSENSORAGE_TIMER = "blukon-getSensorAge-timer"
    private val BLUKON_DECODE_SERIAL_TIMER = "blukon-decodeSerial-timer"


    private val mLock = ReentrantLock()
    private val condition = mLock.newCondition()

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

        lateinit var mContext: Context
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

    private var currentCommand: String = ""

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
        mContext = applicationContext

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
            mBondingState = mGatt.device.bondState
            if (mBondingState != BluetoothDevice.BOND_BONDED) {
                mGatt.device.createBond()
                waitFor(1000)
                mBondingState = mGatt.device.bondState
                if (mBondingState != BluetoothDevice.BOND_BONDED) {
                    Log.d(TAG, "onServicesDiscovered: Pairing appeared to fail")
                }
            } else {
                Log.d(TAG, "onServicesDiscovered: Device is already bonded")
            }

            val services: MutableList<BluetoothGattService>? = gatt.services
            mService = gatt.getService(desiredServiceUUID)
            mWriteCharacteristic = mService.getCharacteristic(desiredTransmitCharacteristicUUID)
            mReadCharacteristic = mService.getCharacteristic(desiredReceiveCharacteristicUUID)

            val charaProp = mReadCharacteristic.properties
            if ((charaProp and BluetoothGattCharacteristic.PROPERTY_NOTIFY) > 0) {
                Log.d(TAG, "onServicesDiscovered: Setting notification on characteristic")
                val result = mGatt.setCharacteristicNotification(mReadCharacteristic, true)
                if (!result) Log.d(TAG, "onServicesDiscovered: Failed seeting notification on blukon")
            } else {
                Log.d(TAG, "onServicesDiscovered: Unusual error")
            }
            mGatt.readCharacteristic(mReadCharacteristic)
        }

        override fun onCharacteristicRead(
            gatt: BluetoothGatt?,
            characteristic: BluetoothGattCharacteristic?,
            status: Int
        ) {
            onCharacteristicChanged(gatt, characteristic)
        }

        override fun onCharacteristicWrite(
            gatt: BluetoothGatt?,
            characteristic: BluetoothGattCharacteristic?,
            status: Int
        ) {
            when (status) {
                BluetoothGatt.GATT_SUCCESS -> {
                    onCharacteristicChanged(gatt, characteristic)
                   // broadcastUpdate(ACTION_DATA_WRITTEN, characteristic)
                }
                else -> {
                    Log.d(TAG, "onCharacteristicWrite: Failed :C")
                }
            }
        }

        override fun onCharacteristicChanged(gatt: BluetoothGatt?, characteristic: BluetoothGattCharacteristic?) {
            Log.d(TAG, "onCharacteristicChanged: ${characteristic!!.value.toString()}")
            val data = characteristic!!.getValue()
            if (data != null && data.isNotEmpty()) {
                setSerialDataToTransmitterRawData(data, data.size)
            }
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

    private fun setSerialDataToTransmitterRawData(buffer: ByteArray?, len: Int) {
        val reply = decodePacket(buffer)
        if (reply != null) {

        }
    }


    private var mBlockNumber: Int = 0
    private var mCurrentBlockNumber: Int = 0
    private var mCurrentOffset: Int = 0


    private fun decodePacket(buffer: ByteArray?): ByteArray? {
        var cmdFound = 0
        var gotLowBat: Boolean? = false
        var getHistoricReadings: Boolean? = false
        if (buffer == null) {
            Log.d(TAG, "decodePacket: null buffer")
            return null
        }

        val strRecCmd = buffer.toHex().toLowerCase()
        Log.d(TAG, "decodePacket: $strRecCmd")

        if (strRecCmd.equals("cb010000", ignoreCase = true)) {
            Log.i(TAG, "Reset currentCommand")
            currentCommand = ""
            cmdFound = 1
            mCommunicationStarted = true
        }

        if (strRecCmd.startsWith("8b0a00")) {
            cmdFound = 1
            Log.i(TAG, "Got ACK")

            if (currentCommand.startsWith("810a00")) {//ACK sent
                //ack received

                currentCommand = "010d0b00"
                Log.i(TAG, "getUnknownCmd1: $currentCommand")

            } else {
                Log.i(TAG, "Got sleep ack, resetting initialstate!")
                currentCommand = ""
            }
        }

        if (strRecCmd.startsWith("8b1a02")) {
            cmdFound = 1
            Log.e(TAG, "Got NACK on cmd=" + currentCommand + " with error=" + strRecCmd.substring(6))

            if (strRecCmd.startsWith("8b1a020014")) {
                Log.e(TAG, "Timeout: please wait 5min or push button to restart!")
            }

            if (strRecCmd.startsWith("8b1a02000f")) {
                Log.e(TAG, "Libre sensor has been removed!")
            }

            if (strRecCmd.startsWith("8b1a020011")) {
                Log.e(
                    TAG,
                    "Patch read error.. please check the connectivity and re-initiate... or maybe battery is low?"
                )
                //Pref.setInt("bridge_battery", 1)
                gotLowBat = true
            }

            if (strRecCmd.startsWith("8b1a020009")) {
                //Log.e(TAG, "");
            }

            mGetNowGlucoseDataCommand = false
            mGetNowGlucoseDataIndexCommand = false

            currentCommand = "010c0e00"
            Log.i(TAG, "Send sleep cmd")
            mCommunicationStarted = false
        }

        if (currentCommand == "" && strRecCmd.equals("cb010000", ignoreCase = true)) {
            cmdFound = 1
            Log.i(TAG, "wakeup received")

            //must be first cmd to be sent otherwise get NACK!
            if (JoHH.ratelimit("blukon-request_patch_info", 1)) {
                currentCommand = "010d0900"
            }
            Log.i(TAG, "getPatchInfo")

        } else if (currentCommand.startsWith("010d0900") /*getPatchInfo*/ && strRecCmd.startsWith("8bd9")) {
            cmdFound = 1
            Log.i(TAG, "Patch Info received")

            /*
                in getPatchInfo: blucon answer is 20 bytes long.
                Bytes 13 - 19 (0 indexing) contains the bytes 0 ... 6 of block #0
                Bytes 11 to 12: ?
                Bytes 3 to 10: Serial Number reverse order
                Byte 2: 04: ?
                Bytes 0 - 1 (0 indexing) is the ordinary block request answer (0x8B 0xD9).

                Remark: Byte #17 (0 indexing) contains the SensorStatusByte.
            */

            if (JoHH.pratelimit(BLUKON_DECODE_SERIAL_TIMER, GET_DECODE_SERIAL_DELAY)) {
                decodeSerialNumber(buffer)
            }

            if (isSensorReady(buffer[17])) {
                currentCommand = "810a00"
                Log.i(TAG, "Send ACK")
            } else {
                Log.e(TAG, "Sensor is not ready, stop!")
                currentCommand = "010c0e00"
                Log.i(TAG, "Send sleep cmd")
                mCommunicationStarted = false
            }

        } else if (currentCommand.startsWith("010d0b00") /*getUnknownCmd1*/ && strRecCmd.startsWith("8bdb")) {
            cmdFound = 1
            Log.i(TAG, "gotUnknownCmd1 (010d0b00): $strRecCmd")

            if (strRecCmd != "8bdb0101041711") {
                Log.e(TAG, "gotUnknownCmd1 (010d0b00): $strRecCmd")
            }

            currentCommand = "010d0a00"
            Log.i(TAG, "getUnknownCmd2 $currentCommand")

        } else if (currentCommand.startsWith("010d0a00") /*getUnknownCmd2*/ && strRecCmd.startsWith("8bda")) {
            cmdFound = 1
            Log.i(TAG, "gotUnknownCmd2 (010d0a00): $strRecCmd")

            if (strRecCmd != "8bdaaa") {
                Log.e(TAG, "gotUnknownCmd2 (010d0a00): $strRecCmd")
            }

            if (strRecCmd == "8bda02") {
                Log.e(TAG, "gotUnknownCmd2: is maybe battery low????")
                // Pref.setInt("bridge_battery", 5)
                gotLowBat = true
            }

            if (JoHH.pratelimit(BLUKON_GETSENSORAGE_TIMER, GET_SENSOR_AGE_DELAY)) {
                currentCommand = "010d0e0127"
                Log.i(TAG, "getSensorAge")
            } else {
                currentCommand = "010d0e0103"
                mGetNowGlucoseDataIndexCommand =
                    true//to avoid issue when gotNowDataIndex cmd could be same as getNowGlucoseData (case block=3)
                Log.i(TAG, "getNowGlucoseDataIndexCommand")
            }

        } else if (currentCommand.startsWith("010d0e0127") /*getSensorAge*/ && strRecCmd.startsWith("8bde")) {
            cmdFound = 1
            val sensorAge = sensorAge(buffer)
            Log.i(TAG, "SensorAge received $sensorAge")


            currentCommand = "010d0e0103"
            mGetNowGlucoseDataIndexCommand =
                true//to avoid issue when gotNowDataIndex cmd could be same as getNowGlucoseData (case block=3)
            Log.i(TAG, "getNowGlucoseDataIndexCommand")


        } else if (currentCommand.startsWith("010d0e0103") /*getNowDataIndex*/ && mGetNowGlucoseDataIndexCommand == true && strRecCmd.startsWith(
                "8bde"
            )
        ) {
            cmdFound = 1

            // get index to current BG reading
            mCurrentBlockNumber = blockNumberForNowGlucoseData(buffer)
            mCurrentOffset = mNowGlucoseOffset
            // time diff must be > 5,5 min and less than the complete trend buffer
            currentCommand = "010d0e010" + Integer.toHexString(mCurrentBlockNumber)//getNowGlucoseData
            mNowGlucoseOffset = mCurrentOffset
            Log.i(TAG, "getNowGlucoseData")
            mGetNowGlucoseDataIndexCommand = false
            mGetNowGlucoseDataCommand = true

        } else if (currentCommand.startsWith("010d0e01") /*getNowGlucoseData*/ && mGetNowGlucoseDataCommand == true && strRecCmd.startsWith(
                "8bde"
            )
        ) {
            Log.d(TAG, "Before Saving data: + currentCommand = $currentCommand")
            val blockId = currentCommand.substring("010d0e01".length)
            val now = JoHH.tsl()
            if (!blockId.isEmpty()) {
                val blockNum = JoHH.parseIntWithDefault(blockId, 16, -1)
                if (blockNum != -1) {
                    Log.d(TAG, "Saving data: + blockid = $blockNum")
                }
            }

            cmdFound = 1
            val currentGlucose = nowGetGlucoseValue(buffer)

            Log.i(TAG, "********got getNowGlucoseData=$currentGlucose")

            currentCommand = "010c0e00"
            Log.i(TAG, "Send sleep cmd")
            mCommunicationStarted = false

            mGetNowGlucoseDataCommand = false
            Log.i(TAG, "bf: processNewTransmitterData with delayed timestamp of X min")

        } else if ((currentCommand.startsWith("010d0f02002b") /*getHistoricData */ || currentCommand.isEmpty() && mBlockNumber > 0) && strRecCmd.startsWith(
                "8bdf"
            )
        ) {
            cmdFound = 1
        } else if (strRecCmd.startsWith("cb020000")) {
            cmdFound = 1
            Log.e(TAG, "is bridge battery low????!")
            gotLowBat = true
        } else if (strRecCmd.startsWith("cbdb0000")) {
            cmdFound = 1
            Log.e(TAG, "is bridge battery really low????!")
            gotLowBat = true
        }

        if (currentCommand.length > 0 && cmdFound == 1) {
            Log.i(TAG, "Sending reply: $currentCommand")
            return currentCommand.toByteArray(Charset.defaultCharset())
        } else {
            if (cmdFound == 0) {
                Log.e(TAG, "***COMMAND NOT FOUND! -> $strRecCmd on currentCmd=$currentCommand")
            }
            currentCommand = ""
            return null
        }
    }

    private fun nowGetGlucoseValue(input: ByteArray): Int {
        val curGluc: Int
        val rawGlucose: Long

        // option to use 13 bit mask
        //final boolean thirteen_bit_mask = Pref.getBooleanDefaultFalse("testing_use_thirteen_bit_mask");
        val thirteen_bit_mask = true
        // grep 2 bytes with BG data from input bytearray, mask out 12 LSB bits and rescale for xDrip+
        rawGlucose =
            (input[3 + mNowGlucoseOffset + 1].toLong() and if (thirteen_bit_mask) 0x1F else 0x0F).shl(8) or (input[3 + mNowGlucoseOffset].toLong() and 0xFF)
        Log.i(TAG, "rawGlucose=$rawGlucose, m_nowGlucoseOffset=$mNowGlucoseOffset")

        // rescale
        curGluc = getGlucose(rawGlucose)

        return curGluc
    }

    private var mCurrentTrendIndex: Int = 0
    private var mNowGlucoseOffset: Int = 0


    private fun blockNumberForNowGlucoseData(input: ByteArray): Int {
        var nowGlucoseIndex2 = 0
        var nowGlucoseIndex3 = 0

        nowGlucoseIndex2 = (input[5] and 0x0F).toInt()

        mCurrentTrendIndex = nowGlucoseIndex2

        // calculate byte position in sensor body
        nowGlucoseIndex2 = nowGlucoseIndex2 * 6 + 4

        // decrement index to get the index where the last valid BG reading is stored
        nowGlucoseIndex2 -= 6
        // adjust round robin
        if (nowGlucoseIndex2 < 4)
            nowGlucoseIndex2 = nowGlucoseIndex2 + 96

        // calculate the absolute block number which correspond to trend index
        nowGlucoseIndex3 = 3 + nowGlucoseIndex2 / 8

        // calculate offset of the 2 bytes in the block
        mNowGlucoseOffset = nowGlucoseIndex2 % 8

        Log.i(
            TAG,
            "++++++++currentTrendData: index $mCurrentTrendIndex, block $nowGlucoseIndex3, offset $mNowGlucoseOffset"
        )

        return nowGlucoseIndex3
    }

    private fun sensorAge(input: ByteArray): Int {
        val sensorAge = ((input[3 + 5]).toInt() and 0xFF).shl(8) or (input[3 + 4].toInt() and 0xFF)
        Log.i(TAG, "sensorAge=$sensorAge")

        return sensorAge
    }

    private fun getGlucose(rawGlucose: Long): Int {
        // standard divider for raw Libre data (1000 range)
        return (rawGlucose * 117.64705) as Int
    }

    private fun sendCommand() {
        val value = "cb010000".hexStringToByteArray()
        Log.d(TAG, "sendCommand: $value")
        mWriteCharacteristic.value = value
        Log.d(TAG, "sendCommand: ${mWriteCharacteristic.value.toString()}")
        mGatt.writeCharacteristic(mWriteCharacteristic)
    }

    private fun enableNotifications() {
        val service = mGatt.getService(desiredServiceUUID)
        val characteristic = service.getCharacteristic(desiredReceiveCharacteristicUUID)
        val descriptor = characteristic.getDescriptor(descriptorUUID)
        descriptor.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
        val b = mGatt.writeDescriptor(descriptor)
        Log.d(TAG, "enableNotifications: $b")
    }

    private fun isSensorReady(sensorStatusByte: Byte): Boolean {

        var sensorStatusString = ""
        var ret = false
        val qSSB = sensorStatusByte.toInt()

        when (qSSB) {
            0x01 -> sensorStatusString = "not yet started"
            0x02 -> {
                sensorStatusString = "starting"
                ret = true
            }
            0x03          // status for 14 days and 12 h of normal operation, abbott reader quits after 14 days
            -> {
                sensorStatusString = "ready"
                ret = true
            }
            0x04          // status of the following 12 h, sensor delivers last BG reading constantly
            -> sensorStatusString = "expired"
            0x05          // sensor stops operation after 15d after start
            -> sensorStatusString = "shutdown"
            0x06 -> sensorStatusString = "in failure"
            else -> sensorStatusString = "in an unknown state"
        }// @keencave: to use dead sensor for test
        //                ret = true;
        // @keencave: to use dead sensors for test
        //                ret = true;

        Log.i(TAG, "Sensor status is: $sensorStatusString")


        return ret
    }

    // This function assumes that the UID is starting at place 3, and is 8 bytes long
    fun decodeSerialNumber(input: ByteArray) {

        val uuid = byteArrayOf(0, 0, 0, 0, 0, 0, 0, 0)
        val lookupTable = arrayOf(
            "0",
            "1",
            "2",
            "3",
            "4",
            "5",
            "6",
            "7",
            "8",
            "9",
            "A",
            "C",
            "D",
            "E",
            "F",
            "G",
            "H",
            "J",
            "K",
            "L",
            "M",
            "N",
            "P",
            "Q",
            "R",
            "T",
            "U",
            "V",
            "W",
            "X",
            "Y",
            "Z"
        )
        val uuidShort = byteArrayOf(0, 0, 0, 0, 0, 0, 0, 0)
        var i: Int

        i = 2
        while (i < 8) {
            uuidShort[i - 2] = input[2 + 8 - i]
            i++
        }
        uuidShort[6] = 0x00
        uuidShort[7] = 0x00

        var binary = ""
        var binS = ""
        i = 0
        while (i < 8) {
            binS = String.format(
                "%8s",
                Integer.toBinaryString(((uuidShort[i] and 0xFF.toByte()).toInt())).replace(' ', '0')
            )
            binary += binS
            i++
        }

        var v = "0"
        val pozS = charArrayOf(0.toChar(), 0.toChar(), 0.toChar(), 0.toChar(), 0.toChar())
        i = 0
        while (i < 10) {
            for (k in 0..4) pozS[k] = binary[5 * i + k]
            val value =
                (pozS[0] - '0') * 16 + (pozS[1] - '0') * 8 + (pozS[2] - '0') * 4 + (pozS[3] - '0') * 2 + (pozS[4] - '0') * 1
            v += lookupTable[value]
            i++
        }
        Log.e(TAG, "decodeSerialNumber=$v")
    }

    private fun waitFor(millis: Long) {
        mLock.withLock {
            condition.await(millis, TimeUnit.MILLISECONDS)
        }
    }

    private val HEX_CHARS = "0123456789ABCDEF".toCharArray()

    fun ByteArray.toHex(): String {
        val result = StringBuffer()

        forEach {
            val octet = it.toInt()
            val firstIndex = (octet and 0xF0).ushr(4)
            val secondIndex = octet and 0x0F
            result.append(HEX_CHARS[firstIndex])
            result.append(HEX_CHARS[secondIndex])
        }

        return result.toString()
    }

    fun String.hexStringToByteArray() : ByteArray {

        val result = ByteArray(length / 2)

        for (i in 0 until length step 2) {
            val firstIndex = HEX_CHARS.indexOf(this[i]);
            val secondIndex = HEX_CHARS.indexOf(this[i + 1]);

            val octet = firstIndex.shl(4).or(secondIndex)
            result.set(i.shr(1), octet.toByte())
        }

        return result
    }
}