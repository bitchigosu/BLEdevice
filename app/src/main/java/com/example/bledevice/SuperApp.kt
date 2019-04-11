package com.example.bledevice

import android.app.Application
import android.content.Context

class SuperApp : Application() {
    override fun onCreate() {
        super.onCreate()
        instance = this
    }

    companion object {
        private lateinit var instance: Application
        fun getContext() : Context = instance.applicationContext
    }
}