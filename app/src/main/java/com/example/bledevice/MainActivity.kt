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
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.widget.Toast
import com.example.bledevice.utils.*
import kotlinx.android.synthetic.main.activity_main.*
import java.nio.ByteBuffer
import java.util.*
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock
import kotlin.experimental.and
import com.rits.cloning.Cloner
import okhttp3.*
import java.io.IOException


class MainActivity : AppCompatActivity(), View.OnClickListener {

    private val TAG = "MainActivity"
    private val REQUEST_COARSE_LOCATION = 2
    private var mConnectionState = STATE_DISCONNECTED
    private val desiredTransmitCharacteristicUUID: UUID = UUID.fromString("436AA6E9-082E-4CE8-A08B-01D81F195B24")
    private val desiredReceiveCharacteristicUUID: UUID = UUID.fromString("436A0C82-082E-4CE8-A08B-01D81F195B24")
    private val desiredServiceUUID: UUID = UUID.fromString("436A62C0-082E-4CE8-A08B-01D81F195B24")

    private lateinit var mGatt: BluetoothGatt
    private lateinit var mService: BluetoothGattService
    private lateinit var mWriteCharacteristic: BluetoothGattCharacteristic
    private lateinit var mReadCharacteristic: BluetoothGattCharacteristic

    private var mBondingState: Int = 0
    private val mFullData: ByteArray = ByteArray(344)

    private var mGetNowGlucoseDataCommand: Boolean = false
    private var mGetNowGlucoseDataIndexCommand: Boolean = false
    private var mCommunicationStarted: Boolean = false
    private val GET_DECODE_SERIAL_DELAY = 12 * 3600
    private val GET_SENSOR_AGE_DELAY = 3 * 3600
    private val BLUKON_GETSENSORAGE_TIMER = "blukon-getSensorAge-timer"
    private val BLUKON_DECODE_SERIAL_TIMER = "blukon-decodeSerial-timer"

    private var mBlockNumber: Int = 0
    private var mCurrentBlockNumber: Int = 0
    private var mCurrentOffset: Int = 0
    private var mTimeLastCmdReceived: Long = 0
    private var mPersistentTimeLastBg: Long = 0
    private var mMinutesDiffToLastReading: Int = 0
    private var mGetOlderReading: Boolean = false
    private var mMinutesBack: Int = 0
    private var mTimeLastBg: Long = 0
    private var mCurrentTrendIndex: Int = 0
    private var mNowGlucoseOffset: Int = 0

    private val mLock = ReentrantLock()
    private val condition = mLock.newCondition()
    private val cloner: Cloner = Cloner()

    private var mBluetoothAdapter: BluetoothAdapter? = null
    private var mBluetoothManager: BluetoothManager? = null
    private var mScanning = false
    var mBluetoothGatt: BluetoothGatt? = null
    private lateinit var mHandler: Handler
    private lateinit var mLeDeviceAdapter: LeDeviceListAdapter

    var currentCommand: String = ""

    private lateinit var mClient: OkHttpClient
    private lateinit var mRequest: Request

