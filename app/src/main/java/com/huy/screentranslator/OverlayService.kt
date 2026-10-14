package com.huy.screentranslator

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.content.res.Configuration
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
import android.os.SystemClock
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
import org.json.JSONException
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors
import kotlin.math.abs

private class Block(val text: String, val box: Rect)

private class RetryException(message: String) : Exception(message)

class OverlayService : Service() {

    companion object {
        const val DEFAULT_MODEL = "gemini-flash-lite-latest"
        private const val CHUNK = 30
        private const val SYSTEM_PROMPT =
            "You are a phone-screen translator. The user message is a JSON array of text blocks " +
                "read by OCR from ONE phone screen (could be a game, app, website, comic speech bubbles, chat...). " +
                "Blocks are in reading order. Translate every English block into natural Vietnamese, " +
                "using the whole screen as context and keeping terminology consistent. " +
                "Return ONLY a JSON array of strings with exactly the same length and order as the input. " +
                "Never return English text or the original text unchanged for an English block. " +
                "If a block is already Vietnamese (OCR may have dropped its diacritics), or is only " +
                "numbers, symbols, a code or a proper name that should stay unchanged, return an empty " +
                "string for that block. Keep each translation concise, because it will be drawn in the " +
                "same box as the original text."
        private const val VI_CHARS =
            "ăâđêôơưĂÂĐÊÔƠƯàáảãạằắẳẵặầấẩẫậèéẻẽẹềếểễệìíỉĩịòóỏõọồốổỗộờớởỡợùúủũụừứửữựỳýỷỹỵ" +
                "ÀÁẢÃẠẰẮẲẴẶẦẤẨẪẬÈÉẺẼẸỀẾỂỄỆÌÍỈĨỊÒÓỎÕỌỒỐỔỖỘỜỚỞỠỢÙÚỦŨỤỪỨỬỮỰỲÝỶỸỴ"
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
    private var dpi = 0

    @Volatile private var lastFrameTime = 0L

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

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        mainHandler.postDelayed({ resizeCapture() }, 300)
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

    private fun readScreenSize() {
        val metrics = DisplayMetrics()
        @Suppress("DEPRECATION")
        wm.defaultDisplay.getRealMetrics(metrics)
        screenW = metrics.widthPixels
        screenH = metrics.heightPixels
        dpi = metrics.densityDpi
    }

    private fun makeReader(w: Int, h: Int): ImageReader {
        val reader = ImageReader.newInstance(w, h, PixelFormat.RGBA_8888, 3)
        reader.setOnImageAvailableListener({ r ->
            val img = try {
                r.acquireLatestImage()
            } catch (e: Exception) {
                null
            }
            if (img != null) {
                lastFrameTime = SystemClock.uptimeMillis()
                synchronized(lock) {
                    latest?.close()
                    latest = img
                }
            }
        }, bgHandler)
        return reader
    }

    private fun startCapture(code: Int, data: Intent) {
        readScreenSize()

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

        val reader = makeReader(screenW, screenH)
        virtualDisplay = mp.createVirtualDisplay(
            "screen-translator", screenW, screenH, dpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            reader.surface, null, bgHandler
        )
        projection = mp
        imageReader = reader
    }

    private fun resizeCapture() {
        if (projection == null) return
        val oldW = screenW
        val oldH = screenH
        readScreenSize()
        if (screenW == oldW && screenH == oldH) return

        val oldReader = imageReader
        val reader = makeReader(screenW, screenH)
        virtualDisplay?.resize(screenW, screenH, dpi)
        virtualDisplay?.surface = reader.surface
        imageReader = reader
        synchronized(lock) {
            latest?.close()
            latest = null
        }
        oldReader?.close()

        val lp = buttonLp
        val b = button
        if (lp != null && b != null) {
            lp.x = lp.x.coerceIn(0, maxOf(0, screenW - lp.width))
            lp.y = lp.y.coerceIn(0, maxOf(0, screenH - lp.height))
            wm.updateViewLayout(b, lp)
        }
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
        val startedAt = SystemClock.uptimeMillis()
        bgHandler.post {
            waitForSettle(id, startedAt) { grabAndProcess(id) }
        }
    }

    private fun onHoldEnd() {
        holding = false
        requestId += 1
        hideOverlay()
    }

    private fun waitForSettle(id: Int, startedAt: Long, then: () -> Unit) {
        if (!holding || id != requestId) return
        val now = SystemClock.uptimeMillis()
        val quiet = now - lastFrameTime
        if (quiet >= 150 || now - startedAt >= 700) {
            then()
            return
        }
        bgHandler.postDelayed({ waitForSettle(id, startedAt, then) }, 50)
    }

    private fun grabAndProcess(id: Int) {
        if (!holding || id != requestId) return
        val bmp = grabBitmap()
        val scale = if (bmp != null && bmp.width <= 800) 2f else 1.5f
        val scaled = if (bmp != null) {
            Bitmap.createScaledBitmap(
                bmp, (bmp.width * scale).toInt(), (bmp.height * scale).toInt(), true
            )
        } else {
            null
        }
        mainHandler.post {
            if (!holding || id != requestId) return@post
            showOverlay("Đang dịch...")
            if (bmp == null || scaled == null) {
                setBanner("Chưa chụp được màn hình, thử lại")
                return@post
            }
            runOcr(bmp, scaled, scale, id)
        }
    }

    private fun looksVietnamese(s: String): Boolean = s.any { VI_CHARS.indexOf(it) >= 0 }

    private fun norm(s: String): String = s.lowercase().filter { it.isLetterOrDigit() }

    private fun runOcr(bmp: Bitmap, scaled: Bitmap, scale: Float, id: Int) {
        val lp = buttonLp
        val btnRect = if (lp != null) {
            Rect(lp.x - 10, lp.y - 10, lp.x + lp.width + 10, lp.y + lp.height + 10)
        } else {
            Rect()
        }

        recognizer.process(InputImage.fromBitmap(scaled, 0))
            .addOnSuccessListener { result ->
                if (!holding || id != requestId) return@addOnSuccessListener
                val blocks = ArrayList<Block>()
                for (b in result.textBlocks) {
                    val rb = b.boundingBox ?: continue
                    val box = Rect(
                        (rb.left / scale).toInt(), (rb.top / scale).toInt(),
                        (rb.right / scale).toInt(), (rb.bottom / scale).toInt()
                    )
                    val text = b.text.replace("\n", " ").trim()
                    if (text.length < 2 || !text.any { it.isLetter() }) continue
                    if (looksVietnamese(text)) continue
                    if (Rect.intersects(box, btnRect)) continue
                    blocks.add(Block(text, box))
                }
                if (blocks.isEmpty()) {
                    setBanner("Không có chữ nước ngoài nào để dịch")
                    return@addOnSuccessListener
                }
                blocks.sortWith(compareBy<Block>({ it.box.top / 40 }, { it.box.left }))
                netExecutor.execute { translate(blocks, bmp, id) }
            }
            .addOnFailureListener {
                if (holding && id == requestId) setBanner("Lỗi khi đọc chữ trên màn hình")
            }
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
            val all = ArrayList<String>()
            var from = 0
            while (from < blocks.size) {
                if (!holding || id != requestId) return
                val chunk = blocks.subList(from, minOf(from + CHUNK, blocks.size))
                all.addAll(callWithRetry(key, model, chunk.map { it.text }))
                from += CHUNK
            }
            mainHandler.post {
                if (holding && id == requestId) drawTranslations(blocks, all, bmp)
            }
        } catch (e: Exception) {
            showMessage(e.message ?: "Lỗi khi dịch")
        }
    }

