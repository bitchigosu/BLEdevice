package com.example.bledevice

import android.Manifest
import android.bluetooth.*
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.*
import android.support.v7.app.AppCompatActivity
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.widget.SeekBar
import android.widget.TextView
import com.example.bledevice.BluetoothLeService.Companion.VALUES_HAS_BEEN_SEND
import kotlinx.android.synthetic.main.activity_main.*
import kotlinx.android.synthetic.main.activity_main.meal_value
import kotlinx.android.synthetic.main.activity_main.seekbar_meal
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import okhttp3.*
import java.io.IOException
import java.text.DateFormat
import java.text.SimpleDateFormat
import java.util.*


class MainActivity : AppCompatActivity() {

    private lateinit var mLeDeviceAdapter: LeDeviceListAdapter

    private var bound = false

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

                    if (msg.data?.getString("text").equals(VALUES_HAS_BEEN_SEND)) {
                        setValues()
                        saveValues()
                    }
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
            search()
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            bound = false
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        setSupportActionBar(findViewById(R.id.toolbar))

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            if (checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
                requestPermissions(
                    arrayOf(Manifest.permission.ACCESS_COARSE_LOCATION),
                    REQUEST_COARSE_LOCATION
                )
            }
        }
        setupUI()
    }

    override fun onPause() {
        super.onPause()
        saveValues()
    }

    override fun onDestroy() {
        super.onDestroy()
        if (bound)
            unbindService(serviceConnection)
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        if (requestCode == REQUEST_ENABLE_BT) {
            val intent = Intent(this, BluetoothLeService::class.java)
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

    private fun setupUI() {
        mLeDeviceAdapter = LeDeviceListAdapter(this)
        devices.adapter = mLeDeviceAdapter
        devices.setOnItemClickListener { _, _, position, _ ->
            val device: BluetoothDevice = mLeDeviceAdapter.getDevice(position)
            if (!bound) {
                val intent = Intent(this, BluetoothLeService::class.java)
                bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE)
            }
            connect(device)
        }

        search.isEnabled = true
        search.setOnClickListener {
            val intent = Intent(this, BluetoothLeService::class.java)
            bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE)
            if (bound) {
                search()
            }
        }

        disconnect.isEnabled = false
        disconnect.setOnClickListener {
            disconnect()
            bound = false
            unbindService(serviceConnection)
        }

        send.setOnClickListener {
            saveValues()
            sendRequest()
        }
        show_hide_console.setOnClickListener {
            console.visibility = if (console.visibility == View.VISIBLE) View.GONE else View.VISIBLE
        }

        setValues()

        seekbar_meal.setListener(meal_value)
        seekbar_basal.setListener(basal_value)
        seekbar_bolus.setListener(bolus_value)
    }

    private fun setValues() {
        val meal = Pref.getString("Meal", "0")
        seekbar_meal.progress = meal.toInt()
        meal_value.text = meal

        val basal = Pref.getString("Basal", "0")
        seekbar_basal.progress = basal.toInt()
        basal_value.text = basal

        val bolus = Pref.getString("Bolus", "0")
        seekbar_bolus.progress = bolus.toInt()
        bolus_value.text = bolus
    }

    private fun saveValues() {
        Pref.setString("Meal", seekbar_meal.progress.toString())
        Pref.setString("Basal", seekbar_basal.progress.toString())
        Pref.setString("Bolus", seekbar_bolus.progress.toString())
    }

    private fun addDevice(device: BluetoothDevice) {
        mLeDeviceAdapter.addDevice(device)
        mLeDeviceAdapter.notifyDataSetChanged()
    }

    private fun enableBT() {
        val enableBtIntent = Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE)
        startActivityForResult(enableBtIntent, REQUEST_ENABLE_BT)
    }

    private fun changeUI(connected: Boolean) {
        disconnect.isEnabled = connected
        //send.isEnabled = !connected
        when (connected) {
            true -> {
                bound = true
                showText(getString(R.string.connected))
                search.isEnabled = false
                devices.visibility = View.GONE
            }
            false -> {
                showText(getString(R.string.disconnected))
                search.isEnabled = true
                devices.visibility = View.VISIBLE
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
        saveValues()
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

    private fun sendRequest() {
        RequestMaker().sendData(mainActivityMessenger)
    }

    private fun showText(text: String) {
        console.text = "" + console.text + "\n" + text
    }

    private fun SeekBar.setListener(view: TextView) =
        this.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                view.text = seekBar?.progress.toString()
            }

            override fun onStartTrackingTouch(seekBar: SeekBar?) {

            }

            override fun onStopTrackingTouch(seekBar: SeekBar?) {

            }
        })

    companion object {
        const val REQUEST_COARSE_LOCATION = 2
        const val REQUEST_ENABLE_BT = 11
    }
}