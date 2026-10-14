package com.huy.screentranslator

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.IBinder
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.TextView
import kotlin.math.abs

class OverlayService : Service() {

    private lateinit var wm: WindowManager
    private var button: TextView? = null
    private var demo: TextView? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        wm = getSystemService(WINDOW_SERVICE) as WindowManager
        startAsForeground()
        addButton()
    }

    private fun startAsForeground() {
        val channelId = "overlay"
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(channelId, "Nút nổi", NotificationManager.IMPORTANCE_LOW)
        )
        val notification = Notification.Builder(this, channelId)
            .setContentTitle("Dịch Màn Hình đang chạy")
            .setContentText("Giữ nút nổi để dịch")
            .setSmallIcon(android.R.drawable.ic_menu_search)
            .build()
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(1, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(1, notification)
        }
    }

    private fun circle(color: Int) = GradientDrawable().apply {
        shape = GradientDrawable.OVAL
        setColor(color)
    }

    private fun addButton() {
        val size = (56 * resources.displayMetrics.density).toInt()
        val blue = Color.parseColor("#E61565C0")
        val green = Color.parseColor("#E62E7D32")

        val tv = TextView(this).apply {
            text = "Dịch"
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            textSize = 12f
            background = circle(blue)
        }
        val lp = WindowManager.LayoutParams(
            size, size,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 20
            y = 400
        }

        tv.setOnTouchListener(object : View.OnTouchListener {
            var startX = 0f
            var startY = 0f
            var origX = 0
            var origY = 0
            var moved = false

            override fun onTouch(v: View, e: MotionEvent): Boolean {
                when (e.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        startX = e.rawX
                        startY = e.rawY
                        origX = lp.x
                        origY = lp.y
                        moved = false
                        tv.background = circle(green)
                        tv.text = "..."
                        showDemo()
                    }
                    MotionEvent.ACTION_MOVE -> {
                        val dx = e.rawX - startX
                        val dy = e.rawY - startY
                        if (abs(dx) > 15 || abs(dy) > 15) moved = true
                        if (moved) {
                            lp.x = origX + dx.toInt()
                            lp.y = origY + dy.toInt()
                            wm.updateViewLayout(tv, lp)
                        }
                    }
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                        tv.background = circle(blue)
                        tv.text = "Dịch"
                        hideDemo()
                    }
                }
                return true
            }
        })

        wm.addView(tv, lp)
        button = tv
    }

    private fun showDemo() {
        if (demo != null) return
        val tv = TextView(this).apply {
            text = "Bản dịch sẽ hiện ở đây\n(demo bước 2)"
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            textSize = 22f
            setBackgroundColor(Color.parseColor("#99000000"))
        }
        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
            PixelFormat.TRANSLUCENT
        )
        wm.addView(tv, lp)
        demo = tv
    }

    private fun hideDemo() {
        demo?.let { wm.removeView(it) }
        demo = null
    }

    override fun onDestroy() {
        hideDemo()
        button?.let { wm.removeView(it) }
        button = null
        super.onDestroy()
    }
}
