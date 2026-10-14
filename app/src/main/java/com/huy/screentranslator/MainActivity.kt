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
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : Activity() {

    private lateinit var keyInput: EditText
    private lateinit var modelInput: EditText
    private lateinit var aiSwitch: Switch
    private lateinit var regionSwitch: Switch
    private lateinit var timingSwitch: Switch
    private lateinit var usageText: TextView

    private fun prefs() = getSharedPreferences("app", MODE_PRIVATE)

    private fun makeSwitch(label: String, key: String, def: Boolean): Switch {
        return Switch(this).apply {
            text = label
            textSize = 16f
            isChecked = prefs().getBoolean(key, def)
            setOnCheckedChangeListener { _, checked ->
                prefs().edit().putBoolean(key, checked).apply()
            }
        }
    }

    private fun usageToday(): Int {
        val today = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())
        val p = prefs()
        return if (p.getString("usage_date", "") == today) p.getInt("usage_count", 0) else 0
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val p = prefs()
        val pad = (24 * resources.displayMetrics.density).toInt()
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad * 2, pad, pad)
        }

        val info = TextView(this).apply {
            text = "Dịch Màn Hình\n\n" +
                "1. Dán Gemini API key vào ô bên dưới.\n" +
                "2. Bấm \"Bật nút nổi\", cấp quyền, chọn chia sẻ TOÀN BỘ màn hình.\n" +
                "3. GIỮ nút xanh dương khoảng 0,2 giây để dịch, thả tay ra để về nguyên trạng.\n" +
                "4. CHẠM ĐÚP nút để mở cài đặt nhanh và chọn vùng cần dịch."
            textSize = 16f
        }
        keyInput = EditText(this).apply {
            hint = "Gemini API key"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            setSingleLine()
            setText(p.getString("gemini_key", ""))
        }
        modelInput = EditText(this).apply {
            hint = "Tên model Gemini"
            setSingleLine()
            setText(p.getString("gemini_model", OverlayService.DEFAULT_MODEL))
        }
        aiSwitch = makeSwitch("Chế độ AI nhìn ảnh (chậm hơn, chính xác hơn)", "mode_ai", false)
        regionSwitch = makeSwitch("Chỉ dịch vùng đã chọn (chọn vùng bằng cách chạm đúp nút nổi)", "region_only", false)
        timingSwitch = makeSwitch("Hiện thời gian xử lý", "show_timing", true)
        usageText = TextView(this).apply { textSize = 14f }

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
        layout.addView(aiSwitch)
        layout.addView(regionSwitch)
        layout.addView(timingSwitch)
        layout.addView(usageText)
        layout.addView(startBtn)
        layout.addView(stopBtn)

        val scroll = ScrollView(this)
        scroll.addView(layout)
        setContentView(scroll)

        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        }
    }

    override fun onResume() {
        super.onResume()
        val p = prefs()
        aiSwitch.isChecked = p.getBoolean("mode_ai", false)
        regionSwitch.isChecked = p.getBoolean("region_only", false)
        timingSwitch.isChecked = p.getBoolean("show_timing", true)
        usageText.text = "Hôm nay đã dùng ${usageToday()} lượt Gemini (tính theo ngày của điện thoại)"
    }

    @Suppress("DEPRECATION")
    private fun startOverlay() {
        val key = keyInput.text.toString().trim()
        if (key.isEmpty()) {
            Toast.makeText(this, "Hãy dán Gemini API key trước", Toast.LENGTH_LONG).show()
            return
        }
        val model = modelInput.text.toString().trim().ifEmpty { OverlayService.DEFAULT_MODEL }
        prefs().edit()
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
