package com.auto.voltmeter

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat

class MainActivity : AppCompatActivity() {

    private lateinit var radioGroupSource: RadioGroup
    private lateinit var rbObd: RadioButton
    private lateinit var rbMcu: RadioButton
    private lateinit var spinnerBt: Spinner
    private lateinit var seekBarOffset: SeekBar
    private lateinit var tvOffsetValue: TextView
    private lateinit var btnSaveAndStart: Button
    private lateinit var btnStop: Button

    private val pairedDevices = mutableListOf<BluetoothDevice>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        requestNeededPermissions()

        radioGroupSource = findViewById(R.id.rg_source)
        rbObd = findViewById(R.id.rb_obd)
        rbMcu = findViewById(R.id.rb_mcu)
        spinnerBt = findViewById(R.id.spinner_bt)
        seekBarOffset = findViewById(R.id.seek_offset)
        tvOffsetValue = findViewById(R.id.tv_offset_val)
        btnSaveAndStart = findViewById(R.id.btn_start)
        btnStop = findViewById(R.id.btn_stop)

        val prefs = getSharedPreferences("volt_prefs", Context.MODE_PRIVATE)

        loadPairedBluetoothDevices(prefs.getString("selected_mac", null))

        val savedOffset = prefs.getFloat("calibration_offset", 0.0f)
        seekBarOffset.max = 100
        seekBarOffset.progress = ((savedOffset + 5.0f) * 10).toInt().coerceIn(0, 100)
        tvOffsetValue.text = String.format("%+.1f В", savedOffset)

        seekBarOffset.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
                val currentOffset = (progress - 50) / 10.0f
                tvOffsetValue.text = String.format("%+.1f В", currentOffset)
            }
            override fun onStartTrackingTouch(sb: SeekBar?) {}
            override fun onStopTrackingTouch(sb: SeekBar?) {}
        })

        radioGroupSource.setOnCheckedChangeListener { _, checkedId ->
            spinnerBt.isEnabled = (checkedId == R.id.rb_obd)
        }

        val currentMode = prefs.getString("source_mode", SourceMode.HEAD_UNIT_MCU.name)
        if (currentMode == SourceMode.OBD_ELM327.name) {
            rbObd.isChecked = true
            spinnerBt.isEnabled = true
        } else {
            rbMcu.isChecked = true
            spinnerBt.isEnabled = false
        }

        btnSaveAndStart.setOnClickListener {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(this)) {
                Toast.makeText(this, "Разрешите отображение поверх других окон!", Toast.LENGTH_LONG).show()
                requestOverlayPermission()
                return@setOnClickListener
            }

            val selectedOffset = (seekBarOffset.progress - 50) / 10.0f
            val mode = if (rbObd.isChecked) SourceMode.OBD_ELM327.name else SourceMode.HEAD_UNIT_MCU.name

            val editor = prefs.edit()
            editor.putString("source_mode", mode)
            editor.putFloat("calibration_offset", selectedOffset)

            if (rbObd.isChecked && pairedDevices.isNotEmpty()) {
                val pos = spinnerBt.selectedItemPosition
                if (pos in pairedDevices.indices) {
                    editor.putString("selected_mac", pairedDevices[pos].address)
                }
            }
            editor.apply()

            val serviceIntent = Intent(this, VoltOverlayService::class.java)
            stopService(serviceIntent)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(serviceIntent)
            } else {
                startService(serviceIntent)
            }
            Toast.makeText(this, "Вольтметр запущен", Toast.LENGTH_SHORT).show()
            finish()
        }

        btnStop.setOnClickListener {
            stopService(Intent(this, VoltOverlayService::class.java))
            Toast.makeText(this, "Вольтметр остановлен", Toast.LENGTH_SHORT).show()
        }
    }

    @SuppressLint("MissingPermission")
    private fun loadPairedBluetoothDevices(savedMac: String?) {
        pairedDevices.clear()
        val deviceNames = mutableListOf<String>()
        val btManager = getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
        val adapter = btManager?.adapter

        var selectedIndex = 0
        try {
            adapter?.bondedDevices?.forEachIndexed { index, dev ->
                pairedDevices.add(dev)
                deviceNames.add("${dev.name ?: "Устройство"} (${dev.address})")
                if (dev.address == savedMac) selectedIndex = index
            }
        } catch (_: SecurityException) {}

        if (deviceNames.isEmpty()) {
            deviceNames.add("Нет сопряженных устройств")
        }

        val arrayAdapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, deviceNames)
        spinnerBt.adapter = arrayAdapter
        spinnerBt.setSelection(selectedIndex)
    }

    private fun requestNeededPermissions() {
        requestOverlayPermission()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (ActivityCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(
                    this,
                    arrayOf(Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.BLUETOOTH_SCAN),
                    100
                )
            }
        }
    }

    private fun requestOverlayPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(this)) {
            val intent = Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:$packageName")
            )
            startActivity(intent)
        }
    }
}
