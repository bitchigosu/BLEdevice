package com.example.bledevice


class GlucoseDecoder {
    private var dataString = ""
    private val sensorTimeStartingBytes = arrayOf(317, 316)
    private val trendValuesRange = arrayOf(56, 248)
    private val historicalDataRange = arrayOf(248, 632)
    private val glucoseByteLength = 12
    private val nextWriteBlock1StartIndex = 26 * 2
    private val nextWriteBlock2StartIndex = 27 * 2

    fun getGlucose(data: String): Int {
        val glucose = String.format("%02X", data).toInt() and  (0x0FFF)
        val result = glucose / 10
        return if (result >= 0) result else 0
    }
}