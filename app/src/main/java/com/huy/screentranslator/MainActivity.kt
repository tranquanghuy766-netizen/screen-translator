package com.huy.screentranslator

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.text.InputType
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast

class MainActivity : Activity() {

    private lateinit var keyInput: EditText
    private lateinit var modelInput: EditText

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val prefs = getSharedPreferences("app", MODE_PRIVATE)
        val pad = (24 * resources.displayMetrics.density).toInt()
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad * 2, pad, pad)
        }

        val info = TextView(this).apply {
            text = "Dịch Màn Hình\n\n" +
                "1. Dán Gemini API key vào ô bên dưới.\n" +
                "2. Bấm \"Bật nút nổi\", cấp quyền, chọn chia sẻ TOÀN BỘ màn hình.\n" +
                "3. Mở màn hình cần dịch, GIỮ nút xanh dương cho tới khi thấy bản dịch. Thả tay ra để về nguyên trạng."
            textSize = 16f
        }
        keyInput = EditText(this).apply {
            hint = "Gemini API key"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            setSingleLine()
            setText(prefs.getString("gemini_key", ""))
        }
        modelInput = EditText(this).apply {
            hint = "Tên model Gemini"
            setSingleLine()
            setText(prefs.getString("gemini_model", OverlayService.DEFAULT_MODEL))
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
        layout.addView(keyInput)
        layout.addView(modelInput)
        layout.addView(startBtn)
        layout.addView(stopBtn)
        setContentView(layout)

        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        }
    }

    @Suppress("DEPRECATION")
    private fun startOverlay() {
        val key = keyInput.text.toString().trim()
        if (key.isEmpty()) {
            Toast.makeText(this, "Hãy dán Gemini API key trước", Toast.LENGTH_LONG).show()
            return
        }
        val model = modelInput.text.toString().trim().ifEmpty { OverlayService.DEFAULT_MODEL }
        getSharedPreferences("app", MODE_PRIVATE).edit()
            .putString("gemini_key", key)
            .putString("gemini_model", model)
            .apply()

        if (!Settings.canDrawOverlays(this)) {
            Toast.makeText(this, "Hãy bật quyền \"Hiển thị trên ứng dụng khác\" rồi quay lại bấm lần nữa", Toast.LENGTH_LONG).show()
            startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
            return
        }

        val mpm = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        startActivityForResult(mpm.createScreenCaptureIntent(), 100)
    }

    @Suppress("DEPRECATION")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == 100) {
            if (resultCode == RESULT_OK && data != null) {
                val intent = Intent(this, OverlayService::class.java)
                    .putExtra("code", resultCode)
                    .putExtra("data", data)
                startForegroundService(intent)
                Toast.makeText(this, "Đã bật. Thoát ra và giữ nút nổi để dịch", Toast.LENGTH_LONG).show()
            } else {
                Toast.makeText(this, "Bạn đã từ chối quyền chụp màn hình", Toast.LENGTH_LONG).show()
            }
        }
    }
}
