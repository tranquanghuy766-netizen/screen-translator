package com.huy.screentranslator

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.drawable.GradientDrawable
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.Image
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.Looper
import android.util.DisplayMetrics
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.TextView
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors
import kotlin.math.abs

private class Block(val text: String, val box: Rect)

class OverlayService : Service() {

    companion object {
        const val DEFAULT_MODEL = "gemini-flash-lite-latest"
        private const val SYSTEM_PROMPT =
            "You are a phone-screen translator. The user message is a JSON array of text blocks " +
                "read by OCR from ONE phone screen (could be a game, app, website, comic, chat...). " +
                "Translate every block into natural Vietnamese, using the whole screen as context " +
                "and keeping terminology consistent. Return ONLY a JSON array of strings with exactly " +
                "the same length and order as the input. If a block is already Vietnamese, or is only " +
                "numbers, symbols, a code or a proper name that should stay unchanged, return an empty " +
                "string for that block. Keep each translation concise, because it will be drawn in the " +
                "same box as the original text."
    }

    private lateinit var wm: WindowManager
    private var button: TextView? = null
    private var buttonLp: WindowManager.LayoutParams? = null
    private var overlay: FrameLayout? = null

    private var projection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null
    private var latest: Image? = null
    private val lock = Any()
    private var screenW = 0
    private var screenH = 0

