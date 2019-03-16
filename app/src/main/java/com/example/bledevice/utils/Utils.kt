package com.example.bledevice.utils

import android.util.Log
import kotlin.experimental.and

private val TAG = "Utils"
private val HEX_CHARS = "0123456789ABCDEF".toCharArray()

fun ByteArray.toHex(): String {
    val result = StringBuffer()

    forEach {
        val octet = it.toInt()
        val firstIndex = (octet and 0xF0).ushr(4)
        val secondIndex = octet and 0x0F
        result.append(HEX_CHARS[firstIndex])
        result.append(HEX_CHARS[secondIndex])
    }

    return result.toString()
}

fun String.hexStringToByteArray(): ByteArray {

    val result = ByteArray(length / 2)

    for (i in 0 until length step 2) {
        val firstIndex = HEX_CHARS.indexOf(this[i])
        val secondIndex = HEX_CHARS.indexOf(this[i + 1])

        val octet = firstIndex.shl(4).or(secondIndex)
        result.set(i.shr(1), octet.toByte())
    }

    return result
}

fun hexToBytes(hex: String): ByteArray {
    try {
        val length = hex.length
        val bytes = ByteArray(length / 2)
        var i = 0
        while (i < length) {
            bytes[i / 2] = ((Character.digit(hex[i], 16) shl 4) + Character.digit(hex[i + 1], 16)).toByte()
            i += 2
        }
        return bytes
    } catch (e: Exception) {
        Log.e(TAG, "Got Exception: $e")
        return ByteArray(0)
    }

}

fun decodeSerialNumber(input: ByteArray) {
    val lookupTable = arrayOf(
        "0",
        "1",
        "2",
        "3",
        "4",
        "5",
        "6",
        "7",
        "8",
        "9",
        "A",
        "C",
        "D",
        "E",
        "F",
        "G",
        "H",
        "J",
        "K",
        "L",
        "M",
        "N",
        "P",
        "Q",
        "R",
        "T",
        "U",
        "V",
        "W",
        "X",
        "Y",
        "Z"
    )
    val uuidShort = byteArrayOf(0, 0, 0, 0, 0, 0, 0, 0)
    var i: Int

    i = 2
    while (i < 8) {
        uuidShort[i - 2] = input[2 + 8 - i]
        i++
    }
    uuidShort[6] = 0x00
    uuidShort[7] = 0x00

    var binary = ""
    var binS: String
    i = 0
    while (i < 8) {
        binS = String.format(
            "%8s",
            Integer.toBinaryString(((uuidShort[i] and 0xFF.toByte()).toInt())).replace(' ', '0')
        )
        binary += binS
        i++
    }

    var v = "0"
    val pozS = charArrayOf(0.toChar(), 0.toChar(), 0.toChar(), 0.toChar(), 0.toChar())
    i = 0
    while (i < 10) {
        for (k in 0..4) pozS[k] = binary[5 * i + k]
        val value =
            (pozS[0] - '0') * 16 + (pozS[1] - '0') * 8 + (pozS[2] - '0') * 4 + (pozS[3] - '0') * 2 + (pozS[4] - '0') * 1
        v += lookupTable[value]
        i++
    }
    Log.d(TAG, "decodeSerialNumber=$v")
}