package com.example.bledevice

import android.support.v7.app.AppCompatActivity
import android.os.Bundle
import com.example.bledevice.utils.Pref
import kotlinx.android.synthetic.main.activity_settings.*

class SettingsActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)

        saveButton.setOnClickListener {
            Pref.setInt("Glucose", editText_glucose.text.toString().toInt())
            Pref.setInt("Meal", seekBar_meal.progress)
            Pref.setInt("Basal", basalEditText.text.toString().toInt())
            Pref.setInt("Bolus", bolusEditText.text.toString().toInt())
        }
    }
}
