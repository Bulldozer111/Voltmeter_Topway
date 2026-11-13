package com.auto.voltmeter

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.ServiceInfo
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.IBinder
import android.provider.Settings
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.TextView
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale

class VoltOverlayService : Service() {

    private lateinit var windowManager: WindowManager
    private lateinit var overlayView: TextView
    private lateinit var prefs: SharedPreferences
    private var obdManager: ObdManager? = null
    private val serviceScope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // перезапуск системой после убийства процесса (актуально для магнитол)
        return START_STICKY
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onCreate() {
        super.onCreate()
        prefs = getSharedPreferences("volt_prefs", Context.MODE_PRIVATE)
        startInForeground()

        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(this)) {
            stopSelf()
            return
        }

        val bgDrawable = GradientDrawable().apply {
            setColor(Color.parseColor("#B3000000"))
            cornerRadius = 14f
        }

        overlayView = TextView(this).apply {
            text = "--.-V"
            textSize = 15f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.GREEN)
            background = bgDrawable
            setPadding(18, 4, 18, 4)
        }

        val layoutFlag = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_SYSTEM_ALERT
        }

        val savedX = prefs.getInt("overlay_x", 120)
        val savedY = prefs.getInt("overlay_y", 4)

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            layoutFlag,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = savedX
            y = savedY
        }

        overlayView.setOnTouchListener(object : View.OnTouchListener {
            private var initialX = 0
            private var initialY = 0
            private var initialTouchX = 0f
            private var initialTouchY = 0f

            override fun onTouch(v: View, event: MotionEvent): Boolean {
                when (event.action) {
                    MotionEvent.ACTION_DOWN -> {
                        initialX = params.x
                        initialY = params.y
                        initialTouchX = event.rawX
                        initialTouchY = event.rawY
                        return true
                    }
                    MotionEvent.ACTION_MOVE -> {
                        params.x = initialX + (event.rawX - initialTouchX).toInt()
                        params.y = initialY + (event.rawY - initialTouchY).toInt()
                        try { windowManager.updateViewLayout(overlayView, params) } catch (_: Exception) {}
                        return true
                    }
                    MotionEvent.ACTION_UP -> {
                        prefs.edit()
                            .putInt("overlay_x", params.x)
                            .putInt("overlay_y", params.y)
                            .apply()
                        return true
                    }
                }
                return false
            }
        })

        try {
            windowManager.addView(overlayView, params)
        } catch (_: Exception) {
            // нет права на оверлей / прошивка запретила - не роняем приложение
            stopSelf()
            return
        }
        startMonitoring()
    }

    private fun startMonitoring() {
        val mode = prefs.getString("source_mode", SourceMode.HEAD_UNIT_MCU.name)
        serviceScope.launch {
            if (mode == SourceMode.OBD_ELM327.name) runObdLoop() else runInternalLoop()
        }
    }

    private suspend fun runInternalLoop() {
        val reader = InternalVoltageReader(this)
        while (true) {
            // чтение файлов - не на главном потоке
            val v = withContext(Dispatchers.IO) {
                try { reader.readMcuVoltage() } catch (_: Exception) { null }
            }
            updateDisplay(v)
            delay(1000)
        }
    }

    private suspend fun runObdLoop() {
        val macAddress = prefs.getString("selected_mac", null)
        if (macAddress.isNullOrEmpty()) {
            show("NO MAC", Color.YELLOW)
            return
        }

        val adapter = (getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter

        while (true) {
            try {
                if (adapter == null || !adapter.isEnabled) {
                    show("BT OFF", Color.YELLOW)
                } else {
                    show("OBD...", Color.CYAN)
                    val device = adapter.getRemoteDevice(macAddress)
                    val obd = ObdManager(device)
                    obdManager = obd

                    if (obd.connect()) {
                        var fails = 0
                        while (true) {
                            val v = obd.readVoltage()
                            if (v != null) {
                                fails = 0
                                updateDisplay(v)
                            } else if (++fails >= 3) {
                                break // потеряли связь - переподключаемся
                            }
                            delay(1500)
                        }
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
            }

            obdManager?.disconnect()
            show("WAIT", Color.GRAY)
            delay(5000)
        }
    }

    private fun show(text: String, color: Int) {
        overlayView.text = text
        overlayView.setTextColor(color)
    }

    private fun updateDisplay(rawVolt: Float?) {
        if (rawVolt != null && rawVolt > 0f) {
            val offset = prefs.getFloat("calibration_offset", 0.0f)
            val finalVolt = rawVolt + offset

            overlayView.text = String.format(Locale.US, "%.1fV", finalVolt)
            when {
                finalVolt < 12.1f -> overlayView.setTextColor(Color.parseColor("#FF5252"))
                finalVolt > 14.8f -> overlayView.setTextColor(Color.parseColor("#FFD740"))
                else -> overlayView.setTextColor(Color.parseColor("#69F0AE"))
            }
        } else {
            overlayView.text = "--.-V"
            overlayView.setTextColor(Color.LTGRAY)
        }
    }

    private fun startInForeground() {
        val channelId = "volt_overlay_channel"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                channelId,
                "Мониторинг бортовой сети",
                NotificationManager.IMPORTANCE_LOW
            )
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }

        val notification: Notification = NotificationCompat.Builder(this, channelId)
            .setContentTitle("Вольтметр активен")
            .setContentText("Отображение напряжения в верхней панели")
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setOngoing(true)
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(101, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(101, notification)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        serviceScope.cancel()
        obdManager?.disconnect()
        if (::overlayView.isInitialized) {
            try { windowManager.removeView(overlayView) } catch (_: Exception) {}
        }
    }
}