    private val mainHandler = Handler(Looper.getMainLooper())
    private lateinit var bgThread: HandlerThread
    private lateinit var bgHandler: Handler
    private val netExecutor = Executors.newSingleThreadExecutor()
    private val recognizer by lazy { TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS) }

    @Volatile private var holding = false
    @Volatile private var requestId = 0

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        wm = getSystemService(WINDOW_SERVICE) as WindowManager
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (projection != null) return START_NOT_STICKY
        val code = intent?.getIntExtra("code", 0) ?: 0
        @Suppress("DEPRECATION")
        val data = intent?.getParcelableExtra<Intent>("data")
        if (data == null) {
            stopSelf()
            return START_NOT_STICKY
        }
        startAsForeground()
        try {
            startCapture(code, data)
        } catch (e: Exception) {
            stopSelf()
            return START_NOT_STICKY
        }
        addButton()
        return START_NOT_STICKY
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
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(1, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
        } else {
            startForeground(1, notification)
        }
    }

    private fun startCapture(code: Int, data: Intent) {
        val metrics = DisplayMetrics()
        @Suppress("DEPRECATION")
        wm.defaultDisplay.getRealMetrics(metrics)
        screenW = metrics.widthPixels
        screenH = metrics.heightPixels

        bgThread = HandlerThread("capture")
        bgThread.start()
        bgHandler = Handler(bgThread.looper)

        val mpm = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        val mp = mpm.getMediaProjection(code, data)
        mp.registerCallback(object : MediaProjection.Callback() {
            override fun onStop() {
                mainHandler.post { stopSelf() }
            }
        }, bgHandler)

        val reader = ImageReader.newInstance(screenW, screenH, PixelFormat.RGBA_8888, 3)
        reader.setOnImageAvailableListener({ r ->
            val img = try {
                r.acquireLatestImage()
            } catch (e: Exception) {
                null
            }
            if (img != null) {
                synchronized(lock) {
                    latest?.close()
                    latest = img
                }
            }
        }, bgHandler)

        virtualDisplay = mp.createVirtualDisplay(
            "screen-translator", screenW, screenH, metrics.densityDpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            reader.surface, null, bgHandler
        )
        projection = mp
        imageReader = reader
    }

    private fun grabBitmap(): Bitmap? {
        var result: Bitmap? = null
        synchronized(lock) {
            val img = latest
            if (img != null) {
                val plane = img.planes[0]
                val buffer = plane.buffer
                buffer.rewind()
                val pixelStride = plane.pixelStride
                val rowStride = plane.rowStride
                val padding = rowStride - pixelStride * img.width
                val wide = Bitmap.createBitmap(
                    img.width + padding / pixelStride, img.height, Bitmap.Config.ARGB_8888
                )
                wide.copyPixelsFromBuffer(buffer)
                result = if (padding == 0) wide else Bitmap.createBitmap(wide, 0, 0, img.width, img.height)
            }
        }
        return result
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
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 20
            y = 400
        }
        if (Build.VERSION.SDK_INT >= 28) {
            lp.layoutInDisplayCutoutMode =
                WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
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
                        onHoldStart()
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
                        onHoldEnd()
                    }
                }
                return true
            }
        })

        wm.addView(tv, lp)
        button = tv
        buttonLp = lp
    }

    private fun onHoldStart() {
        holding = true
        requestId += 1
        val id = requestId

        val bmp = grabBitmap()
        showOverlay("Đang dịch...")
        if (bmp == null) {
            setBanner("Chưa chụp được màn hình, thử lại")
            return
        }

        val lp = buttonLp
        val btnRect = if (lp != null) {
            Rect(lp.x - 10, lp.y - 10, lp.x + lp.width + 10, lp.y + lp.height + 10)
        } else {
            Rect()
        }

        recognizer.process(InputImage.fromBitmap(bmp, 0))
            .addOnSuccessListener { result ->
                if (!holding || id != requestId) return@addOnSuccessListener
                val blocks = ArrayList<Block>()
                for (b in result.textBlocks) {
                    val box = b.boundingBox ?: continue
                    val text = b.text.replace("\n", " ").trim()
                    if (text.length < 2 || !text.any { it.isLetter() }) continue
                    if (Rect.intersects(box, btnRect)) continue
                    blocks.add(Block(text, box))
                }
                if (blocks.isEmpty()) {
                    setBanner("Không thấy chữ nào để dịch")
                    return@addOnSuccessListener
                }
                netExecutor.execute { translate(blocks, bmp, id) }
            }
            .addOnFailureListener {
                if (holding && id == requestId) setBanner("Lỗi khi đọc chữ trên màn hình")
            }
    }

    private fun onHoldEnd() {
        holding = false
        requestId += 1
        hideOverlay()
    }

    private fun translate(blocks: List<Block>, bmp: Bitmap, id: Int) {
        try {
            val prefs = getSharedPreferences("app", MODE_PRIVATE)
            val key = prefs.getString("gemini_key", "") ?: ""
            val model = prefs.getString("gemini_model", DEFAULT_MODEL) ?: DEFAULT_MODEL
            if (key.isEmpty()) {
                showMessage("Chưa có API key")
                return
            }
            val results = callGemini(key, model, blocks.map { it.text })
            mainHandler.post {
                if (holding && id == requestId) drawTranslations(blocks, results, bmp)
            }
        } catch (e: Exception) {
            showMessage(e.message ?: "Lỗi khi dịch")
        }
    }

    private fun callGemini(key: String, model: String, texts: List<String>): List<String> {
        val body = JSONObject()
            .put(
                "systemInstruction",
                JSONObject().put("parts", JSONArray().put(JSONObject().put("text", SYSTEM_PROMPT)))
            )
            .put(
                "contents",
                JSONArray().put(
                    JSONObject().put("role", "user").put(
                        "parts",
                        JSONArray().put(JSONObject().put("text", JSONArray(texts).toString()))
                    )
                )
            )
            .put(
                "generationConfig",
                JSONObject()
                    .put("responseMimeType", "application/json")
                    .put(
                        "responseSchema",
                        JSONObject().put("type", "ARRAY").put("items", JSONObject().put("type", "STRING"))
                    )
                    .put("temperature", 0.2)
            )

        val url = URL("https://generativelanguage.googleapis.com/v1beta/models/$model:generateContent")
        val conn = url.openConnection() as HttpURLConnection
        try {
            conn.requestMethod = "POST"
            conn.connectTimeout = 8000
            conn.readTimeout = 20000
            conn.setRequestProperty("Content-Type", "application/json")
            conn.setRequestProperty("x-goog-api-key", key)
            conn.doOutput = true
            conn.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }

            val code = conn.responseCode
            if (code != 200) {
                val msg = when (code) {
                    429 -> "Hết lượt miễn phí, hãy thử lại sau"
                    400, 403 -> "API key hoặc tên model bị từ chối (lỗi $code)"
                    404 -> "Không tìm thấy model, hãy kiểm tra tên model"
                    else -> "Lỗi Gemini ($code)"
                }
                throw Exception(msg)
            }
            val resp = conn.inputStream.bufferedReader().use { it.readText() }
            val text = JSONObject(resp)
                .getJSONArray("candidates").getJSONObject(0)
                .getJSONObject("content")
                .getJSONArray("parts").getJSONObject(0)
                .getString("text")
            val arr = JSONArray(text)
            return List(texts.size) { i -> if (i < arr.length()) arr.optString(i, "") else "" }
        } finally {
            conn.disconnect()
        }
    }

    private fun showOverlay(msg: String) {
        if (overlay == null) {
            val root = FrameLayout(this)
            val lp = WindowManager.LayoutParams(
                screenW, screenH,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT
            )
            lp.gravity = Gravity.TOP or Gravity.START
            if (Build.VERSION.SDK_INT >= 28) {
                lp.layoutInDisplayCutoutMode =
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
            wm.addView(root, lp)
            overlay = root
        }
        setBanner(msg)
    }

    private fun setBanner(msg: String) {
        val root = overlay ?: return
        root.removeAllViews()
        val tv = TextView(this).apply {
            text = msg
            setTextColor(Color.WHITE)
            textSize = 16f
            setPadding(32, 16, 32, 16)
            setBackgroundColor(Color.parseColor("#CC000000"))
        }
        val lp = FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.WRAP_CONTENT,
            FrameLayout.LayoutParams.WRAP_CONTENT,
            Gravity.TOP or Gravity.CENTER_HORIZONTAL
        )
        lp.topMargin = 120
        root.addView(tv, lp)
    }

    private fun showMessage(msg: String) {
        mainHandler.post { if (holding) setBanner(msg) }
    }

    private fun hideOverlay() {
        overlay?.let { wm.removeView(it) }
        overlay = null
    }

    private fun sampleColor(bmp: Bitmap, r: Rect): Int {
        val xs = intArrayOf(r.left - 3, r.right + 3)
        val ys = intArrayOf(r.top - 3, r.bottom + 3)
        var rs = 0
        var gs = 0
        var bs = 0
        var n = 0
        for (x in xs) {
            for (y in ys) {
                val px = x.coerceIn(0, bmp.width - 1)
                val py = y.coerceIn(0, bmp.height - 1)
                val c = bmp.getPixel(px, py)
                rs += Color.red(c)
                gs += Color.green(c)
                bs += Color.blue(c)
                n++
            }
        }
        return Color.rgb(rs / n, gs / n, bs / n)
    }

    private fun isDark(color: Int): Boolean {
        val lum = 0.299 * Color.red(color) + 0.587 * Color.green(color) + 0.114 * Color.blue(color)
        return lum < 140
    }

    private fun drawTranslations(blocks: List<Block>, results: List<String>, bmp: Bitmap) {
        val root = overlay ?: return
        root.removeAllViews()
        var drawn = 0
        for (i in blocks.indices) {
            val t = results[i].trim()
            if (t.isEmpty()) continue
            val box = blocks[i].box
            val bgColor = sampleColor(bmp, box)
            val textColor = if (isDark(bgColor)) Color.WHITE else Color.BLACK
            val tv = TextView(this).apply {
                text = t
                setTextColor(textColor)
                setBackgroundColor(bgColor)
                gravity = Gravity.CENTER_VERTICAL or Gravity.START
                setPadding(4, 2, 4, 2)
                setAutoSizeTextTypeUniformWithConfiguration(8, 24, 1, TypedValue.COMPLEX_UNIT_SP)
            }
            val w = maxOf(box.width(), 40)
            val h = maxOf(box.height(), 30)
            val lp = FrameLayout.LayoutParams(w, h)
            lp.leftMargin = box.left
            lp.topMargin = box.top
            root.addView(tv, lp)
            drawn++
        }
        if (drawn == 0) setBanner("Không có gì cần dịch trên màn hình này")
    }

    override fun onDestroy() {
        holding = false
        hideOverlay()
        button?.let { wm.removeView(it) }
        button = null
        virtualDisplay?.release()
        imageReader?.close()
        synchronized(lock) {
            latest?.close()
            latest = null
        }
        projection?.stop()
        projection = null
        if (::bgThread.isInitialized) bgThread.quitSafely()
        netExecutor.shutdownNow()
        recognizer.close()
        super.onDestroy()
    }
}