    companion object {
        const val SCAN_PERIOD: Long = 10000
        const val STATE_DISCONNECTED = 0
        const val STATE_CONNECTING = 1
        const val STATE_CONNECTED = 2
        const val ACTION_GATT_CONNECTED = "com.example.bluetooth.le.ACTION_GATT_CONNECTED"
        const val ACTION_GATT_DISCONNECTED = "com.example.bluetooth.le.ACTION_GATT_DISCONNECTED"
        const val ACTION_GATT_SERVICES_DISCOVERED =
            "com.example.bluetooth.le.ACTION_GATT_SERVICES_DISCOVERED"
        const val ACTION_DATA_AVAILABLE = "com.example.bluetooth.le.ACTION_DATA_AVAILABLE"
        const val EXTRA_DATA = "com.example.bluetooth.le.EXTRA_DATA"
        const val ACTION_DATA_WRITTEN = "com.example.bluetooth.ACTION_DATA_WRITTEN"

        const val WAKEUP_COMMAND = "cb010000"
        const val ACK_ON_WAKEUP_ANSWER = "810a00"
        const val SLEEP_COMMAND = "010c0e00"

        const val GET_PATCH_INFO_COMMAND = "010d0900"

        const val UNKNOWN1_COMMAND = "010d0b00"
        const val UNKNOWN2_COMMAND = "010d0a00"

        const val GET_SENSOR_TIME_COMMAND = "010d0e0127"     // read single block #0x27
        const val GET_NOW_DATA_INDEX_COMMAND = "010d0e0103"  // read single block #0x03
        const val READ_SINGLE_BLOCK_COMMAND_PREFIX = "010d0e010"
        const val READ_SINGLE_BLOCK_COMMAND_PREFIX_SHORT = "010d0e01"
        const val GET_HISTORIC_DATA_COMMAND_ALL_BLOCKS = "010d0f02002b" // read all blocks from 0 to 0x2B

        const val PATCH_INFO_RESPONSE_PREFIX = "8bd9"
        const val SINGLE_BLOCK_INFO_RESPONSE_PREFIX = "8bde"
        const val MULTIPLE_BLOCK_RESPONSE_INDEX = "8bdf"
        const val BLUCON_ACK_RESPONSE = "8b0a00"
        const val BLUCON_NAK_RESPONSE_PREFIX = "8b1a02"

        const val BLUCON_UNKNOWN1_COMMAND_RESPONSE = "8bdb0101041711"
        const val BLUCON_UNKNOWN2_COMMAND_RESPONSE = "8bdaaa"
        const val BLUCON_UNKNOWN2_COMMAND_RESPONSE_BATTERY_LOW = "8bda02"

        const val BLUCON_NAK_RESPONSE_ERROR09 = "8b1a020009"
        const val BLUCON_NAK_RESPONSE_ERROR14 = "8b1a020014"

        const val PATCH_NOT_FOUND_RESPONSE = "8b1a02000f"
        const val PATCH_READ_ERROR = "8b1a020011"

        // we guess that this two commands indicate a low battery state
        const val BLUCON_BATTERY_LOW_INDICATION1 = "cb020000"
        const val BLUCON_BATTERY_LOW_INDICATION2 = "cbdb0000"

        const val POSITION_OF_SENSOR_STATUS_BYTE = 17
        const val MmollToMgdl = 18.0182
        const val MgdlToMmoll = 1 / MmollToMgdl

        lateinit var mContext: Context
    }


    private val mLeScanCallback = BluetoothAdapter.LeScanCallback { device, _, _ ->
        runOnUiThread {
            mLeDeviceAdapter.addDevice(device)
            mLeDeviceAdapter.notifyDataSetChanged()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        setSupportActionBar(findViewById(R.id.my_toolbar))
        mContext = this

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            if (checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
                requestPermissions(arrayOf(Manifest.permission.ACCESS_COARSE_LOCATION), REQUEST_COARSE_LOCATION)
            }
        }

        mBluetoothManager = getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager?
        mBluetoothAdapter = mBluetoothManager?.adapter
        mHandler = Handler()
        mClient = OkHttpClient()
        mLeDeviceAdapter = LeDeviceListAdapter(this)
        listview.adapter = mLeDeviceAdapter
        listview.visibility = View.GONE
        listview.setOnItemClickListener { _, _, position, _ ->
            val device: BluetoothDevice = mLeDeviceAdapter.getDevice(position)
            mBluetoothGatt = device
                .connectGatt(this, false, mGattCallback)
        }
        searchButton.isEnabled = true
        searchButton.setOnClickListener(this)
        disconnectButton.isEnabled = false
        disconnectButton.setOnClickListener {
            close()
        }

        sendButton.isEnabled = true

        sendButton.setOnClickListener {
            val httpUrl = HttpUrl.Builder()
                .scheme("https")
                .host(Pref.getString("IP", "83.149.249.52"))
                .build()
            val urlBuilder = httpUrl?.newBuilder()
            urlBuilder?.addQueryParameter("id", Pref.getString("Client", "0"))
            urlBuilder?.addQueryParameter("time", Pref.getString("Time", "0"))
            urlBuilder?.addQueryParameter("date", Pref.getString("Date", "0"))
            urlBuilder?.addQueryParameter("glucose", Pref.getString("Glucose", "0"))
            urlBuilder?.addQueryParameter("meal", Pref.getString("Meal", "0"))
            urlBuilder?.addQueryParameter("basal", Pref.getString("Basal", "0"))
            urlBuilder?.addQueryParameter("bolus", Pref.getString("Bolus", "0"))
            val url = urlBuilder?.build().toString()

            mRequest = Request.Builder()
                .url(url)
                .build()
            showText(url)

            mClient.newCall(mRequest).enqueue(object : Callback {
                override fun onResponse(call: Call, response: Response) {
                    if (!response.isSuccessful) {
                        Toast.makeText(MainActivity.mContext.applicationContext, "Values hasn't been send", Toast.LENGTH_SHORT).show()
                        throw IOException("Unexpected code $response")
                    } else {
                        Toast.makeText(MainActivity.mContext.applicationContext, "Values has been send", Toast.LENGTH_SHORT).show()
                        showText(response.body()!!.string())
                    }
                }

                override fun onFailure(call: Call, e: IOException) {
                    e.printStackTrace()
                }
            })
        }

    }

