package com.auto.voltmeter

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import java.io.File

enum class SourceMode {
    OBD_ELM327,
    HEAD_UNIT_MCU
}

class InternalVoltageReader(private val context: Context) {

    private val sysPaths = listOf(
        "/sys/class/power_supply/battery/voltage_now",
        "/sys/class/power_supply/ac/voltage_now",
        "/sys/class/power_supply/usb/voltage_now",
        "/sys/devices/platform/mcu/voltage",
        "/sys/bus/platform/drivers/mcu/voltage",
        "/proc/mcu_voltage"
    )

    fun readMcuVoltage(): Float? {
        for (path in sysPaths) {
            val file = File(path)
            if (file.exists() && file.canRead()) {
                try {
                    val text = file.readText().trim()
                    val raw = text.toLongOrNull()
                    if (raw != null && raw > 0) {
                        val voltage = when {
                            raw > 100_000 -> raw / 1_000_000f
                            raw > 1_000 -> raw / 1_000f
                            raw in 80..180 -> raw / 10f
                            else -> raw.toFloat()
                        }
                        if (voltage in 8.0f..18.0f) return voltage
                    }
                } catch (_: Exception) {}
            }
        }

        val propKeys = listOf(
            "persist.sys.mcu.voltage",
            "sys.mcu.voltage",
            "hw.mcu.voltage"
        )
        for (key in propKeys) {
            val propVal = getSystemProperty(key)
            val parsed = propVal?.replace("V", "", true)?.trim()?.toFloatOrNull()
            if (parsed != null && parsed in 8.0f..18.0f) {
                return parsed
            }
        }

        try {
            val intent = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            val mv = intent?.getIntExtra(BatteryManager.EXTRA_VOLTAGE, -1) ?: -1
            if (mv > 8000) {
                return mv / 1000f
            } else if (mv in 3000..4500) {
                return mv / 1000f
            }
        } catch (_: Exception) {}

        return null
    }

    @SuppressLint("PrivateApi")
    private fun getSystemProperty(key: String): String? {
        return try {
            val clazz = Class.forName("android.os.SystemProperties")
            val getMethod = clazz.getMethod("get", String::class.java)
            val result = getMethod.invoke(null, key) as? String
            if (result.isNullOrEmpty()) null else result
        } catch (_: Exception) {
            null
        }
    }
}
