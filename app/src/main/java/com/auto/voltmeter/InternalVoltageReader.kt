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

/**
 * Чтение напряжения бортсети с головного устройства (Topway и аналоги).
 *
 * Порядок источников:
 *  1. свой путь из настроек (custom_path)
 *  2. последний рабочий путь (запоминается автоматически)
 *  3. известные пути sysfs/proc
 *  4. автопоиск файлов с "volt" в имени
 *  5. системные свойства
 *  6. ACTION_BATTERY_CHANGED (только если значение похоже на 12/24 В, а не на Li-ion 3-4.5 В)
 */
class InternalVoltageReader(private val context: Context) {

    private val prefs = context.getSharedPreferences("volt_prefs", Context.MODE_PRIVATE)

    private var discovered: List<String> = emptyList()
    private var lastScan = 0L

    companion object {
        const val PREF_CUSTOM_PATH = "custom_path"
        private const val PREF_WORKING_PATH = "working_path"
        private const val RESCAN_INTERVAL_MS = 120_000L
        private const val MAX_SCANNED_ENTRIES = 6000

        // Допустимый диапазон бортсети: просадка при пуске ... 24 В
        private const val MIN_V = 5.0f
        private const val MAX_V = 32.0f

        private val STATIC_PATHS = listOf(
            "/proc/mcu_voltage",
            "/sys/devices/platform/mcu/voltage",
            "/sys/bus/platform/drivers/mcu/voltage",
            "/sys/class/mcu/voltage",
            "/sys/class/misc/mcu/voltage",
            "/sys/class/power_supply/battery/voltage_now",
            "/sys/class/power_supply/ac/voltage_now",
            "/sys/class/power_supply/usb/voltage_now",
            "/sys/class/power_supply/main/voltage_now",
            "/sys/class/power_supply/bms/voltage_now"
        )

        private val PROP_KEYS = listOf(
            "persist.sys.mcu.voltage",
            "sys.mcu.voltage",
            "hw.mcu.voltage",
            "sys.acc.voltage",
            "persist.sys.voltage",
            "vendor.mcu.voltage"
        )

        // корень -> максимальная глубина поиска
        private val SCAN_ROOTS = listOf(
            "/sys/class/power_supply" to 2,
            "/sys/class/mcu" to 1,
            "/sys/class/misc" to 2,
            "/sys/devices/platform" to 3,
            "/proc" to 0
        )

        private val SKIP_NAME_PARTS = listOf("max", "min", "design", "ocv", "limit", "threshold", "cutoff")
    }

    /** Напряжение в вольтах или null, если источник не найден. */
    fun readMcuVoltage(): Float? {
        prefs.getString(PREF_CUSTOM_PATH, null)?.trim()?.takeIf { it.isNotEmpty() }?.let { p ->
            readPath(p)?.let { return it }
        }

        prefs.getString(PREF_WORKING_PATH, null)?.let { p ->
            readPath(p)?.let { return it }
        }

        for (p in STATIC_PATHS) {
            readPath(p)?.let { remember(p); return it }
        }

        for (p in discoveredPaths()) {
            readPath(p)?.let { remember(p); return it }
        }

        for (key in PROP_KEYS) {
            val v = getSystemProperty(key)?.let { normalize(it) }
            if (v != null) return v
        }

        return readBatteryIntent()
    }

    private fun remember(path: String) {
        prefs.edit().putString(PREF_WORKING_PATH, path).apply()
    }

