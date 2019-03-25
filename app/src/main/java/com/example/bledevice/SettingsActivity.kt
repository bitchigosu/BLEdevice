package com.example.bledevice

import android.app.DatePickerDialog
import android.support.v7.app.AppCompatActivity
import android.os.Bundle
import android.view.View
import android.widget.EditText
import kotlinx.android.synthetic.main.activity_settings.*
import java.text.DateFormat
import java.util.*
import android.app.TimePickerDialog
import android.text.Editable
import android.view.Menu
import android.view.MenuItem
import android.widget.SeekBar
import android.widget.Toast
import java.text.SimpleDateFormat


class SettingsActivity : AppCompatActivity() {

    private val dateAndTime = Calendar.getInstance()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)
        setSupportActionBar(findViewById(R.id.my_toolbar))
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        updateTimeAndDate()

        timeText.setOnClickListener {
            showTimeDialog(it)
        }

        dateText.setOnClickListener {
            showDateDialog(it)
        }

        saveButton.setOnClickListener {
            Pref.setString("Glucose", editText_glucose.checkForNull())
            Pref.setString("Meal", seekBar_meal.progress.toString())
            Pref.setString("Basal", basalEditText.checkForNull())
            Pref.setString("Bolus", bolusEditText.checkForNull())
            Pref.setString("IP", IPADDR_View.checkForNull())
            Pref.setString("Mac", CLIENTID_View.checkForNull().replace(":", "_"))
            Pref.setString("Time", timeText.text.toString().replace(":", "_"))
            Pref.setString("Date", dateText.text.toString().replace(".", "_"))
            Toast.makeText(this, "Saved!", Toast.LENGTH_SHORT).show()
            finish()
        }

        seekBar_meal.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                mealValue.text = seekBar?.progress.toString()
            }

            override fun onStartTrackingTouch(seekBar: SeekBar?) {

            }

            override fun onStopTrackingTouch(seekBar: SeekBar?) {

            }

        })
    }

    override fun onCreateOptionsMenu(menu: Menu?): Boolean {
        menuInflater.inflate(R.menu.settings_menu, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem?): Boolean = when (item?.itemId) {
        R.id.resetBtn -> {
            resetValues()
            true
        }
        android.R.id.home -> {
            finish()
            true
        }
        else -> {
            super.onOptionsItemSelected(item)
        }
    }

    private fun resetValues() {
        editText_glucose.text.clear()
        seekBar_meal.progress = 0
        basalEditText.text.clear()
        bolusEditText.text.clear()
    }

    private fun updateTimeAndDate() {
        val sdf = SimpleDateFormat("HH:mm", Locale.UK)
        timeText.text = sdf.format(dateAndTime.timeInMillis).toString()
        dateText.text = DateFormat.getDateInstance(3).format(dateAndTime.timeInMillis).toString()

        editText_glucose.text = Pref.getString("Glucose", "0").toEditable()
        basalEditText.text = Pref.getString("Basal", "0").toEditable()
        bolusEditText.text = Pref.getString("Bolus", "0").toEditable()
        IPADDR_View.text = Pref.getString("IP", "0").toEditable()
        CLIENTID_View.text = Pref.getString("Mac", "1").toEditable()
        seekBar_meal.progress = Pref.getString("Meal", "0").toInt()
        mealValue.text = Pref.getString("Meal", "0")
    }

    private fun showDateDialog(view: View) {
        DatePickerDialog(
            this, d,
            dateAndTime.get(Calendar.YEAR),
            dateAndTime.get(Calendar.MONTH),
            dateAndTime.get(Calendar.DAY_OF_MONTH)
        )
            .show()
    }

    private fun showTimeDialog(view: View) {
        TimePickerDialog(
            this, t,
            dateAndTime.get(Calendar.HOUR_OF_DAY),
            dateAndTime.get(Calendar.MINUTE), true
        )
            .show()
    }

    var d: DatePickerDialog.OnDateSetListener =
        DatePickerDialog.OnDateSetListener { _, year, monthOfYear, dayOfMonth ->
            dateAndTime.set(Calendar.YEAR, year)
            dateAndTime.set(Calendar.MONTH, monthOfYear)
            dateAndTime.set(Calendar.DAY_OF_MONTH, dayOfMonth)
            updateTimeAndDate()
        }

    var t: TimePickerDialog.OnTimeSetListener =
        TimePickerDialog.OnTimeSetListener { _, hourOfDay, minute ->
            dateAndTime.set(Calendar.HOUR_OF_DAY, hourOfDay)
            dateAndTime.set(Calendar.MINUTE, minute)
            updateTimeAndDate()
        }


    private fun EditText.checkForNull(): String = if (text.isEmpty()) "0" else text.toString()
    private fun String.toEditable(): Editable = Editable.Factory.getInstance().newEditable(this)
}
