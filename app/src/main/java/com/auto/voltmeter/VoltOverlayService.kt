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
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.IBinder
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.TextView
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.*

class VoltOverlayService : Service() {

    private lateinit var windowManager: WindowManager
    private lateinit var overlayView: TextView
    private lateinit var prefs: SharedPreferences
    private var obdManager: ObdManager? = null
    private val serviceScope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    override fun onBind(intent: Intent?): IBinder? = null

    @SuppressLint("ClickableViewAccessibility")
    override fun onCreate() {
        super.onCreate()
        prefs = getSharedPreferences("volt_prefs", Context.MODE_PRIVATE)
        startInForeground()

        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager

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
                        windowManager.updateViewLayout(overlayView, params)
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

        windowManager.addView(overlayView, params)
        startMonitoring()
    }

    private fun startMonitoring() {
        val mode = prefs.getString("source_mode", SourceMode.HEAD_UNIT_MCU.name)
        val internalReader = InternalVoltageReader(this)

        serviceScope.launch {
            if (mode == SourceMode.OBD_ELM327.name) {
                val macAddress = prefs.getString("selected_mac", null)
                if (macAddress.isNullOrEmpty()) {
                    overlayView.text = "NO MAC"
                    overlayView.setTextColor(Color.YELLOW)
                    return@launch
                }

                val btManager = getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
                val adapter = btManager.adapter

                while (isActive) {
                    try {
                        overlayView.text = "OBD..."
                        overlayView.setTextColor(Color.CYAN)

                        val device = adapter.getRemoteDevice(macAddress)
                        obdManager = ObdManager(device)

                        if (obdManager?.connect() == true) {
                            while (isActive) {
                                val v = obdManager?.readVoltage()
                                if (v != null) {
                                    updateDisplay(v)
                                } else {
                                    break
                                }
                                delay(1500)
                            }
                        }
                    } catch (_: Exception) {}

                    obdManager?.disconnect()
                    overlayView.text = "WAIT"
                    overlayView.setTextColor(Color.GRAY)
                    delay(5000)
                }
            } else {
                while (isActive) {
                    val v = internalReader.readMcuVoltage()
                    updateDisplay(v)
                    delay(1000)
                }
            }
        }
    }

    private fun updateDisplay(rawVolt: Float?) {
        if (rawVolt != null && rawVolt > 0f) {
            val offset = prefs.getFloat("calibration_offset", 0.0f)
            val finalVolt = rawVolt + offset

            overlayView.text = String.format("%.1fV", finalVolt)
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

        startForeground(101, notification)
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