    private fun readPath(path: String): Float? {
        return try {
            val f = File(path)
            if (!f.isFile || !f.canRead()) null else normalize(f.readText())
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Приводит сырое значение к вольтам.
     *  13.8 -> 13.8 | 138 -> 13.8 | 1380 -> 13.8 | 13800 -> 13.8 | 13800000 -> 13.8
     * Напряжение Li-ion батареи (3.0-4.5 В) отбрасывается диапазоном.
     */
    private fun normalize(text: String): Float? {
        val m = Regex("\\d+(?:[.,]\\d+)?").find(text.trim()) ?: return null
        val num = m.value.replace(',', '.').toFloatOrNull() ?: return null
        if (num <= 0f) return null
        val v = when {
            num >= 5_000_000f -> num / 1_000_000f
            num >= 5_000f -> num / 1_000f
            num in 800f..2800f -> num / 100f
            num in 50f..320f -> num / 10f
            else -> num
        }
        return if (v in MIN_V..MAX_V) v else null
    }

    private fun readBatteryIntent(): Float? {
        return try {
            val intent = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            val mv = intent?.getIntExtra(BatteryManager.EXTRA_VOLTAGE, -1) ?: -1
            // < 5000 мВ - это батарея телефона/заглушка, а не бортсеть
            if (mv < 5000) null else normalize(mv.toString())
        } catch (_: Exception) {
            null
        }
    }

    private fun discoveredPaths(): List<String> {
        val now = System.currentTimeMillis()
        if (discovered.isEmpty() && now - lastScan > RESCAN_INTERVAL_MS) {
            lastScan = now
            discovered = discoverVoltageFiles()
        }
        return discovered
    }

    private fun discoverVoltageFiles(): List<String> {
        val out = mutableListOf<String>()
        val counter = intArrayOf(0)
        for ((root, depth) in SCAN_ROOTS) {
            val dir = File(root)
            if (dir.isDirectory) scan(dir, 0, depth, out, counter)
        }
        return out.distinct()
    }

    private fun scan(dir: File, depth: Int, maxDepth: Int, out: MutableList<String>, counter: IntArray) {
        val children = (try { dir.listFiles() } catch (_: Exception) { null }) ?: return
        for (c in children) {
            if (counter[0]++ > MAX_SCANNED_ENTRIES) return
            try {
                if (c.isFile) {
                    val n = c.name.lowercase()
                    val looksLikeVoltage = n.contains("volt") || n.contains("vbat")
                    if (looksLikeVoltage && SKIP_NAME_PARTS.none { n.contains(it) } && c.canRead()) {
                        out.add(c.absolutePath)
                    }
                } else if (c.isDirectory && depth < maxDepth && c.canonicalPath == c.absolutePath) {
                    scan(c, depth + 1, maxDepth, out, counter)
                }
            } catch (_: Exception) {
            }
        }
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

    // ---------------------------------------------------------------- диагностика

    /** Подробный отчёт: что есть на магнитоле и что приложение смогло прочитать. */
    fun dumpReport(): String {
        val sb = StringBuilder()
        sb.appendLine("=== УСТРОЙСТВО ===")
        sb.appendLine("MODEL=${android.os.Build.MODEL} DEVICE=${android.os.Build.DEVICE} BOARD=${android.os.Build.BOARD}")
        sb.appendLine("ANDROID=${android.os.Build.VERSION.RELEASE} SDK=${android.os.Build.VERSION.SDK_INT}")
        sb.appendLine("FINGERPRINT=${android.os.Build.FINGERPRINT}")

        sb.appendLine("\n=== ИЗВЕСТНЫЕ ПУТИ ===")
        for (p in STATIC_PATHS) {
            val f = File(p)
            sb.appendLine("$p exists=${f.exists()} read=${f.canRead()} raw=${safeRead(f)} -> ${if (f.canRead()) normalize(safeRead(f)) else null}")
        }

        sb.appendLine("\n=== НАЙДЕННЫЕ ФАЙЛЫ *volt* ===")
        val found = discoverVoltageFiles()
        if (found.isEmpty()) sb.appendLine("(ничего не найдено/нет доступа)")
        for (p in found) sb.appendLine("$p raw=${safeRead(File(p))} -> ${normalize(safeRead(File(p)))}")

        sb.appendLine("\n=== /sys/class/power_supply ===")
        val ps = try { File("/sys/class/power_supply").listFiles() } catch (_: Exception) { null }
        if (ps == null) sb.appendLine("(нет доступа)")
        ps?.sortedBy { it.name }?.forEach { d ->
            sb.appendLine("${d.name}: type=${safeRead(File(d, "type"))} online=${safeRead(File(d, "online"))} voltage_now=${safeRead(File(d, "voltage_now"))}")
        }

        sb.appendLine("\n=== СИСТЕМНЫЕ СВОЙСТВА ===")
        for (k in PROP_KEYS) sb.appendLine("$k=${getSystemProperty(k)}")

        sb.appendLine("\n=== BATTERY_CHANGED ===")
        try {
            val i = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            sb.appendLine("voltage(mV)=${i?.getIntExtra(BatteryManager.EXTRA_VOLTAGE, -1)} plugged=${i?.getIntExtra(BatteryManager.EXTRA_PLUGGED, -1)}")
        } catch (e: Exception) {
            sb.appendLine("ERR ${e.javaClass.simpleName}")
        }

        sb.appendLine("\n=== getprop (отфильтровано) ===")
        val propRegex = Regex(
            "volt|mcu|acc\\b|batt|topway|syu|canbus|ro\\.product\\.(model|device|board)|ro\\.build\\.(fingerprint|version\\.release)",
            RegexOption.IGNORE_CASE
        )
        runCmd("getprop").lineSequence().filter { propRegex.containsMatchIn(it) }.take(80).forEach { sb.appendLine(it) }

        sb.appendLine("\n=== /dev (mcu, tty, can) ===")
        val devRegex = Regex("mcu|syu|topway|can|ttyS|ttyHS|ttyMT|ttyAMA|ttyUSB", RegexOption.IGNORE_CASE)
        try {
            File("/dev").list()?.filter { devRegex.containsMatchIn(it) }?.sorted()?.forEach { sb.appendLine(it) }
        } catch (_: Exception) {
        }

        sb.appendLine("\n=== ПАКЕТЫ (topway/syu/mcu/canbus) ===")
        try {
            val pkgRegex = Regex("topway|syu|mcu|canbus|carsetting|autolink", RegexOption.IGNORE_CASE)
            context.packageManager.getInstalledApplications(0)
                .map { it.packageName }
                .filter { pkgRegex.containsMatchIn(it) }
                .sorted()
                .forEach { sb.appendLine(it) }
        } catch (e: Exception) {
            sb.appendLine("ERR ${e.javaClass.simpleName}")
        }

        sb.appendLine("\n=== ИТОГ ===")
        sb.appendLine("readMcuVoltage() = ${readMcuVoltage()}")
        sb.appendLine("custom_path = ${prefs.getString(PREF_CUSTOM_PATH, null)}")
        sb.appendLine("working_path = ${prefs.getString(PREF_WORKING_PATH, null)}")
        return sb.toString()
    }

    private fun safeRead(f: File): String {
        return try {
            if (f.isFile && f.canRead()) f.readText().trim().take(64) else "-"
        } catch (e: Exception) {
            "ERR:${e.javaClass.simpleName}"
        }
    }

    private fun runCmd(vararg cmd: String): String {
        return try {
            val p = Runtime.getRuntime().exec(cmd)
            val text = p.inputStream.bufferedReader().readText()
            p.waitFor()
            text
        } catch (e: Exception) {
            "ERR ${e.javaClass.simpleName}"
        }
    }
}
