package com.example.bledevice

import android.os.Bundle
import android.os.Message
import android.os.Messenger
import android.os.RemoteException
import okhttp3.*
import java.io.IOException
import java.text.DateFormat
import java.text.SimpleDateFormat
import java.util.*

class RequestMaker {

    fun sendData(messenger: Messenger) {
        val okHttpClient = OkHttpClient()
        val url = StringBuilder(
            "http://${Pref.getString(
                "IP",
                "isa.eshestakov.ru/api/dia/patients/set"
            )}"
        )

        val request = Request.Builder()
            .url(url.toString())
            .build()

        val sdf = SimpleDateFormat("HH:mm", Locale.UK)
        val time = sdf.format(System.currentTimeMillis()).toString()
        val date = DateFormat.getDateInstance(3).format(System.currentTimeMillis()).toString()

        url.append("?id=${Pref.getString("Mac", "1")}")
        url.append("&time=$time")
        url.append("&date=$date")
        url.append("&sugar=${Pref.getString("Glucose", "0")}")

        val meal = Pref.getString("Meal", "0")
        val basal = Pref.getString("Basal", "0")
        val bolus = Pref.getString("Bolus", "0")
        val divider = Pref.getString("Divider", "180.62")
        if (meal != "0") url.append("&food=$meal")
        if (basal != "0") url.append("&basal=$basal")
        if (bolus != "0") url.append("&bolus=$bolus")
        if (divider != "0") url.append("&divider=$divider")

        sendMessageShowText("Sending values to $url", messenger)

        okHttpClient.newCall(request).enqueue(object : Callback {
            override fun onResponse(call: Call, response: Response) {
                println("BluetoothLeService.onResponse")
                if (!response.isSuccessful) {
                    throw IOException("Unexpected code $response")
                } else {
                    Pref.setString("Glucose", "0")
                    Pref.setString("Meal", "0")
                    Pref.setString("Basal", "0")
                    Pref.setString("Bolus", "0")
                    sendMessageShowText(BluetoothLeService.VALUES_HAS_BEEN_SEND, messenger)
                }
            }

            override fun onFailure(call: Call, e: IOException) {
                println("BluetoothLeService.onFailure")
                sendMessageShowText("Sending failed: ${e.message}", messenger)
            }
        })
    }

    private fun sendMessageShowText(text: String, messenger: Messenger) {
        val bundle = Bundle()
        bundle.putString("text", text)
        val message = Message.obtain(null, BluetoothLeService.SHOW_TEXT)
        message.data = bundle

        try {
            messenger.send(message)
        } catch (e: RemoteException) {
            e.printStackTrace()
        }
    }
}