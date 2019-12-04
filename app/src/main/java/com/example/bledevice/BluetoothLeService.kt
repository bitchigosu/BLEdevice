package com.example.bledevice

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.bluetooth.*
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.content.Context
import android.content.Intent
import android.os.*
import android.support.v4.app.NotificationCompat
import android.util.Log
import com.example.bledevice.utils.*
import com.rits.cloning.Cloner
import kotlinx.coroutines.*
import okhttp3.*
import java.io.IOException
import java.nio.ByteBuffer
import java.text.DateFormat
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.TimeUnit
import kotlin.experimental.and

private val TAG = BluetoothLeService::class.java.simpleName

class BluetoothLeService : Service() {

    private var bondingState: Int = 0
    private var bluetoothDeviceAddress: String? = null
    private var connectionState = STATE_DISCONNECTED
    private val bluetoothAdapter: BluetoothAdapter? by lazy(LazyThreadSafetyMode.NONE) {
        val bluetoothManager = getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        bluetoothManager.adapter
    }
    private val BluetoothAdapter.isDisabled: Boolean
        get() = !isEnabled
    private lateinit var bluetoothGatt: BluetoothGatt
    private lateinit var bleService: BluetoothGattService
    private lateinit var writeCharacteristic: BluetoothGattCharacteristic
    private lateinit var readCharacteristic: BluetoothGattCharacteristic

    private lateinit var okHttpClient: OkHttpClient
    private lateinit var request: Request

    private val mMessenger = Messenger(InternalHandler())
    private var mMainActivityMessenger: Messenger? = null

    internal inner class InternalHandler : Handler() {
        override fun handleMessage(msg: Message) {
            when (msg.what) {
                CONNECT -> {
                    mMainActivityMessenger = msg.replyTo
                    connect(msg.data.getString("address")!!)
                }
                DISCONNECT -> {
                    disconnect()
                }
                SEARCH -> {
                    mMainActivityMessenger = msg.replyTo
                    startScan()
                }
                SEND_DATA -> {
                    sendData()
                }
                else -> super.handleMessage(msg)
            }
        }
    }

    private val cloner: Cloner = Cloner()

    private val localBinder = LocalBinder()