    override fun onCreateOptionsMenu(menu: Menu?): Boolean {
        menuInflater.inflate(R.menu.menu, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem?): Boolean = when (item?.itemId) {
        R.id.settings -> {
            startActivity(Intent(this, SettingsActivity::class.java))
            true
        }
        else -> {
            super.onOptionsItemSelected(item)
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
        listview.visibility = View.VISIBLE
        mScanning = true
        scanDevices(mScanning)
    }


    private fun setSerialDataToTransmitterRawData(buffer: ByteArray) {
        val reply = decodeBlukonPacket(buffer)
        if (reply != null) {
            sendBtMessage(reply)
        }
    }

    private fun sendBtMessage(reply: ByteArray?): Boolean {
        return sendBtMessage(JoHH.bArrayAsBuffer(reply))
    }

    @Synchronized
    private fun sendBtMessage(message: ByteBuffer?): Boolean {
        Log.d(TAG, "sendBtMessage: entered")
        val value = message!!.array()
        if (mWriteCharacteristic != mReadCharacteristic) {
            return writeChar(mWriteCharacteristic, value)
        }
        return writeChar(mReadCharacteristic, value)
    }

    @Synchronized
    private fun writeChar(localmCharacteristic: BluetoothGattCharacteristic, value: ByteArray?): Boolean {
        localmCharacteristic.value = value
        val result = mGatt.writeCharacteristic(localmCharacteristic)
        if (!result) {
            Log.d(TAG, "writeChar: Error writing characteristic")
            val resendCharacteristic: BluetoothGattCharacteristic = cloner.shallowClone(localmCharacteristic)
            waitFor(1000)
            JoHH.runOnUiThreadDelayed({
                kotlin.run {
                    val newResult = mGatt.writeCharacteristic(resendCharacteristic)
                    if (!newResult) Log.d(TAG, "writeChar: Error writing char on 2nd try")
                    else Log.d(TAG, "writeChar: Succeeded writing char on 2nd try")
                }
            }, 500)
        } else {
            Log.d(TAG, "writeChar: SUCCESSFUL")
        }
        return result
    }


    @Synchronized
    fun decodeBlukonPacket(buffer: ByteArray?): ByteArray? {
        var cmdFound = 0
        var gotLowBat: Boolean? = false
        var getHistoricReadings: Boolean? = false

        if (buffer == null) {
            Log.e(TAG, "null buffer passed to decodeBlukonPacket")
            return null
        }

        mTimeLastCmdReceived = JoHH.tsl()

        // calculate time delta to last valid reading
        mPersistentTimeLastBg = PersistentStore.getLong("blukon-time-of-last-reading")
        mMinutesDiffToLastReading = (((JoHH.tsl() - mPersistentTimeLastBg) / 1000 + 30) / 60).toInt()
        Log.i(
            TAG,
            "m_minutesDiffToLastReading=$mMinutesDiffToLastReading, last reading: " + JoHH.dateTimeText(
                mPersistentTimeLastBg
            )
        )

        // Get history if the last reading is older than we can reasonably backfill
        if (Pref.getBooleanDefaultFalse("retrieve_blukon_history") && mPersistentTimeLastBg > 0 && mMinutesDiffToLastReading > 17) {
            getHistoricReadings = true
        }

        val strRecCmd = buffer.toHex().toLowerCase()
        Log.i(TAG, "Blukon data: $strRecCmd")

        /*
         * step 1: have we got a wakeUp command from blucon?
         */
        if (strRecCmd.equals(WAKEUP_COMMAND, ignoreCase = true)) {
            Log.i(TAG, "Reset currentCommand")
            currentCommand = ""
            cmdFound = 1
            mCommunicationStarted = true
        }

        // BluconACKResponse will come in two different situations
        // 1) after we have sent an ackwakeup command
        // 2) after we have a sleep command
        /*
         * step 4 / step 11: receive ACK on wakeup or after sending sleep command
         */
        if (strRecCmd.startsWith(BLUCON_ACK_RESPONSE)) {
            cmdFound = 1
            Log.i(TAG, "Got ACK")

            if (currentCommand.startsWith(ACK_ON_WAKEUP_ANSWER)) {//ACK sent
                //ack received

                currentCommand = UNKNOWN1_COMMAND
                Log.i(TAG, "getUnknownCmd1: $currentCommand")

            } else {
                Log.i(TAG, "Got sleep ack, resetting initialstate!")
                currentCommand = ""
            }
        }

        if (strRecCmd.startsWith(BLUCON_NAK_RESPONSE_PREFIX)) {
            cmdFound = 1
            Log.e(TAG, "Got NACK on cmd=" + currentCommand + " with error=" + strRecCmd.substring(6))

            if (strRecCmd.startsWith(BLUCON_NAK_RESPONSE_ERROR14)) {
                Log.e(TAG, "Timeout: please wait 5min or push button to restart!")
                showText("Timeout: please wait 5min or push button to restart!")
            }

            if (strRecCmd.startsWith(PATCH_NOT_FOUND_RESPONSE)) {
                Log.e(TAG, "Libre sensor has been removed!")
                showText("Libre sensor has been removed!")
            }

            if (strRecCmd.startsWith(PATCH_READ_ERROR)) {
                Log.e(
                    TAG,
                    "Patch read error.. please check the connectivity and re-initiate... or maybe battery is low?"
                )
                showText("Patch read error.. please check the connectivity and re-initiate... or maybe battery is low?")
                Pref.setInt("bridge_battery", 1)
                gotLowBat = true
            }

            if (strRecCmd.startsWith(BLUCON_NAK_RESPONSE_ERROR09)) {
                //Log.e(TAG, "");
            }

            mGetNowGlucoseDataCommand = false
            mGetNowGlucoseDataIndexCommand = false

            currentCommand = SLEEP_COMMAND
            Log.i(TAG, "Send sleep cmd")
            mCommunicationStarted = false


            JoHH.clearRatelimit(BLUKON_GETSENSORAGE_TIMER)// set to current time to force timer to be set back
        }

        /*
         * step 2: process getPatchInfo
         */
        if (currentCommand == "" && strRecCmd.equals(WAKEUP_COMMAND, ignoreCase = true)) {
            cmdFound = 1
            Log.i(TAG, "wakeup received")

            //must be first cmd to be sent otherwise get NACK!
            if (JoHH.ratelimit("blukon-request_patch_info", 1)) {
                currentCommand = GET_PATCH_INFO_COMMAND
            }
            Log.i(TAG, "getPatchInfo")
            /*
         * step 3: analyse received patch info, decode serial number and check sensorStatus
         */
        } else if (currentCommand.startsWith(GET_PATCH_INFO_COMMAND) /*getPatchInfo*/ && strRecCmd.startsWith(
                PATCH_INFO_RESPONSE_PREFIX
            )
        ) {
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

            if (isSensorReady(buffer[POSITION_OF_SENSOR_STATUS_BYTE])) {
                currentCommand = ACK_ON_WAKEUP_ANSWER
                Log.i(TAG, "Send ACK")
            } else {
                Log.e(TAG, "Sensor is not ready, stop!")
                showText("Sensor is not ready, stop!")
                currentCommand = SLEEP_COMMAND
                Log.i(TAG, "Send sleep cmd")
                mCommunicationStarted = false
            }

            /*
         * step 5: send unknownCommand1 as otherwise communication errors will occur
         */
        } else if (currentCommand.startsWith(UNKNOWN1_COMMAND) /*getUnknownCmd1*/ && strRecCmd.startsWith("8bdb")) {
            cmdFound = 1
            Log.i(TAG, "gotUnknownCmd1 (010d0b00): $strRecCmd")

            if (strRecCmd != BLUCON_UNKNOWN1_COMMAND_RESPONSE) {
                Log.e(TAG, "gotUnknownCmd1 (010d0b00): $strRecCmd")
            }

            currentCommand = UNKNOWN2_COMMAND
            Log.i(TAG, "getUnknownCmd2 $currentCommand")

            /*
         * step 6: send unknownCommand2 as otherwise communication errors will occur
         */
        } else if (currentCommand.startsWith(UNKNOWN2_COMMAND) /*getUnknownCmd2*/ && strRecCmd.startsWith("8bda")) {
            cmdFound = 1
            Log.i(TAG, "gotUnknownCmd2 (010d0a00): $strRecCmd")

            if (strRecCmd != BLUCON_UNKNOWN2_COMMAND_RESPONSE) {
                Log.e(TAG, "gotUnknownCmd2 (010d0a00): $strRecCmd")
            }

            if (strRecCmd == BLUCON_UNKNOWN2_COMMAND_RESPONSE_BATTERY_LOW) {
                Log.e(TAG, "gotUnknownCmd2: is maybe battery low????")
                Pref.setInt("bridge_battery", 5)
                gotLowBat = true
            }

            if (JoHH.pratelimit(BLUKON_GETSENSORAGE_TIMER, GET_SENSOR_AGE_DELAY)) {
                currentCommand = GET_SENSOR_TIME_COMMAND
                Log.i(TAG, "getSensorAge")
            } else {
                if (Pref.getBooleanDefaultFalse("external_blukon_algorithm") || getHistoricReadings!!) {
                    // Send the command to getHistoricData (read all blcoks from 0 to 0x2b)
                    Log.i(TAG, "getHistoricData (2)")
                    currentCommand = GET_HISTORIC_DATA_COMMAND_ALL_BLOCKS
                    mBlockNumber = 0
                } else {
                    currentCommand = GET_NOW_DATA_INDEX_COMMAND
                    mGetNowGlucoseDataIndexCommand =
                        true//to avoid issue when gotNowDataIndex cmd could be same as getNowGlucoseData (case block=3)
                    Log.i(TAG, "getNowGlucoseDataIndexCommand")
                }
            }

            /*
         * step 7: calculate sensorAge from sensors FRAM copy
         */
        } else if (currentCommand.startsWith(GET_SENSOR_TIME_COMMAND) /*getSensorAge*/ && strRecCmd.startsWith(
                SINGLE_BLOCK_INFO_RESPONSE_PREFIX
            )
        ) {
            cmdFound = 1
            Log.i(TAG, "SensorAge received")

            val sensorAge = sensorAge(buffer)
            val sensorAgeDays = TimeUnit.SECONDS.toDays(sensorAge.toLong())
            showText("Sensor Age: $sensorAgeDays")

            if (Pref.getBooleanDefaultFalse("external_blukon_algorithm") || getHistoricReadings!!) {
                // Send the command to getHistoricData (read all blcoks from 0 to 0x2b)
                Log.i(TAG, "getHistoricData (3)")
                currentCommand = GET_HISTORIC_DATA_COMMAND_ALL_BLOCKS
                mBlockNumber = 0
            } else {
                /* LibreAlarmReceiver.CalculateFromDataTransferObject, called when processing historical data,
                 * expects the sensor age not to be updated yet, so only update the sensor age when not retrieving history.
                 */
                if (sensorAge in 1..199999) {
                    Pref.setInt("nfc_sensor_age", sensorAge)//in min
                }
                currentCommand = GET_NOW_DATA_INDEX_COMMAND
                mGetNowGlucoseDataIndexCommand =
                    true//to avoid issue when gotNowDataIndex cmd could be same as getNowGlucoseData (case block=3)
                Log.i(TAG, "getNowGlucoseDataIndexCommand")
            }

            /*
         * step 8: determine trend or historic data index
         */
        } else if (currentCommand.startsWith(GET_NOW_DATA_INDEX_COMMAND) && mGetNowGlucoseDataIndexCommand
            && strRecCmd.startsWith(SINGLE_BLOCK_INFO_RESPONSE_PREFIX)
        ) {
            cmdFound = 1

            // check time range for valid backfilling
            mGetOlderReading = if (mMinutesDiffToLastReading > 7 && mMinutesDiffToLastReading < 8 * 60) {
                Log.i(TAG, "start backfilling")
                true
            } else {
                false
            }
            // get index to current BG reading
            mCurrentBlockNumber = blockNumberForNowGlucoseData(buffer)
            mCurrentOffset = mNowGlucoseOffset
            // time diff must be > 5,5 min and less than the complete trend buffer
            if (!mGetOlderReading) {
                currentCommand =
                    READ_SINGLE_BLOCK_COMMAND_PREFIX + Integer.toHexString(mCurrentBlockNumber)//getNowGlucoseData
                mNowGlucoseOffset = mCurrentOffset
                Log.i(TAG, "getNowGlucoseData")
            } else {
                mMinutesBack = mMinutesDiffToLastReading
                var delayedTrendIndex = mCurrentTrendIndex
                // ensure to have min 3 mins distance to last reading to avoid doible draws (even if they are distict)
                when {
                    mMinutesBack > 17 -> mMinutesBack = 15
                    mMinutesBack > 12 -> mMinutesBack = 10
                    mMinutesBack > 7 -> mMinutesBack = 5
                }
                Log.i(TAG, "read $mMinutesBack mins old trend data")
                for (i in 0 until mMinutesBack) {
                    if (--delayedTrendIndex < 0)
                        delayedTrendIndex = 15
                }
                val delayedBlockNumber = blockNumberForNowGlucoseDataDelayed(delayedTrendIndex)
                currentCommand =
                    READ_SINGLE_BLOCK_COMMAND_PREFIX + Integer.toHexString(delayedBlockNumber)//getNowGlucoseData

                Log.i(TAG, "getNowGlucoseData backfilling")
            }
            mGetNowGlucoseDataIndexCommand = false
            mGetNowGlucoseDataCommand = true

            /*
         * step 9: calculate fro current index the block number next to read
         */
        } else if (currentCommand.startsWith(READ_SINGLE_BLOCK_COMMAND_PREFIX_SHORT) && mGetNowGlucoseDataCommand
            && strRecCmd.startsWith(SINGLE_BLOCK_INFO_RESPONSE_PREFIX)
        ) {

            Log.d(TAG, "Before Saving data: + currentCommand = $currentCommand")
            val blockId = currentCommand.substring(READ_SINGLE_BLOCK_COMMAND_PREFIX_SHORT.length)
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
            showText("Current glucose: $currentGlucose")

            if (!mGetOlderReading) {

                mTimeLastBg = now

                PersistentStore.setLong("blukon-time-of-last-reading", mTimeLastBg)
                Log.i(TAG, "time of current reading: " + JoHH.dateTimeText(mTimeLastBg))
                showText("time of last reading: ${JoHH.dateTimeText(mPersistentTimeLastBg)}")
                showText("time of current reading: " + JoHH.dateTimeText(mTimeLastBg))

                /*
                 * step 10: send sleep command
                 */
                currentCommand = SLEEP_COMMAND
                Log.i(TAG, "Send sleep cmd")
                mCommunicationStarted = false

                mGetNowGlucoseDataCommand = false
            } else {
                Log.i(TAG, "bf: processNewTransmitterData with delayed timestamp of $mMinutesBack min")

                mMinutesBack -= 5
                if (mMinutesBack < 5) {
                    mGetOlderReading = false
                }
                Log.i(TAG, "bf: calculate next trend buffer with $mMinutesBack min timestamp")
                var delayedTrendIndex = mCurrentTrendIndex
                for (i in 0 until mMinutesBack) {
                    if (--delayedTrendIndex < 0)
                        delayedTrendIndex = 15
                }
                val delayedBlockNumber = blockNumberForNowGlucoseDataDelayed(delayedTrendIndex)
                currentCommand =
                    READ_SINGLE_BLOCK_COMMAND_PREFIX + Integer.toHexString(delayedBlockNumber)//getNowGlucoseData
                Log.i(TAG, "bf: read next block: $currentCommand")


            }
        } else if ((currentCommand.startsWith(GET_HISTORIC_DATA_COMMAND_ALL_BLOCKS) /*getHistoricData */
                    || currentCommand.isEmpty() && mBlockNumber > 0)
            && strRecCmd.startsWith(MULTIPLE_BLOCK_RESPONSE_INDEX)
        ) {
            cmdFound = 1
            handlegetHistoricDataResponse(buffer)
        } else if (strRecCmd.startsWith(BLUCON_BATTERY_LOW_INDICATION1)) {
            cmdFound = 1
            Log.e(TAG, "is bridge battery low????!")
            Pref.setInt("bridge_battery", 3)
            gotLowBat = true
        } else if (strRecCmd.startsWith(BLUCON_BATTERY_LOW_INDICATION2)) {
            cmdFound = 1
            Log.e(TAG, "is bridge battery really low????!")
            Pref.setInt("bridge_battery", 2)
            gotLowBat = true
        }

        if ((!gotLowBat!!)) {
            Pref.setInt("bridge_battery", 100)
        }


        return if (currentCommand.isNotEmpty() && cmdFound == 1) {
            Log.i(TAG, "Sending reply: $currentCommand")
            hexToBytes(currentCommand)
        } else {
            if (cmdFound == 0) {
                Log.e(TAG, "***COMMAND NOT FOUND! -> $strRecCmd on currentCmd=$currentCommand")
                showText("***COMMAND NOT FOUND! -> $strRecCmd on currentCmd=$currentCommand")
            }
            currentCommand = ""
            null
        }

    }

    private fun handlegetHistoricDataResponse(buffer: ByteArray) {
        Log.e(TAG, "recieved historic data, m_block_number = $mBlockNumber")
        // We are looking for 43 blocks of 8 bytes.
        // The bluekon will send them as 21 blocks of 16 bytes, and the last one of 8 bytes.
        // The packet will look like "0x8b 0xdf 0xblocknumber 0x02 DATA" (so data starts at place 4)
        if (mBlockNumber > 42) {
            Log.e(TAG, "recieved historic data, but block number is too big $mBlockNumber")
            return
        }

        val len = buffer.size - 4
        Log.e(TAG, "len = " + len + " " + len + " blocknum " + buffer[2])

        if (buffer[2].toInt() != mBlockNumber) {
            Log.e(
                TAG,
                "We have recieved a bad block number buffer[2] = " + buffer[2] + " m_blockNumber = " + mBlockNumber
            )
            return
        }
        if (8 * mBlockNumber + len > mFullData.size) {
            Log.e(
                TAG, "We have recieved too much data  m_blockNumber = " + mBlockNumber + " len = " + len +
                        " m_full_data.length = " + mFullData.size
            )
            return
        }

        System.arraycopy(buffer, 4, mFullData, 8 * mBlockNumber, len)
        mBlockNumber += len / 8

        if (mBlockNumber >= 43) {
            val now = JoHH.tsl()
            currentCommand = SLEEP_COMMAND
            Log.i(TAG, "Send sleep cmd")
            mCommunicationStarted = false

            PersistentStore.setLong("blukon-time-of-last-reading", now)
            Log.i(TAG, "time of current reading: " + JoHH.dateTimeText(now))
        } else {
            currentCommand = ""
        }
    }

    private fun nowGetGlucoseValue(input: ByteArray): Int {
        val rawGlucose: Long

        // option to use 13 bit mask
        //final boolean thirteen_bit_mask = Pref.getBooleanDefaultFalse("testing_use_thirteen_bit_mask");
        // grep 2 bytes with BG data from input bytearray, mask out 12 LSB bits and rescale for xDrip+
        rawGlucose =
            (input[3 + mNowGlucoseOffset + 1].toLong() and 0x1F).shl(8) or (input[3 + mNowGlucoseOffset].toLong() and 0xFF)
        Log.i(TAG, "rawGlucose=$rawGlucose, m_nowGlucoseOffset=$mNowGlucoseOffset")

        // rescale
        //curGluc = getGlucose(rawGlucose)

        return rawGlucose.toInt()
    }

    private fun blockNumberForNowGlucoseData(input: ByteArray): Int {
        var nowGlucoseIndex2: Int = (input[5] and 0x0F).toInt()
        val nowGlucoseIndex3: Int

        mCurrentTrendIndex = nowGlucoseIndex2

        // calculate byte position in sensor body
        nowGlucoseIndex2 = nowGlucoseIndex2 * 6 + 4

        // decrement index to get the index where the last valid BG reading is stored
        nowGlucoseIndex2 -= 6
        // adjust round robin
        if (nowGlucoseIndex2 < 4)
            nowGlucoseIndex2 += 96

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

    private fun blockNumberForNowGlucoseDataDelayed(delayedIndex: Int): Int {
        var ngi2: Int = delayedIndex * 6 + 4
        val ngi3: Int

        // calculate byte offset in libre FRAM

        ngi2 -= 6
        if (ngi2 < 4)
            ngi2 += 96

        // calculate the block number where to get the BG reading
        ngi3 = 3 + ngi2 / 8

        // calculate the offset in the block
        mNowGlucoseOffset = ngi2 % 8
        Log.i(TAG, "++++++++backfillingTrendData: index $delayedIndex, block $ngi3, offset $mNowGlucoseOffset")

        return ngi3
    }

    private fun sensorAge(input: ByteArray): Int {
        val sensorAge = ((input[3 + 5]).toInt() and 0xFF).shl(8) or (input[3 + 4].toInt() and 0xFF)
        Log.i(TAG, "sensorAge=$sensorAge")

        return sensorAge
    }

    private fun getGlucose(rawGlucose: Long): Int {
        // standard divider for raw Libre data (1000 range)
        return (rawGlucose * 117.64705).toInt()
    }

    private fun sendCommand() {
        val value = "8bde".toByteArray(Charsets.UTF_8)
        Log.d(TAG, "sendCommand: $value")
        mWriteCharacteristic.value = value
        mGatt.writeCharacteristic(mWriteCharacteristic)
    }

    private fun isSensorReady(sensorStatusByte: Byte): Boolean {

        val sensorStatusString: String
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
        }

        Log.i(TAG, "Sensor status is: $sensorStatusString")


        return ret
    }

    private fun waitFor(millis: Long) {
        mLock.withLock {
            condition.await(millis, TimeUnit.MILLISECONDS)
        }
    }

    @Synchronized
    fun close() {
        if (mGatt != null)
            mGatt.close()
        mConnectionState = STATE_DISCONNECTED
        changeUI(connected = false)
    }

    private fun changeUI(connected: Boolean) {
        Handler(Looper.getMainLooper()).post {
            disconnectButton.isEnabled = connected
            sendButton.isEnabled = !connected
            when (connected) {
                true -> {
                    showText(getString(R.string.connected))
                    listview.visibility = View.GONE
                }
                false -> {
                    showText(getString(R.string.disconnected))
                    listview.visibility = View.VISIBLE
                }
            }

        }
    }

    private fun showText(text: String) {
        runOnUiThread {
            uuid_textView.text = "" + uuid_textView.text + "\n" + text
        }

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
                    Pref.setString("Mac", mGatt.device.address)
                    broadcastUpdate(intentAction)
                    Handler(Looper.getMainLooper()).postDelayed({
                        val ans: Boolean = gatt.discoverServices()
                        changeUI(true)
                        Log.d(TAG, "Connected to GATT server $ans.")
                    }, 1000)
                    Log.d(TAG, "Connected to GATT server.")
                }
                BluetoothProfile.STATE_DISCONNECTED -> {
                    intentAction = ACTION_GATT_DISCONNECTED
                    mConnectionState = STATE_DISCONNECTED
                    changeUI(false)
                    showText(getString(R.string.disconnected))
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
                    Log.d(TAG, "onCharacteristicWrite: OK")
                }
                else -> {
                    Log.d(TAG, "onCharacteristicWrite: Failed :C")
                }
            }
        }

        override fun onCharacteristicChanged(gatt: BluetoothGatt?, characteristic: BluetoothGattCharacteristic?) {
            Log.d(TAG, "onCharacteristicChanged: ${characteristic!!.value}")
            val data = characteristic.value
            if (data != null && data.isNotEmpty()) {
                setSerialDataToTransmitterRawData(data)
            }
        }

        private fun broadcastUpdate(action: String) {
            val intent = Intent(action)
            sendBroadcast(intent)
        }
    }
}