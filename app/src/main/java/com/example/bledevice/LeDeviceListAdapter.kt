package com.example.bledevice

import android.bluetooth.BluetoothDevice
import android.content.Context
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.TextView

class LeDeviceListAdapter(private val context: Context) : BaseAdapter() {
    private val TAG = "LeDeviceListAdapter"

    private val mLeDevices: ArrayList<BluetoothDevice> = ArrayList()

    private val inflater: LayoutInflater = context.getSystemService(
        Context.LAYOUT_INFLATER_SERVICE
    ) as LayoutInflater


    fun addDevice(device: BluetoothDevice) {
        if (!mLeDevices.contains(device)) {
            mLeDevices.add(device)
        }
    }

    fun getDevice(position: Int): BluetoothDevice {
        return mLeDevices[position]
    }

    fun clear() {
        mLeDevices.clear()
    }

    override fun getView(position: Int, convertView: View?, parent: ViewGroup?): View {
        val rowView = inflater.inflate(R.layout.listitem_device, parent, false)
        val deviceName = rowView.findViewById(R.id.device_name) as TextView
        val macAddress = rowView.findViewById(R.id.macAddress) as TextView

        val device = getDevice(position)
        if (device.name != null)
            deviceName.text = device.name
        else
            deviceName.text = context.getString(R.string.none)
        Log.d(TAG, "getView:${device.name}")
        macAddress.text = device.address

        return rowView
    }

    override fun getItem(position: Int): Any {
        return mLeDevices[position]
    }

    override fun getItemId(position: Int): Long {
        return position.toLong()
    }

    override fun getCount(): Int {
        return mLeDevices.size
    }

}