    private fun callWithRetry(key: String, model: String, texts: List<String>): List<String> {
        var last: Exception? = null
        for (attempt in 1..2) {
            try {
                return callGemini(key, model, texts)
            } catch (e: RetryException) {
                last = e
            }
        }
        throw Exception(last?.message ?: "Lỗi khi dịch")
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
                when {
                    code == 429 -> throw Exception("Hết lượt miễn phí, hãy thử lại sau")
                    code in 500..599 -> throw RetryException("Gemini đang bận ($code)")
                    code == 404 -> throw Exception("Không tìm thấy model, hãy kiểm tra tên model")
                    else -> throw Exception("API key hoặc tên model bị từ chối (lỗi $code)")
                }
            }
            val arr: JSONArray = try {
                val resp = conn.inputStream.bufferedReader().use { it.readText() }
                val text = JSONObject(resp)
                    .getJSONArray("candidates").getJSONObject(0)
                    .getJSONObject("content")
                    .getJSONArray("parts").getJSONObject(0)
                    .getString("text")
                JSONArray(text)
            } catch (e: JSONException) {
                throw RetryException("Gemini trả sai định dạng")
            }
            if (arr.length() != texts.size) {
                throw RetryException("Gemini trả thiếu kết quả")
            }
            return List(texts.size) { i -> arr.optString(i, "") }
        } catch (e: IOException) {
            throw RetryException("Lỗi mạng, thử lại")
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
            val t = results.getOrElse(i) { "" }.trim()
            if (t.isEmpty()) continue
            if (norm(t) == norm(blocks[i].text)) continue
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
            val w = maxOf(box.width() + 12, 40)
            val h = maxOf(box.height() + 6, 30)
            val lp = FrameLayout.LayoutParams(w, h)
            lp.leftMargin = maxOf(box.left - 6, 0)
            lp.topMargin = maxOf(box.top - 3, 0)
            root.addView(tv, lp)
            drawn++
        }
        if (drawn == 0) {
            setBanner("Không có gì cần dịch trên màn hình này")
            return
        }
        val label = TextView(this).apply {
            text = "Đã dịch $drawn/${blocks.size} khối"
            setTextColor(Color.WHITE)
            textSize = 12f
            setPadding(16, 8, 16, 8)
            setBackgroundColor(Color.parseColor("#B3000000"))
        }
        val llp = FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.WRAP_CONTENT,
            FrameLayout.LayoutParams.WRAP_CONTENT,
            Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
        )
        llp.bottomMargin = 160
        root.addView(label, llp)
    }

    override fun onDestroy() {
        holding = false
        mainHandler.removeCallbacksAndMessages(null)
        if (::bgHandler.isInitialized) bgHandler.removeCallbacksAndMessages(null)
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