    inner class LocalBinder : Binder() {
        val service: BluetoothLeService
            get() = this@BluetoothLeService
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel();
        startForeground(NOTIFICATION_ID, createNotification().build())
        okHttpClient = OkHttpClient()
        Log.d(TAG, "onCreate() called");
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d(TAG, "onStartCommand() called");
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = mMessenger.binder
    override fun onUnbind(intent: Intent?): Boolean {
        close()
        return super.onUnbind(intent)
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID, "Channel name",
                NotificationManager.IMPORTANCE_DEFAULT
            )

            channel.description = "CHANNEL DESCRIPTION"
            val manager = getSystemService(NotificationManager::class.java)

            manager?.createNotificationChannel(channel)
        }
    }

    private fun createNotification(): NotificationCompat.Builder {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle("BLE service")
            .setOnlyAlertOnce(true)
    }

    private fun close() {
        bluetoothGatt?.close()
        connectionState = STATE_DISCONNECTED
        sendMessageUpdateUI(false)
    }

    private fun startScan() {
        bluetoothAdapter?.takeIf { it.isDisabled }?.apply {
            sendMessageEnableBT()
        }
        bluetoothAdapter?.takeIf { it.isEnabled }?.apply {
            scanDevices(true)
        }
    }

    private val gattCallback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(gatt: BluetoothGatt?, status: Int, newState: Int) {
            bluetoothGatt = gatt!!
            val intentAction: String
            when (newState) {
                BluetoothProfile.STATE_CONNECTED -> {
                    intentAction = ACTION_GATT_CONNECTED
                    connectionState = STATE_CONNECTED
                    Pref.setString(
                        "Mac",
                        bluetoothGatt.device?.address.toString().replace(":", "_")
                    )
                    broadcastUpdate(intentAction)
                    GlobalScope.launch(Dispatchers.IO) {
                        delay(1000)
                        val ans: Boolean = gatt.discoverServices()
                        Log.d(TAG, "Connected to GATT server $ans.")
                        sendMessageUpdateUI(true)
                        Log.d(TAG, "Connected to GATT server.")
                    }
                }

                BluetoothProfile.STATE_DISCONNECTED -> {
                    intentAction = ACTION_GATT_DISCONNECTED
                    connectionState = STATE_DISCONNECTED
                    broadcastUpdate(intentAction)
                    sendMessageUpdateUI(false)
                    sendMessageShowText(getString(R.string.disconnected))
                    Log.d(TAG, "Disconnected from GATT server.")
                }
            }
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt?, status: Int) {
            bluetoothGatt = gatt!!
            bondingState = bluetoothGatt.device.bondState
            if (bondingState != BluetoothDevice.BOND_BONDED) {
                bluetoothGatt.device.createBond()
                runBlocking {
                    delay(1000)
                }
                bondingState = bluetoothGatt.device.bondState
                if (bondingState != BluetoothDevice.BOND_BONDED) {
                    Log.d(TAG, "onServicesDiscovered: Pairing appeared to fail")
                }
            } else {
                Log.d(TAG, "onServicesDiscovered: Device is already bonded")
            }

            bleService = gatt.getService(desiredServiceUUID)
            writeCharacteristic = bleService.getCharacteristic(desiredTransmitCharacteristicUUID)
            readCharacteristic = bleService.getCharacteristic(desiredReceiveCharacteristicUUID)

            val charaProp = readCharacteristic.properties
            if ((charaProp and BluetoothGattCharacteristic.PROPERTY_NOTIFY) > 0) {
                Log.d(TAG, "onServicesDiscovered: Setting notification on characteristic")
                val result = bluetoothGatt.setCharacteristicNotification(readCharacteristic, true)
                if (!result) Log.d(
                    TAG,
                    "onServicesDiscovered: Failed seeting notification on blukon"
                )
            } else {
                Log.d(TAG, "onServicesDiscovered: Unusual error")
            }
            bluetoothGatt.readCharacteristic(readCharacteristic)
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

        override fun onCharacteristicChanged(
            gatt: BluetoothGatt?,
            characteristic: BluetoothGattCharacteristic?
        ) {
            Log.d(TAG, "onCharacteristicChanged: ${characteristic!!.value}")
            val data = characteristic.value
            if (data != null && data.isNotEmpty()) {
                setSerialDataToTransmitterRawData(data)
            }
        }
    }

    private fun broadcastUpdate(action: String) {
        val intent = Intent(action)
        sendBroadcast(intent)
    }

    fun connect(address: String): Boolean {
        val device = bluetoothAdapter?.getRemoteDevice(address) ?: return false
        bluetoothGatt = device.connectGatt(this, false, gattCallback)
        bluetoothDeviceAddress = address
        connectionState = STATE_DISCONNECTED
        return true
    }

    fun disconnect() {
        if (bluetoothAdapter == null || bluetoothGatt == null) {
            return
        }
        bluetoothGatt?.disconnect()
        stopForeground(true)
    }

    private fun scanDevices(enable: Boolean) {
        when (enable) {
            true -> {
                GlobalScope.launch(Dispatchers.IO) {
                    delay(SCAN_PERIOD)
                    bluetoothAdapter?.bluetoothLeScanner?.stopScan(scanCallback)
                }
                bluetoothAdapter?.bluetoothLeScanner?.startScan(scanCallback)
            }
            else -> {
                bluetoothAdapter?.bluetoothLeScanner?.stopScan(scanCallback)
            }
        }
    }

    private fun sendMessageEnableBT() {
        val message = Message.obtain(null, REQUEST_ENABLE_BT)
        try {
            mMainActivityMessenger!!.send(message)
        } catch (e: RemoteException) {
            e.printStackTrace()
        }
    }

    private fun sendMessageAddDevice(device: BluetoothDevice) {
        val bundle = Bundle()
        bundle.putParcelable("device", device)
        val message = Message.obtain(null, ADD_DEVICE)
        message.data = bundle

        try {
            mMainActivityMessenger!!.send(message)
        } catch (e: RemoteException) {
            e.printStackTrace()
        }
    }

    private fun sendMessageShowText(text: String) {
        val bundle = Bundle()
        bundle.putString("text", text)
        val message = Message.obtain(null, SHOW_TEXT)
        message.data = bundle

        try {
            mMainActivityMessenger!!.send(message)
        } catch (e: RemoteException) {
            e.printStackTrace()
        }
    }

    private fun sendMessageUpdateUI(connected: Boolean) {
        val bundle = Bundle()
        bundle.putBoolean("changeUI", connected)
        val message = Message.obtain(null, CHANGE_UI)
        message.data = bundle

        try {
            mMainActivityMessenger!!.send(message)
        } catch (e: RemoteException) {
            e.printStackTrace()
        }
    }


    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult?) {
            sendMessageUpdateUI(false)
            sendMessageAddDevice(result?.device!!)
        }

        override fun onScanFailed(errorCode: Int) {
            println("Scan failed")
        }

        override fun onBatchScanResults(results: MutableList<ScanResult>?) {
            super.onBatchScanResults(results)
        }
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

    private fun sendBtMessage(message: ByteBuffer?): Boolean {
        Log.d(TAG, "sendBtMessage: entered")
        val value = message!!.array()
        if (writeCharacteristic != readCharacteristic) {
            return writeChar(writeCharacteristic, value)
        }
        return writeChar(readCharacteristic, value)
    }

    private var timeLastCmdReceived: Long = 0
    private var persistentTimeLastBg: Long = 0
    private var minutesDiffToLastReading: Int = 0
    private var currentCommand: String = ""
    private var communicationStarted: Boolean = false
    private var getNowGlucoseDataCommand: Boolean = false
    private var getNowGlucoseDataIndexCommand: Boolean = false
    private var getOlderReading: Boolean = false
    private var minutesBack: Int = 0
    private var timeLastBg: Long = 0
    private var currentTrendIndex: Int = 0
    private var nowGlucoseOffset: Int = 0
    private var blockNumber: Int = 0
    private var currentBlockNumber: Int = 0
    private var currentOffset: Int = 0
    private val fullData: ByteArray = ByteArray(344)

    private fun decodeBlukonPacket(buffer: ByteArray?): ByteArray? {
        var cmdFound = 0
        var gotLowBat: Boolean? = false
        var getHistoricReadings: Boolean? = false

        if (buffer == null) {
            Log.e(TAG, "null buffer passed to decodeBlukonPacket")
            return null
        }

        timeLastCmdReceived = JoHH.tsl()

        // calculate time delta to last valid reading
        persistentTimeLastBg = PersistentStore.getLong("blukon-time-of-last-reading")
        minutesDiffToLastReading =
            (((JoHH.tsl() - persistentTimeLastBg) / 1000 + 30) / 60).toInt()
        Log.i(
            TAG,
            "m_minutesDiffToLastReading=$minutesDiffToLastReading, last reading: " + JoHH.dateTimeText(
                persistentTimeLastBg
            )
        )

        // Get history if the last reading is older than we can reasonably backfill
        if (Pref.getBooleanDefaultFalse("retrieve_blukon_history") && persistentTimeLastBg > 0 && minutesDiffToLastReading > 17) {
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
            communicationStarted = true
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
            Log.e(
                TAG,
                "Got NACK on cmd=" + currentCommand + " with error=" + strRecCmd.substring(6)
            )

            if (strRecCmd.startsWith(BLUCON_NAK_RESPONSE_ERROR14)) {
                Log.e(TAG, "Timeout: please wait 5min or push button to restart!")
                sendMessageShowText("Timeout: please wait 5min or push button to restart!")
            }

            if (strRecCmd.startsWith(PATCH_NOT_FOUND_RESPONSE)) {
                Log.e(TAG, "Libre sensor has been removed!")
                sendMessageShowText("Libre sensor has been removed!")
            }

            if (strRecCmd.startsWith(PATCH_READ_ERROR)) {
                Log.e(
                    TAG,
                    "Patch read error.. please check the connectivity and re-initiate... or maybe battery is low?"
                )
                sendMessageShowText("Patch read error.. please check the connectivity and re-initiate... or maybe battery is low?")
                Pref.setInt("bridge_battery", 1)
                gotLowBat = true
            }

            getNowGlucoseDataCommand = false
            getNowGlucoseDataIndexCommand = false

            currentCommand = SLEEP_COMMAND
            Log.i(TAG, "Send sleep cmd")
            communicationStarted = false


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
                sendMessageShowText("Sensor is not ready, stop!")

                currentCommand = SLEEP_COMMAND
                Log.i(TAG, "Send sleep cmd")
                communicationStarted = false
            }

            /*
         * step 5: send unknownCommand1 as otherwise communication errors will occur
         */
        } else if (currentCommand.startsWith(UNKNOWN1_COMMAND) /*getUnknownCmd1*/ && strRecCmd.startsWith(
                "8bdb"
            )
        ) {
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
        } else if (currentCommand.startsWith(UNKNOWN2_COMMAND) /*getUnknownCmd2*/ && strRecCmd.startsWith(
                "8bda"
            )
        ) {
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
                    blockNumber = 0
                } else {
                    currentCommand = GET_NOW_DATA_INDEX_COMMAND
                    getNowGlucoseDataIndexCommand =
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
            sendMessageShowText("Sensor Age: $sensorAgeDays")

            if (Pref.getBooleanDefaultFalse("external_blukon_algorithm") || getHistoricReadings!!) {
                // Send the command to getHistoricData (read all blcoks from 0 to 0x2b)
                Log.i(TAG, "getHistoricData (3)")
                currentCommand = GET_HISTORIC_DATA_COMMAND_ALL_BLOCKS
                blockNumber = 0
            } else {
                /* LibreAlarmReceiver.CalculateFromDataTransferObject, called when processing historical data,
                 * expects the sensor age not to be updated yet, so only update the sensor age when not retrieving history.
                 */
                if (sensorAge in 1..199999) {
                    Pref.setInt("nfc_sensor_age", sensorAge)//in min
                }
                currentCommand = GET_NOW_DATA_INDEX_COMMAND
                getNowGlucoseDataIndexCommand =
                    true//to avoid issue when gotNowDataIndex cmd could be same as getNowGlucoseData (case block=3)
                Log.i(TAG, "getNowGlucoseDataIndexCommand")
            }

            /*
         * step 8: determine trend or historic data index
         */
        } else if (currentCommand.startsWith(GET_NOW_DATA_INDEX_COMMAND) && getNowGlucoseDataIndexCommand
            && strRecCmd.startsWith(SINGLE_BLOCK_INFO_RESPONSE_PREFIX)
        ) {
            cmdFound = 1

            // check time range for valid backfilling
            getOlderReading =
                if (minutesDiffToLastReading > 7 && minutesDiffToLastReading < 8 * 60) {
                    Log.i(TAG, "start backfilling")
                    true
                } else {
                    false
                }
            // get index to current BG reading
            currentBlockNumber = blockNumberForNowGlucoseData(buffer)
            currentOffset = nowGlucoseOffset
            // time diff must be > 5,5 min and less than the complete trend buffer
            if (!getOlderReading) {
                currentCommand =
                    READ_SINGLE_BLOCK_COMMAND_PREFIX + Integer.toHexString(currentBlockNumber)//getNowGlucoseData
                nowGlucoseOffset = currentOffset
                Log.i(TAG, "getNowGlucoseData")
            } else {
                minutesBack = minutesDiffToLastReading
                var delayedTrendIndex = currentTrendIndex
                // ensure to have min 3 mins distance to last reading to avoid doible draws (even if they are distict)
                when {
                    minutesBack > 17 -> minutesBack = 15
                    minutesBack > 12 -> minutesBack = 10
                    minutesBack > 7 -> minutesBack = 5
                }
                Log.i(TAG, "read $minutesBack mins old trend data")
                for (i in 0 until minutesBack) {
                    if (--delayedTrendIndex < 0)
                        delayedTrendIndex = 15
                }
                val delayedBlockNumber = blockNumberForNowGlucoseDataDelayed(delayedTrendIndex)
                currentCommand =
                    READ_SINGLE_BLOCK_COMMAND_PREFIX + Integer.toHexString(delayedBlockNumber)//getNowGlucoseData

                Log.i(TAG, "getNowGlucoseData backfilling")
            }
            getNowGlucoseDataIndexCommand = false
            getNowGlucoseDataCommand = true

            /*
         * step 9: calculate fro current index the block number next to read
         */
        } else if (currentCommand.startsWith(READ_SINGLE_BLOCK_COMMAND_PREFIX_SHORT) && getNowGlucoseDataCommand
            && strRecCmd.startsWith(SINGLE_BLOCK_INFO_RESPONSE_PREFIX)
        ) {

            Log.d(TAG, "Before Saving data: + currentCommand = $currentCommand")
            val blockId =
                currentCommand.substring(READ_SINGLE_BLOCK_COMMAND_PREFIX_SHORT.length)
            val now = JoHH.tsl()
            if (!blockId.isEmpty()) {
                val blockNum = JoHH.parseIntWithDefault(blockId, 16, -1)
                if (blockNum != -1) {
                    Log.d(TAG, "Saving data: + blockid = $blockNum")
                }
            }

            cmdFound = 1

            val divider = Pref.getString("Divider", "1").toDouble()
            val currentGlucose: Double = nowGetGlucoseValue(buffer) / divider
            val simpleDateFormat = SimpleDateFormat("HH:mm", Locale.UK)
            Pref.setString("Glucose", currentGlucose.toString())
            Pref.setString(
                "Time",
                simpleDateFormat.format(timeLastCmdReceived).toString()//.replace(":", "_")
            )
            Pref.setString(
                "Date",
                DateFormat.getDateInstance(3).format(timeLastCmdReceived).toString()//.replace(".", "_")
            )
            sendData()

            Log.i(TAG, "********got getNowGlucoseData=$currentGlucose")
            sendMessageShowText("Current glucose: $currentGlucose")

            if (!getOlderReading) {

                timeLastBg = now

                PersistentStore.setLong("blukon-time-of-last-reading", timeLastBg)
                Log.i(TAG, "time of current reading: " + JoHH.dateTimeText(timeLastBg))
                sendMessageShowText("time of last reading: ${JoHH.dateTimeText(persistentTimeLastBg)}")
                sendMessageShowText("time of current reading: ${JoHH.dateTimeText(timeLastBg)}")

                /*
                 * step 10: send sleep command
                 */
                currentCommand = SLEEP_COMMAND
                Log.i(TAG, "Send sleep cmd")
                communicationStarted = false

                getNowGlucoseDataCommand = false
            } else {
                Log.i(
                    TAG,
                    "bf: processNewTransmitterData with delayed timestamp of $minutesBack min"
                )

                minutesBack -= 5
                if (minutesBack < 5) {
                    getOlderReading = false
                }
                Log.i(TAG, "bf: calculate next trend buffer with $minutesBack min timestamp")
                var delayedTrendIndex = currentTrendIndex
                for (i in 0 until minutesBack) {
                    if (--delayedTrendIndex < 0)
                        delayedTrendIndex = 15
                }
                val delayedBlockNumber = blockNumberForNowGlucoseDataDelayed(delayedTrendIndex)
                currentCommand =
                    READ_SINGLE_BLOCK_COMMAND_PREFIX + Integer.toHexString(delayedBlockNumber)//getNowGlucoseData
                Log.i(TAG, "bf: read next block: $currentCommand")


            }
        } else if ((currentCommand.startsWith(GET_HISTORIC_DATA_COMMAND_ALL_BLOCKS) /*getHistoricData */
                    || currentCommand.isEmpty() && blockNumber > 0)
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
                sendMessageShowText("***COMMAND NOT FOUND! -> $strRecCmd on currentCmd=$currentCommand")
            }
            currentCommand = ""
            null
        }

    }

    private fun handlegetHistoricDataResponse(buffer: ByteArray) {
        Log.e(TAG, "recieved historic data, m_block_number = $blockNumber")
        // We are looking for 43 blocks of 8 bytes.
        // The bluekon will send them as 21 blocks of 16 bytes, and the last one of 8 bytes.
        // The packet will look like "0x8b 0xdf 0xblocknumber 0x02 DATA" (so data starts at place 4)
        if (blockNumber > 42) {
            Log.e(TAG, "recieved historic data, but block number is too big $blockNumber")
            return
        }

        val len = buffer.size - 4
        Log.e(TAG, "len = " + len + " " + len + " blocknum " + buffer[2])

        if (buffer[2].toInt() != blockNumber) {
            Log.e(
                TAG,
                "We have recieved a bad block number buffer[2] = " + buffer[2] + " m_blockNumber = " + blockNumber
            )
            return
        }
        if (8 * blockNumber + len > fullData.size) {
            Log.e(
                TAG,
                "We have recieved too much data  m_blockNumber = " + blockNumber + " len = " + len +
                        " m_full_data.length = " + fullData.size
            )
            return
        }

        System.arraycopy(buffer, 4, fullData, 8 * blockNumber, len)
        blockNumber += len / 8

        if (blockNumber >= 43) {
            val now = JoHH.tsl()
            currentCommand = SLEEP_COMMAND
            Log.i(TAG, "Send sleep cmd")
            communicationStarted = false

            PersistentStore.setLong("blukon-time-of-last-reading", now)
            Log.i(TAG, "time of current reading: " + JoHH.dateTimeText(now))
        } else {
            currentCommand = ""
        }
    }

    private fun nowGetGlucoseValue(input: ByteArray): Int {
        val rawGlucose: Long =
            (input[3 + nowGlucoseOffset + 1].toLong() and 0x1F).shl(8) or (input[3 + nowGlucoseOffset].toLong() and 0xFF)

        // option to use 13 bit mask
        //final boolean thirteen_bit_mask = Pref.getBooleanDefaultFalse("testing_use_thirteen_bit_mask");
        // grep 2 bytes with BG data from input bytearray, mask out 12 LSB bits and rescale for xDrip+
        Log.i(TAG, "rawGlucose=$rawGlucose, m_nowGlucoseOffset=$nowGlucoseOffset")

        // rescale
        //curGluc = getGlucose(rawGlucose)

        return rawGlucose.toInt()
    }

    private fun blockNumberForNowGlucoseData(input: ByteArray): Int {
        var nowGlucoseIndex2: Int = (input[5] and 0x0F).toInt()
        val nowGlucoseIndex3: Int

        currentTrendIndex = nowGlucoseIndex2

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
        nowGlucoseOffset = nowGlucoseIndex2 % 8

        Log.i(
            TAG,
            "++++++++currentTrendData: index $currentTrendIndex, block $nowGlucoseIndex3, offset $nowGlucoseOffset"
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
        nowGlucoseOffset = ngi2 % 8
        Log.i(
            TAG,
            "++++++++backfillingTrendData: index $delayedIndex, block $ngi3, offset $nowGlucoseOffset"
        )

        return ngi3
    }

    private fun sensorAge(input: ByteArray): Int {
        val sensorAge =
            ((input[3 + 5]).toInt() and 0xFF).shl(8) or (input[3 + 4].toInt() and 0xFF)
        Log.i(TAG, "sensorAge=$sensorAge")

        return sensorAge
    }

    private fun isSensorReady(sensorStatusByte: Byte): Boolean {

        val sensorStatusString: String
        var ret = false

        when (sensorStatusByte.toInt()) {
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

    private fun sendCommand() {
        val value = "8bde".toByteArray(Charsets.UTF_8)
        Log.d(TAG, "sendCommand: $value")
        writeCharacteristic.value = value
        bluetoothGatt.writeCharacteristic(writeCharacteristic)
    }

    private fun sendData() {
        val strBuilder = StringBuilder(
            "http://${Pref.getString(
                "IP",
                "isa.eshestakov.ru/api/dia/patients/set"
            )}"
        )
        strBuilder.append("?id=${Pref.getString("Mac", "1")}")
        strBuilder.append("&time=${Pref.getString("Time", "0")}")
        strBuilder.append("&date=${Pref.getString("Date", "0")}")
        strBuilder.append("&sugar=${String.format(".1%f", Pref.getString("Glucose", "0"))}")

        val meal = Pref.getString("Meal", "0")
        val basal = Pref.getString("Basal", "0")
        val bolus = Pref.getString("Bolus", "0")
        val divider = Pref.getString("Divider", "180.62")
        if (meal != "0") strBuilder.append("&food=$meal")
        if (basal != "0") strBuilder.append("&basal=$basal")
        if (bolus != "0") strBuilder.append("&bolus=$bolus")
        if (divider != "0") strBuilder.append("&divider=$divider")

        sendMessageShowText("Sending values to $strBuilder")

        request = Request.Builder()
            .url(strBuilder.toString())
            .build()

        okHttpClient.newCall(request).enqueue(object : Callback {
            override fun onResponse(call: Call, response: Response) {
                if (!response.isSuccessful) {
                    throw IOException("Unexpected code $response")
                } else {
                    sendMessageShowText("Values has been send")
                    Pref.setString("Glucose", "0")
                    Pref.setString("Meal", "0")
                    Pref.setString("Basal", "0")
                    Pref.setString("Bolus", "0")
                }
            }

            override fun onFailure(call: Call, e: IOException) {
                sendMessageShowText("Sending failed: ${e.message}")
            }
        })
    }

    private fun writeChar(
        localmCharacteristic: BluetoothGattCharacteristic,
        value: ByteArray?
    ): Boolean {
        localmCharacteristic.value = value
        val result = bluetoothGatt.writeCharacteristic(localmCharacteristic)
        if (!result) {
            Log.d(TAG, "writeChar: Error writing characteristic")
            val resendCharacteristic: BluetoothGattCharacteristic =
                cloner.shallowClone(localmCharacteristic)
            runBlocking {
                delay(1000)
            }
            JoHH.runOnUiThreadDelayed({
                kotlin.run {
                    val newResult = bluetoothGatt.writeCharacteristic(resendCharacteristic)
                    if (!newResult) Log.d(TAG, "writeChar: Error writing char on 2nd try")
                    else Log.d(TAG, "writeChar: Succeeded writing char on 2nd try")
                }
            }, 500)
        } else {
            Log.d(TAG, "writeChar: SUCCESSFUL")
        }
        return result
    }

    companion object {
        private const val CHANNEL_ID = "Channel_1"
        private const val NOTIFICATION_ID = 1
        private const val STATE_DISCONNECTED = 0
        private const val STATE_CONNECTING = 1
        private const val STATE_CONNECTED = 2

        const val ACTION_GATT_CONNECTED = "com.example.bluetooth.le.ACTION_GATT_CONNECTED"
        const val ACTION_GATT_DISCONNECTED = "com.example.bluetooth.le.ACTION_GATT_DISCONNECTED"
        private val desiredServiceUUID: UUID =
            UUID.fromString("436A62C0-082E-4CE8-A08B-01D81F195B24")
        private val desiredTransmitCharacteristicUUID: UUID =
            UUID.fromString("436AA6E9-082E-4CE8-A08B-01D81F195B24")
        private val desiredReceiveCharacteristicUUID: UUID =
            UUID.fromString("436A0C82-082E-4CE8-A08B-01D81F195B24")

        const val CONNECT = 1
        const val DISCONNECT = 2
        const val SEARCH = 3
        const val SHOW_TEXT = 4
        const val CHANGE_UI = 5
        const val ADD_DEVICE = 6
        const val SEND_DATA = 7

        const val REQUEST_ENABLE_BT = 11

        const val SCAN_PERIOD: Long = 10000


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
        const val GET_HISTORIC_DATA_COMMAND_ALL_BLOCKS =
            "010d0f02002b" // read all blocks from 0 to 0x2B

        const val PATCH_INFO_RESPONSE_PREFIX = "8bd9"
        const val SINGLE_BLOCK_INFO_RESPONSE_PREFIX = "8bde"
        const val MULTIPLE_BLOCK_RESPONSE_INDEX = "8bdf"
        const val BLUCON_ACK_RESPONSE = "8b0a00"
        const val BLUCON_NAK_RESPONSE_PREFIX = "8b1a02"

        const val BLUCON_UNKNOWN1_COMMAND_RESPONSE = "8bdb0101041711"
        const val BLUCON_UNKNOWN2_COMMAND_RESPONSE = "8bdaaa"
        const val BLUCON_UNKNOWN2_COMMAND_RESPONSE_BATTERY_LOW = "8bda02"

        const val BLUCON_NAK_RESPONSE_ERROR14 = "8b1a020014"

        const val PATCH_NOT_FOUND_RESPONSE = "8b1a02000f"
        const val PATCH_READ_ERROR = "8b1a020011"

        // we guess that this two commands indicate a low battery state
        const val BLUCON_BATTERY_LOW_INDICATION1 = "cb020000"
        const val BLUCON_BATTERY_LOW_INDICATION2 = "cbdb0000"

        const val POSITION_OF_SENSOR_STATUS_BYTE = 17

        private const val INTENT_SHOW_TEXT = "INTENT_SHOW_TEXT"

        private val BLUKON_GETSENSORAGE_TIMER = "blukon-getSensorAge-timer"
        private val BLUKON_DECODE_SERIAL_TIMER = "blukon-decodeSerial-timer"

        private val GET_DECODE_SERIAL_DELAY = 12 * 3600
        private val GET_SENSOR_AGE_DELAY = 3 * 3600
    }
}

