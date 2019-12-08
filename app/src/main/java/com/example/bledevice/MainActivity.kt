package com.example.bledevice

import android.Manifest
import android.bluetooth.*
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.*
import android.support.v7.app.AppCompatActivity
import android.text.method.ScrollingMovementMethod
import android.util.Log
import android.view.Menu
import android.view.MenuItem
import android.view.View
import com.example.bledevice.utils.*
import kotlinx.android.synthetic.main.activity_main.*
import java.nio.ByteBuffer
import java.util.*
import java.util.concurrent.TimeUnit
import kotlin.experimental.and
import com.rits.cloning.Cloner
import okhttp3.*
import java.io.IOException
import java.text.DateFormat
import java.text.SimpleDateFormat


class MainActivity : AppCompatActivity() {

    private lateinit var mLeDeviceAdapter: LeDeviceListAdapter

    private var bound = false

    private var bleService: BluetoothLeService? = null
    private lateinit var messenger: Messenger
    private var mainActivityMessenger: Messenger = Messenger(InternalMainActivityHandler())

    inner class InternalMainActivityHandler : Handler() {
        override fun handleMessage(msg: Message?) {
            when (msg?.what) {
                BluetoothLeService.REQUEST_ENABLE_BT -> {
                    enableBT()
                }
                BluetoothLeService.ADD_DEVICE -> {
                    addDevice(msg.data?.getParcelable("device") as BluetoothDevice)
                }
                BluetoothLeService.SHOW_TEXT -> {
                    showText(msg.data?.getString("text")!!)
                }
                BluetoothLeService.CHANGE_UI -> {
                    changeUI(msg.data?.getBoolean("changeUI")!!)
                }
            }
            super.handleMessage(msg)
        }
    }

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            messenger = Messenger(service)
            bound = true
            search()
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            bleService = null
            bound = false
        }
    }

    private fun addDevice(device: BluetoothDevice) {
        mLeDeviceAdapter.addDevice(device)
        mLeDeviceAdapter.notifyDataSetChanged()
    }

    private fun enableBT() {
        val enableBtIntent = Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE)
        startActivityForResult(enableBtIntent, REQUEST_ENABLE_BT)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        setSupportActionBar(findViewById(R.id.my_toolbar))

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            if (checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
                requestPermissions(
                    arrayOf(Manifest.permission.ACCESS_COARSE_LOCATION),
                    REQUEST_COARSE_LOCATION
                )
            }
        }

        mLeDeviceAdapter = LeDeviceListAdapter(this)
        listview.adapter = mLeDeviceAdapter
        listview.setOnItemClickListener { _, _, position, _ ->
            val device: BluetoothDevice = mLeDeviceAdapter.getDevice(position)
            if (!bound) {
                val intent = Intent(this, BluetoothLeServiceSecond::class.java)
                bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE)
            }
            connect(device)
        }

        searchButton.isEnabled = true
        searchButton.setOnClickListener {
            val intent = Intent(this, BluetoothLeServiceSecond::class.java)
            bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE)
            if (bound) {
                search()
            }
        }

        disconnectButton.isEnabled = false
        disconnectButton.setOnClickListener {
            disconnect()
            bound = false
            bleService = null
            unbindService(serviceConnection)
        }

        sendButton.isEnabled = true

        sendButton.setOnClickListener {
            if (bound) {
                sendData()
            }
        }

        uuid_textView.movementMethod = ScrollingMovementMethod()
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        if (requestCode == REQUEST_ENABLE_BT) {
            val intent = Intent(this, BluetoothLeServiceSecond::class.java)
            bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE)

            search()
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

    private fun changeUI(connected: Boolean) {
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

    private fun search() {
        val message = Message.obtain(null, BluetoothLeService.SEARCH)
        message.replyTo = mainActivityMessenger
        try {
            messenger.send(message)
        } catch (e: RemoteException) {
            e.printStackTrace()
        }
    }

    private fun connect(device: BluetoothDevice) {
        val bundle = Bundle()
        bundle.putString("address", device.address)
        val message = Message.obtain(null, BluetoothLeService.CONNECT)
        message.data = bundle
        message.replyTo = mainActivityMessenger
        try {
            messenger.send(message)
        } catch (e: RemoteException) {
            e.printStackTrace()
        }
    }

    private fun disconnect() {
        val bundle = Bundle()
        val message = Message.obtain(null, BluetoothLeService.DISCONNECT)
        message.data = bundle
        message.replyTo = mainActivityMessenger
        try {
            messenger.send(message)
        } catch (e: RemoteException) {
            e.printStackTrace()
        }
    }

    private fun sendData() {
        val bundle = Bundle()
        val message = Message.obtain(null, BluetoothLeService.SEND_DATA)
        message.data = bundle
        message.replyTo = mainActivityMessenger
        try {
            messenger.send(message)
        } catch (e: RemoteException) {
            e.printStackTrace()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        if (bound)
            unbindService(serviceConnection)
    }
    

    private fun showText(text: String) {
        uuid_textView.text = "" + uuid_textView.text + "\n" + text
    }

    companion object {
        const val REQUEST_COARSE_LOCATION = 2
        const val REQUEST_ENABLE_BT = 11
    }
}