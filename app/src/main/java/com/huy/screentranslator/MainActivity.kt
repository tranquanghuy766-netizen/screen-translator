package com.huy.screentranslator

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast

class MainActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val pad = (24 * resources.displayMetrics.density).toInt()
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad * 2, pad, pad)
        }

        val info = TextView(this).apply {
            text = "Bước 2: thử nút nổi.\n\nBấm \"Bật nút nổi\", cấp quyền hiển thị trên ứng dụng khác, " +
                "rồi thoát ra màn hình chính. Giữ nút xanh để thấy lớp phủ demo, thả ra thì lớp phủ biến mất."
            textSize = 16f
        }
        val startBtn = Button(this).apply {
            text = "Bật nút nổi"
            setOnClickListener { startOverlay() }
        }
        val stopBtn = Button(this).apply {
            text = "Tắt nút nổi"
            setOnClickListener { stopService(Intent(this@MainActivity, OverlayService::class.java)) }
        }

        layout.addView(info)
        layout.addView(startBtn)
        layout.addView(stopBtn)
        setContentView(layout)
    }

    private fun startOverlay() {
        if (!Settings.canDrawOverlays(this)) {
            Toast.makeText(this, "Hãy bật quyền \"Hiển thị trên ứng dụng khác\" rồi quay lại bấm lần nữa", Toast.LENGTH_LONG).show()
            startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
            return
        }
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        }
        startForegroundService(Intent(this, OverlayService::class.java))
    }
}
