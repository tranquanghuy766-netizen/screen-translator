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
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.Typeface
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
import android.util.Base64
import android.util.DisplayMetrics
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.text.Normalizer
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors
import kotlin.math.abs

private class Block(val text: String, val box: Rect)

private class AiItem(
    val xmin: Float,
    val ymin: Float,
    val xmax: Float,
    val ymax: Float,
    val text: String
)

private class RetryException(message: String) : Exception(message)

private class RegionView(ctx: Context, private val onDone: (Rect?) -> Unit) : View(ctx) {
    private val stroke = Paint().apply {
        color = Color.WHITE
        style = Paint.Style.STROKE
        strokeWidth = 6f
    }
    private val fill = Paint().apply {
        color = Color.parseColor("#3342A5F5")
        style = Paint.Style.FILL
    }
    private var sx = 0f
    private var sy = 0f
    private var ex = 0f
    private var ey = 0f
    private var drawing = false

    private fun rect() = Rect(
        minOf(sx, ex).toInt(), minOf(sy, ey).toInt(),
        maxOf(sx, ex).toInt(), maxOf(sy, ey).toInt()
    )

    override fun onDraw(canvas: Canvas) {
        if (drawing) {
            val r = rect()
            canvas.drawRect(r, fill)
            canvas.drawRect(r, stroke)
        }
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                sx = e.x
                sy = e.y
                ex = sx
                ey = sy
                drawing = true
                invalidate()
            }
            MotionEvent.ACTION_MOVE -> {
                ex = e.x
                ey = e.y
                invalidate()
            }
            MotionEvent.ACTION_UP -> {
                ex = e.x
                ey = e.y
                drawing = false
                onDone(rect())
            }
            MotionEvent.ACTION_CANCEL -> {
                drawing = false
                onDone(null)
            }
        }
        return true
    }
}

class OverlayService : Service() {

    companion object {
        const val DEFAULT_MODEL = "gemini-flash-lite-latest"
        private const val HOLD_MS = 200L
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
                "string for that block. Write Vietnamese in normal sentence case, not ALL CAPS, " +
                "unless it is a very short label. Keep each translation concise, because it will be drawn " +
                "in the same box as the original text."
        private const val AI_PROMPT =
            "You are a phone-screen translator. The image is a screenshot (it may be manga, a comic, a game, " +
                "an app, a website or a chat). Find every block of foreign-language text. A whole speech bubble, " +
                "caption, paragraph, button label or list item is ONE block: never split one sentence into several " +
                "blocks. For each block return box_2d = [ymin, xmin, ymax, xmax] normalized to 0-1000, covering " +
                "the ENTIRE bubble or block interior including all of its text (slightly generous), and vi = a " +
                "natural Vietnamese translation of the whole block, using the whole image as context. " +
                "Skip text that is already Vietnamese, sound effects drawn as art (onomatopoeia), watermarks, " +
                "logos, and pure numbers or symbols. Write Vietnamese in normal sentence case, not ALL CAPS, " +
                "unless it is a very short label. Keep each translation concise so it fits the box. " +
                "Return ONLY the JSON array. If there is nothing to translate return []."
        private const val VI_CHARS =
            "ăâđêôơưĂÂĐÊÔƠƯàáảãạằắẳẵặầấẩẫậèéẻẽẹềếểễệìíỉĩịòóỏõọồốổỗộờớởỡợùúủũụừứửữựỳýỷỹỵ" +
                "ÀÁẢÃẠẰẮẲẴẶẦẤẨẪẬÈÉẺẼẸỀẾỂỄỆÌÍỈĨỊÒÓỎÕỌỒỐỔỖỘỜỚỞỠỢÙÚỦŨỤỪỨỬỮỰỲÝỶỸỴ"
    }

    private lateinit var wm: WindowManager
    private val prefs by lazy { getSharedPreferences("app", MODE_PRIVATE) }

