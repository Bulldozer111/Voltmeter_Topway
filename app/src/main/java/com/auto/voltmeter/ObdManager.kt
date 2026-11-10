package com.auto.voltmeter

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothSocket
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.InputStream
import java.io.OutputStream
import java.util.UUID

class ObdManager(private val device: BluetoothDevice) {

    private val sppUuid: UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")
    private var socket: BluetoothSocket? = null
    private var inStream: InputStream? = null
    private var outStream: OutputStream? = null

    private val voltageRegex = Regex("(\\d{1,2}[.,]\\d{1,2})")

    @SuppressLint("MissingPermission")
    suspend fun connect(): Boolean = withContext(Dispatchers.IO) {
        disconnect()
        try {
            socket = device.createRfcommSocketToServiceRecord(sppUuid)
            socket?.connect()
            inStream = socket?.inputStream
            outStream = socket?.outputStream

            sendCommand("AT Z\r")
            Thread.sleep(400)
            sendCommand("AT E0\r")
            sendCommand("AT L0\r")
            true
        } catch (e: Exception) {
            disconnect()
            false
        }
    }

    suspend fun readVoltage(): Float? = withContext(Dispatchers.IO) {
        try {
            val response = sendCommand("AT RV\r")
            val match = voltageRegex.find(response)
            val numberStr = match?.value?.replace(",", ".")
            numberStr?.toFloatOrNull()
        } catch (e: Exception) {
            null
        }
    }

    private fun sendCommand(cmd: String): String {
        val out = outStream ?: throw IllegalStateException("Stream closed")
        val input = inStream ?: throw IllegalStateException("Stream closed")

        out.write(cmd.toByteArray())
        out.flush()

        val buffer = ByteArray(128)
        val sb = StringBuilder()
        val startTime = System.currentTimeMillis()

        while (System.currentTimeMillis() - startTime < 2000) {
            if (input.available() > 0) {
                val read = input.read(buffer)
                if (read == -1) break
                sb.append(String(buffer, 0, read))
                if (sb.contains(">")) break
            } else {
                Thread.sleep(30)
            }
        }
        return sb.toString()
    }

    fun disconnect() {
        try { inStream?.close() } catch (_: Exception) {}
        try { outStream?.close() } catch (_: Exception) {}
        try { socket?.close() } catch (_: Exception) {}
        inStream = null
        outStream = null
        socket = null
    }
}
