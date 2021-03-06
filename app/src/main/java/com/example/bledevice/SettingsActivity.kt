package com.example.bledevice

import android.app.AlertDialog
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
import android.util.Log
import android.view.Menu
import android.view.MenuItem
import android.widget.SeekBar
import android.widget.Toast
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat


class SettingsActivity : AppCompatActivity() {
    private val dateAndTime = Calendar.getInstance()
    private val TAG = "SettingsActivity"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)
        updateValues()
        setSupportActionBar(findViewById(R.id.toolbar))
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        timeText.setOnClickListener {
            showTimeDialog(it)
        }

        dateText.setOnClickListener {
            showDateDialog(it)
        }

        saveButton.setOnClickListener {
            Pref.setString("Glucose", editText_glucose.checkForNull())
            Pref.setString("IP", IPADDR_View.checkForNull())
            Pref.setString("Mac", CLIENTID_View.checkForNull())
            Pref.setString("Time", timeText.text.toString())
            Pref.setString("Date", dateText.text.toString())

            Pref.setString("Divider", divider.checkForNull())
            Toast.makeText(this, "Saved!", Toast.LENGTH_SHORT).show()
            finish()
        }

        Log.d(TAG, "onCreate: end")
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
    }

    private fun updateValues() {
        val sdf = SimpleDateFormat("HH:mm", Locale.UK)
        timeText.text = sdf.format(dateAndTime.timeInMillis).toString()
        dateText.text = DateFormat.getDateInstance(3).format(dateAndTime.timeInMillis).toString()

        GlobalScope.launch {
            editText_glucose.text = Pref.getString("Glucose", "0").toEditable()
            IPADDR_View.text = Pref.getString("IP", "isa.eshestakov.ru/api/dia/patients/set").toEditable() //83.149.249.16:8888
            CLIENTID_View.text = Pref.getString("Mac", "1").toEditable()
            divider.text = Pref.getString("Divider", "1").toEditable()
        }
    }

    private fun showInfoDialog(term: String) {
        val builder = AlertDialog.Builder(this@SettingsActivity)
        if (term == "bolus") {
            builder
                .setTitle(R.string.bolus_title)
                .setMessage(R.string.bolus_meaning)
        } else {
            builder
                .setTitle(R.string.basal_title)
                .setMessage(R.string.basal_meaning)
        }
        builder
            .setCancelable(false)
            .setPositiveButton(R.string.ok_button) { dialog, _ ->
                dialog.cancel()
            }
        val dialog = builder.create()
        dialog.setCancelable(false)
        dialog.show()
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

    private var d: DatePickerDialog.OnDateSetListener =
        DatePickerDialog.OnDateSetListener { _, year, monthOfYear, dayOfMonth ->
            dateAndTime.set(Calendar.YEAR, year)
            dateAndTime.set(Calendar.MONTH, monthOfYear)
            dateAndTime.set(Calendar.DAY_OF_MONTH, dayOfMonth)
            updateValues()
        }

    private var t: TimePickerDialog.OnTimeSetListener =
        TimePickerDialog.OnTimeSetListener { _, hourOfDay, minute ->
            dateAndTime.set(Calendar.HOUR_OF_DAY, hourOfDay)
            dateAndTime.set(Calendar.MINUTE, minute)
            updateValues()
        }


    private fun EditText.checkForNull(): String = if (text.isEmpty()) "0" else text.toString()
    private fun String.toEditable(): Editable = Editable.Factory.getInstance().newEditable(this)
}