    private var button: TextView? = null
    private var buttonLp: WindowManager.LayoutParams? = null
    private var overlay: FrameLayout? = null
    private var panel: View? = null
    private var picker: FrameLayout? = null
    private var lastTap = 0L

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

    // ---------- cài đặt ----------

    private fun aiMode() = prefs.getBoolean("mode_ai", false)
    private fun regionOnly() = prefs.getBoolean("region_only", false)
    private fun showTiming() = prefs.getBoolean("show_timing", true)

    private fun todayKey() = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())

    private fun usageToday(): Int {
        return if (prefs.getString("usage_date", "") == todayKey()) prefs.getInt("usage_count", 0) else 0
    }

    @Synchronized
    private fun bumpUsage() {
        val n = usageToday()
        prefs.edit().putString("usage_date", todayKey()).putInt("usage_count", n + 1).apply()
    }

    private fun savedRegion(): Rect? {
        val s = prefs.getString("region", null) ?: return null
        val p = s.split(",").mapNotNull { it.toFloatOrNull() }
        if (p.size != 4) return null
        val r = Rect(
            (p[0] * screenW).toInt(), (p[1] * screenH).toInt(),
            (p[2] * screenW).toInt(), (p[3] * screenH).toInt()
        )
        return if (r.width() > 50 && r.height() > 50) r else null
    }

    private fun sec(ms: Long) = "${ms / 100 / 10.0}s"

    // ---------- vòng đời ----------

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
        closePanel()
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
            .setContentText("Giữ nút nổi để dịch, chạm đúp để cài đặt")
            .setSmallIcon(android.R.drawable.ic_menu_search)
            .build()
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(1, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
        } else {
            startForeground(1, notification)
        }
    }

    // ---------- chụp màn hình ----------

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

    // ---------- nút nổi ----------

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
            var held = false
            val holdRunnable = Runnable {
                if (!moved) {
                    held = true
                    tv.background = circle(green)
                    tv.text = "..."
                    onHoldStart()
                }
            }

            override fun onTouch(v: View, e: MotionEvent): Boolean {
                when (e.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        startX = e.rawX
                        startY = e.rawY
                        origX = lp.x
                        origY = lp.y
                        moved = false
                        held = false
                        mainHandler.postDelayed(holdRunnable, HOLD_MS)
                    }
                    MotionEvent.ACTION_MOVE -> {
                        val dx = e.rawX - startX
                        val dy = e.rawY - startY
                        if (abs(dx) > 15 || abs(dy) > 15) {
                            moved = true
                            if (!held) mainHandler.removeCallbacks(holdRunnable)
                        }
                        if (moved) {
                            lp.x = origX + dx.toInt()
                            lp.y = origY + dy.toInt()
                            wm.updateViewLayout(tv, lp)
                        }
                    }
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                        mainHandler.removeCallbacks(holdRunnable)
                        if (held) {
                            tv.background = circle(blue)
                            tv.text = "Dịch"
                            onHoldEnd()
                        } else if (!moved && e.actionMasked == MotionEvent.ACTION_UP) {
                            val now = SystemClock.uptimeMillis()
                            if (now - lastTap < 400) {
                                lastTap = 0
                                showPanel()
                            } else {
                                lastTap = now
                            }
                        }
                        held = false
                    }
                }
                return true
            }
        })

        wm.addView(tv, lp)
        button = tv
        buttonLp = lp
    }

    // ---------- bảng cài đặt nhỏ ----------

    private fun panelButton(label: String): TextView {
        val d = resources.displayMetrics.density
        return TextView(this).apply {
            text = label
            setTextColor(Color.WHITE)
            textSize = 14f
            gravity = Gravity.CENTER
            setPadding((12 * d).toInt(), (10 * d).toInt(), (12 * d).toInt(), (10 * d).toInt())
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#37474F"))
                cornerRadius = 12 * d
            }
        }
    }

    private fun showPanel() {
        if (panel != null) return
        val d = resources.displayMetrics.density
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val p = (16 * d).toInt()
            setPadding(p, p, p, p)
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#F2212121"))
                cornerRadius = 20 * d
            }
        }
        val title = TextView(this).apply {
            text = "Cài đặt nhanh"
            setTextColor(Color.WHITE)
            textSize = 16f
            typeface = Typeface.DEFAULT_BOLD
        }
        val modeBtn = panelButton("")
        val regionBtn = panelButton("")
        val pickBtn = panelButton("Chọn lại vùng cần dịch")
        val timingBtn = panelButton("")
        val usage = TextView(this).apply {
            setTextColor(Color.parseColor("#B0BEC5"))
            textSize = 13f
            gravity = Gravity.CENTER
        }
        val stopBtn = panelButton("Tắt nút nổi")
        val closeBtn = panelButton("Đóng")

        fun refresh() {
            modeBtn.text = if (aiMode()) "Chế độ: AI nhìn ảnh (chậm, chính xác)" else "Chế độ: Nhanh"
            regionBtn.text = if (regionOnly()) "Chỉ dịch vùng đã chọn: BẬT" else "Chỉ dịch vùng đã chọn: TẮT"
            timingBtn.text = if (showTiming()) "Hiện thời gian xử lý: BẬT" else "Hiện thời gian xử lý: TẮT"
            usage.text = "Hôm nay đã dùng ${usageToday()} lượt Gemini"
        }
        refresh()

        modeBtn.setOnClickListener {
            prefs.edit().putBoolean("mode_ai", !aiMode()).apply()
            refresh()
        }
        regionBtn.setOnClickListener {
            if (!regionOnly() && savedRegion() == null) {
                mainHandler.post {
                    closePanel()
                    showRegionPicker()
                }
            } else {
                prefs.edit().putBoolean("region_only", !regionOnly()).apply()
                refresh()
            }
        }
        pickBtn.setOnClickListener {
            mainHandler.post {
                closePanel()
                showRegionPicker()
            }
        }
        timingBtn.setOnClickListener {
            prefs.edit().putBoolean("show_timing", !showTiming()).apply()
            refresh()
        }
        stopBtn.setOnClickListener { stopSelf() }
        closeBtn.setOnClickListener { mainHandler.post { closePanel() } }

        fun add(v: View, topDp: Int = 8) {
            val lp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
            )
            lp.topMargin = (topDp * d).toInt()
            box.addView(v, lp)
        }
        add(title, 0)
        add(modeBtn, 12)
        add(regionBtn)
        add(pickBtn)
        add(timingBtn)
        add(usage, 12)
        add(stopBtn, 12)
        add(closeBtn)

        box.setOnTouchListener { _, e ->
            if (e.actionMasked == MotionEvent.ACTION_OUTSIDE) {
                mainHandler.post { closePanel() }
                true
            } else {
                false
            }
        }

        val w = minOf((screenW * 0.9f).toInt(), (300 * d).toInt())
        val lp = WindowManager.LayoutParams(
            w, WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH,
            PixelFormat.TRANSLUCENT
        )
        lp.gravity = Gravity.CENTER
        wm.addView(box, lp)
        panel = box
    }

    private fun closePanel() {
        panel?.let { wm.removeView(it) }
        panel = null
    }

    // ---------- chọn vùng ----------

    private fun showRegionPicker() {
        if (picker != null) return
        val root = FrameLayout(this)
        root.setBackgroundColor(Color.parseColor("#66000000"))
        val rv = RegionView(this) { rect ->
            mainHandler.post {
                closePicker()
                if (rect != null) saveRegion(rect)
            }
        }
        root.addView(
            rv,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT
            )
        )
        val hint = TextView(this).apply {
            text = "Kéo ngón tay để khoanh vùng cần dịch"
            setTextColor(Color.WHITE)
            textSize = 16f
            setPadding(32, 16, 32, 16)
            setBackgroundColor(Color.parseColor("#CC000000"))
        }
        val hlp = FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.WRAP_CONTENT,
            FrameLayout.LayoutParams.WRAP_CONTENT,
            Gravity.TOP or Gravity.CENTER_HORIZONTAL
        )
        hlp.topMargin = 140
        root.addView(hint, hlp)

        val lp = WindowManager.LayoutParams(
            screenW, screenH,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
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
        picker = root
    }

    private fun closePicker() {
        picker?.let { wm.removeView(it) }
        picker = null
    }

    private fun saveRegion(rect: Rect) {
        if (rect.width() < 100 || rect.height() < 100) {
            Toast.makeText(this, "Vùng quá nhỏ, hãy thử lại", Toast.LENGTH_SHORT).show()
            return
        }
        val s = "${rect.left.toFloat() / screenW},${rect.top.toFloat() / screenH}," +
            "${rect.right.toFloat() / screenW},${rect.bottom.toFloat() / screenH}"
        prefs.edit().putString("region", s).putBoolean("region_only", true).apply()
        Toast.makeText(this, "Đã lưu vùng. Giữ nút nổi để dịch vùng này", Toast.LENGTH_LONG).show()
    }

    // ---------- giữ để dịch ----------

    private fun onHoldStart() {
        holding = true
        requestId += 1
        val id = requestId
        val t0 = SystemClock.uptimeMillis()
        bgHandler.post { waitForSettle(id, t0) { grabAndProcess(id, t0) } }
    }

    private fun onHoldEnd() {
        holding = false
        requestId += 1
        hideOverlay()
    }

    private fun waitForSettle(id: Int, startedAt: Long, then: () -> Unit) {
        if (!holding || id != requestId) return
        val now = SystemClock.uptimeMillis()
        if (now - lastFrameTime >= 100 || now - startedAt >= 400) {
            then()
            return
        }
        bgHandler.postDelayed({ waitForSettle(id, startedAt, then) }, 40)
    }

    private fun grabAndProcess(id: Int, t0: Long) {
        if (!holding || id != requestId) return
        val full = grabBitmap()
        val region = if (regionOnly()) savedRegion() else null

        var src: Bitmap? = full
        var offX = 0
        var offY = 0
        if (full != null && region != null) {
            val l = region.left.coerceIn(0, full.width - 1)
            val t = region.top.coerceIn(0, full.height - 1)
            val r = region.right.coerceIn(l + 1, full.width)
            val b = region.bottom.coerceIn(t + 1, full.height)
            src = Bitmap.createBitmap(full, l, t, r - l, b - t)
            offX = l
            offY = t
        }

        val ai = aiMode()
        val scale = if (src != null && src.width <= 800) 2f else 1.5f
        val scaled = if (!ai && src != null) {
            Bitmap.createScaledBitmap(
                src, (src.width * scale).toInt(), (src.height * scale).toInt(), true
            )
        } else {
            null
        }
        val tCap = SystemClock.uptimeMillis() - t0
        val fx = offX
        val fy = offY

        mainHandler.post {
            if (!holding || id != requestId) return@post
            showOverlay("Đang dịch...")
            if (src == null) {
                setBanner("Chưa chụp được màn hình, thử lại")
                return@post
            }
            if (ai) {
                runAi(src, fx, fy, id, tCap)
            } else if (scaled != null) {
                runFast(src, scaled, scale, fx, fy, id, tCap)
            }
        }
    }

    // ---------- chế độ nhanh ----------

    private fun looksVietnamese(s: String): Boolean = s.any { VI_CHARS.indexOf(it) >= 0 }

    private fun norm(s: String): String = s.lowercase().filter { it.isLetterOrDigit() }

    private fun mergeBlocks(src: List<Block>): List<Block> {
        val list = src.toMutableList()
        var changed = true
        while (changed) {
            changed = false
            loop@ for (i in list.indices) {
                for (j in i + 1 until list.size) {
                    val a = list[i]
                    val b = list[j]
                    val minH = minOf(a.box.height(), b.box.height())
                    val maxH = maxOf(a.box.height(), b.box.height())
                    if (minH <= 0 || maxH > minH * 2) continue
                    val gap = maxOf(4, minH / 3)
                    val ea = Rect(a.box.left - gap, a.box.top - gap, a.box.right + gap, a.box.bottom + gap)
                    if (Rect.intersects(ea, b.box)) {
                        val aFirst = if (abs(a.box.top - b.box.top) < minH / 2) {
                            a.box.left <= b.box.left
                        } else {
                            a.box.top < b.box.top
                        }
                        val union = Rect(
                            minOf(a.box.left, b.box.left), minOf(a.box.top, b.box.top),
                            maxOf(a.box.right, b.box.right), maxOf(a.box.bottom, b.box.bottom)
                        )
                        val text = if (aFirst) a.text + " " + b.text else b.text + " " + a.text
                        list[i] = Block(text, union)
                        list.removeAt(j)
                        changed = true
                        break@loop
                    }
                }
            }
        }
        return list
    }

    private fun runFast(
        src: Bitmap, scaled: Bitmap, scale: Float, offX: Int, offY: Int, id: Int, tCap: Long
    ) {
        val lp = buttonLp
        val btnRect = if (lp != null) {
            Rect(lp.x - 10, lp.y - 10, lp.x + lp.width + 10, lp.y + lp.height + 10)
        } else {
            Rect()
        }
        val tOcr0 = SystemClock.uptimeMillis()

        recognizer.process(InputImage.fromBitmap(scaled, 0))
            .addOnSuccessListener { result ->
                if (!holding || id != requestId) return@addOnSuccessListener
                val tOcr = SystemClock.uptimeMillis() - tOcr0
                val found = ArrayList<Block>()
                for (b in result.textBlocks) {
                    val rb = b.boundingBox ?: continue
                    val box = Rect(
                        (rb.left / scale).toInt(), (rb.top / scale).toInt(),
                        (rb.right / scale).toInt(), (rb.bottom / scale).toInt()
                    )
                    val text = b.text.replace("\n", " ").trim()
                    if (text.length < 2 || !text.any { it.isLetter() }) continue
                    if (looksVietnamese(text)) continue
                    val screenBox = Rect(box.left + offX, box.top + offY, box.right + offX, box.bottom + offY)
                    if (Rect.intersects(screenBox, btnRect)) continue
                    found.add(Block(text, box))
                }
                if (found.isEmpty()) {
                    setBanner("Không có chữ nước ngoài nào để dịch")
                    return@addOnSuccessListener
                }
                val blocks = mergeBlocks(found).sortedWith(compareBy<Block>({ it.box.top / 40 }, { it.box.left }))
                netExecutor.execute { translateFast(blocks, src, offX, offY, id, tCap, tOcr) }
            }
            .addOnFailureListener {
                if (holding && id == requestId) setBanner("Lỗi khi đọc chữ trên màn hình")
            }
    }

    private fun translateFast(
        blocks: List<Block>, src: Bitmap, offX: Int, offY: Int, id: Int, tCap: Long, tOcr: Long
    ) {
        try {
            val key = prefs.getString("gemini_key", "") ?: ""
            val model = prefs.getString("gemini_model", DEFAULT_MODEL) ?: DEFAULT_MODEL
            if (key.isEmpty()) {
                showMessage("Chưa có API key")
                return
            }
            val tG0 = SystemClock.uptimeMillis()
            val all = ArrayList<String>()
            var from = 0
            while (from < blocks.size) {
                if (!holding || id != requestId) return
                val chunk = blocks.subList(from, minOf(from + CHUNK, blocks.size)).map { it.text }
                all.addAll(withRetry { callGeminiFast(key, model, chunk) })
                from += CHUNK
            }
            val tG = SystemClock.uptimeMillis() - tG0
            val timing = " · chụp ${sec(tCap)} · đọc chữ ${sec(tOcr)} · Gemini ${sec(tG)}"
            mainHandler.post {
                if (holding && id == requestId) drawFast(blocks, all, src, offX, offY, timing)
            }
        } catch (e: Exception) {
            showMessage(e.message ?: "Lỗi khi dịch")
        }
    }

    private fun drawFast(
        blocks: List<Block>, results: List<String>, src: Bitmap, offX: Int, offY: Int, timing: String
    ) {
        val root = overlay ?: return
        root.removeAllViews()
        var drawn = 0
        for (i in blocks.indices) {
            val t = results.getOrElse(i) { "" }.trim()
            if (t.isEmpty()) continue
            if (norm(t) == norm(blocks[i].text)) continue
            val b = blocks[i].box
            val bg = dominantColor(src, b)
            val screenRect = Rect(
                maxOf(b.left - 6, 0) + offX, maxOf(b.top - 3, 0) + offY,
                b.right + 6 + offX, b.bottom + 3 + offY
            )
            addTranslated(root, screenRect, bg, t)
            drawn++
        }
        if (drawn == 0) {
            setBanner("Không có gì cần dịch trên màn hình này")
            return
        }
        addLabel(root, "Đã dịch $drawn/${blocks.size} khối" + if (showTiming()) timing else "")
    }

    private fun callGeminiFast(key: String, model: String, texts: List<String>): List<String> {
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
        val text = postGemini(key, model, body)
        val arr = try {
            JSONArray(text)
        } catch (e: JSONException) {
            throw RetryException("Gemini trả sai định dạng")
        }
        if (arr.length() != texts.size) throw RetryException("Gemini trả thiếu kết quả")
        return List(texts.size) { i -> arr.optString(i, "") }
    }

    // ---------- chế độ AI nhìn ảnh ----------

    private fun encodeJpeg(bmp: Bitmap): String {
        val longSide = maxOf(bmp.width, bmp.height)
        val s = if (longSide > 1400) 1400f / longSide else 1f
        val out = if (s < 1f) {
            Bitmap.createScaledBitmap(bmp, (bmp.width * s).toInt(), (bmp.height * s).toInt(), true)
        } else {
            bmp
        }
        val bos = ByteArrayOutputStream()
        out.compress(Bitmap.CompressFormat.JPEG, 70, bos)
        return Base64.encodeToString(bos.toByteArray(), Base64.NO_WRAP)
    }

    private fun runAi(src: Bitmap, offX: Int, offY: Int, id: Int, tCap: Long) {
        netExecutor.execute {
            try {
                val key = prefs.getString("gemini_key", "") ?: ""
                val model = prefs.getString("gemini_model", DEFAULT_MODEL) ?: DEFAULT_MODEL
                if (key.isEmpty()) {
                    showMessage("Chưa có API key")
                    return@execute
                }
                val tP0 = SystemClock.uptimeMillis()
                val b64 = encodeJpeg(src)
                val tPrep = SystemClock.uptimeMillis() - tP0
                if (!holding || id != requestId) return@execute
                val tG0 = SystemClock.uptimeMillis()
                val items = withRetry { callGeminiAi(key, model, b64) }
                val tG = SystemClock.uptimeMillis() - tG0
                val timing = " · chụp ${sec(tCap)} · nén ảnh ${sec(tPrep)} · Gemini ${sec(tG)}"
                mainHandler.post {
                    if (holding && id == requestId) drawAi(items, src, offX, offY, timing)
                }
            } catch (e: Exception) {
                showMessage(e.message ?: "Lỗi khi dịch")
            }
        }
    }

    private fun callGeminiAi(key: String, model: String, b64: String): List<AiItem> {
        val itemSchema = JSONObject()
            .put("type", "OBJECT")
            .put(
                "properties",
                JSONObject()
                    .put(
                        "box_2d",
                        JSONObject().put("type", "ARRAY").put("items", JSONObject().put("type", "INTEGER"))
                    )
                    .put("vi", JSONObject().put("type", "STRING"))
            )
            .put("required", JSONArray().put("box_2d").put("vi"))
        val parts = JSONArray()
            .put(
                JSONObject().put(
                    "inline_data",
                    JSONObject().put("mime_type", "image/jpeg").put("data", b64)
                )
            )
            .put(JSONObject().put("text", AI_PROMPT))
        val body = JSONObject()
            .put("contents", JSONArray().put(JSONObject().put("role", "user").put("parts", parts)))
            .put(
                "generationConfig",
                JSONObject()
                    .put("responseMimeType", "application/json")
                    .put("responseSchema", JSONObject().put("type", "ARRAY").put("items", itemSchema))
                    .put("temperature", 0.2)
            )
        val text = postGemini(key, model, body)
        val arr = try {
            JSONArray(text)
        } catch (e: JSONException) {
            throw RetryException("Gemini trả sai định dạng")
        }
        val out = ArrayList<AiItem>()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val b = o.optJSONArray("box_2d") ?: continue
            if (b.length() != 4) continue
            val vi = o.optString("vi", "").trim()
            if (vi.isEmpty()) continue
            out.add(
                AiItem(
                    b.optDouble(1).toFloat(), b.optDouble(0).toFloat(),
                    b.optDouble(3).toFloat(), b.optDouble(2).toFloat(), vi
                )
            )
        }
        return out
    }

    private fun drawAi(items: List<AiItem>, src: Bitmap, offX: Int, offY: Int, timing: String) {
        val root = overlay ?: return
        root.removeAllViews()
        var drawn = 0
        for (item in items) {
            var l = (item.xmin / 1000f * src.width).toInt()
            var t = (item.ymin / 1000f * src.height).toInt()
            var r = (item.xmax / 1000f * src.width).toInt()
            var b = (item.ymax / 1000f * src.height).toInt()
            if (r - l < 10 || b - t < 10) continue
            val padX = ((r - l) * 0.02f).toInt() + 2
            val padY = ((b - t) * 0.02f).toInt() + 2
            l = (l - padX).coerceIn(0, src.width - 1)
            t = (t - padY).coerceIn(0, src.height - 1)
            r = (r + padX).coerceIn(l + 1, src.width)
            b = (b + padY).coerceIn(t + 1, src.height)
            val rect = Rect(l, t, r, b)
            val bg = dominantColor(src, rect)
            val screenRect = Rect(l + offX, t + offY, r + offX, b + offY)
            addTranslated(root, screenRect, bg, item.text)
            drawn++
        }
        if (drawn == 0) {
            setBanner("Không thấy chữ cần dịch")
            return
        }
        addLabel(root, "Đã dịch $drawn khối" + if (showTiming()) timing else "")
    }

    // ---------- gọi Gemini ----------

    private fun <T> withRetry(block: () -> T): T {
        var last: Exception? = null
        for (attempt in 1..2) {
            try {
                return block()
            } catch (e: RetryException) {
                last = e
            }
        }
        throw Exception(last?.message ?: "Lỗi khi dịch")
    }

    private fun postGemini(key: String, model: String, body: JSONObject): String {
        val url = URL("https://generativelanguage.googleapis.com/v1beta/models/$model:generateContent")
        val conn = url.openConnection() as HttpURLConnection
        try {
            conn.requestMethod = "POST"
            conn.connectTimeout = 8000
            conn.readTimeout = 30000
            conn.setRequestProperty("Content-Type", "application/json")
            conn.setRequestProperty("x-goog-api-key", key)
            conn.doOutput = true
            bumpUsage()
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
            val resp = conn.inputStream.bufferedReader().use { it.readText() }
            val root = try {
                JSONObject(resp)
            } catch (e: JSONException) {
                throw RetryException("Gemini trả sai định dạng")
            }
            val cands = root.optJSONArray("candidates")
            if (cands == null || cands.length() == 0) {
                throw Exception("Gemini từ chối hoặc không trả kết quả cho nội dung này")
            }
            val cand = cands.getJSONObject(0)
            if (!cand.has("content")) {
                throw Exception("Gemini từ chối nội dung này (bộ lọc an toàn)")
            }
            return try {
                cand.getJSONObject("content")
                    .getJSONArray("parts").getJSONObject(0)
                    .getString("text")
            } catch (e: JSONException) {
                throw RetryException("Gemini trả sai định dạng")
            }
        } catch (e: IOException) {
            throw RetryException("Lỗi mạng, thử lại")
        } finally {
            conn.disconnect()
        }
    }

    // ---------- vẽ lớp phủ ----------

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

    private fun dominantColor(bmp: Bitmap, r: Rect): Int {
        val counts = HashMap<Int, IntArray>()
        val steps = 7
        for (i in 0 until steps) {
            for (j in 0 until steps) {
                val x = (r.left + r.width() * (i + 0.5f) / steps).toInt().coerceIn(0, bmp.width - 1)
                val y = (r.top + r.height() * (j + 0.5f) / steps).toInt().coerceIn(0, bmp.height - 1)
                val c = bmp.getPixel(x, y)
                val key = ((Color.red(c) shr 5) shl 6) or ((Color.green(c) shr 5) shl 3) or (Color.blue(c) shr 5)
                val arr = counts.getOrPut(key) { IntArray(4) }
                arr[0]++
                arr[1] += Color.red(c)
                arr[2] += Color.green(c)
                arr[3] += Color.blue(c)
            }
        }
        val best = counts.values.maxByOrNull { it[0] } ?: return Color.WHITE
        return Color.rgb(best[1] / best[0], best[2] / best[0], best[3] / best[0])
    }

    private fun isDark(color: Int): Boolean {
        val lum = 0.299 * Color.red(color) + 0.587 * Color.green(color) + 0.114 * Color.blue(color)
        return lum < 140
    }

    private fun addTranslated(root: FrameLayout, rect: Rect, bgColor: Int, msg: String) {
        val clean = Normalizer.normalize(msg, Normalizer.Form.NFC)
        val tv = TextView(this).apply {
            text = clean
            typeface = Typeface.SANS_SERIF
            setTextColor(if (isDark(bgColor)) Color.WHITE else Color.BLACK)
            setBackgroundColor(bgColor)
            gravity = Gravity.CENTER_VERTICAL or Gravity.START
            setPadding(4, 2, 4, 2)
            setAutoSizeTextTypeUniformWithConfiguration(8, 26, 1, TypedValue.COMPLEX_UNIT_SP)
        }
        val lp = FrameLayout.LayoutParams(maxOf(rect.width(), 40), maxOf(rect.height(), 30))
        lp.leftMargin = maxOf(rect.left, 0)
        lp.topMargin = maxOf(rect.top, 0)
        root.addView(tv, lp)
    }

    private fun addLabel(root: FrameLayout, msg: String) {
        val label = TextView(this).apply {
            text = msg
            setTextColor(Color.WHITE)
            textSize = 12f
            setPadding(16, 8, 16, 8)
            setBackgroundColor(Color.parseColor("#B3000000"))
        }
        val lp = FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.WRAP_CONTENT,
            FrameLayout.LayoutParams.WRAP_CONTENT,
            Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
        )
        lp.bottomMargin = 160
        root.addView(label, lp)
    }

    override fun onDestroy() {
        holding = false
        mainHandler.removeCallbacksAndMessages(null)
        if (::bgHandler.isInitialized) bgHandler.removeCallbacksAndMessages(null)
        hideOverlay()
        closePanel()
        closePicker()
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
