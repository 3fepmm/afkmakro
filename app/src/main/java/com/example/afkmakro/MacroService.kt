package com.example.afkmakro

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.Rect
import android.os.Handler
import android.os.Looper
import android.view.Display
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.Executors
import kotlin.random.Random

class Gray(val w: Int, val h: Int, val d: IntArray)

object Matcher {
    const val SCALE = 4

    fun gray(b: Bitmap, scale: Int): Gray {
        val ww = b.width / scale
        val hh = b.height / scale
        val s = Bitmap.createScaledBitmap(b, ww, hh, true)
        val px = IntArray(ww * hh)
        s.getPixels(px, 0, ww, 0, 0, ww, hh)
        for (i in px.indices) {
            val c = px[i]
            val r = (c shr 16) and 0xFF
            val g = (c shr 8) and 0xFF
            val bl = c and 0xFF
            px[i] = (r * 77 + g * 151 + bl * 28) shr 8
        }
        return Gray(ww, hh, px)
    }

    fun find(scr: Gray, t: Gray, tol: Int): IntArray? {
        if (t.w < 4 || t.h < 4 || t.w > scr.w || t.h > scr.h) return null
        var best = tol.toLong() * t.w * t.h + 1
        var bx = -1
        var by = -1
        for (y in 0..scr.h - t.h) {
            for (x in 0..scr.w - t.w) {
                var sum = 0L
                var ty = 0
                while (ty < t.h && sum < best) {
                    val so = (y + ty) * scr.w + x
                    val to = ty * t.w
                    var tx = 0
                    while (tx < t.w) {
                        val d = scr.d[so + tx] - t.d[to + tx]
                        sum += if (d < 0) -d else d
                        tx++
                    }
                    ty++
                }
                if (sum < best) {
                    best = sum
                    bx = x
                    by = y
                }
            }
        }
        if (bx < 0) return null
        return intArrayOf((bx + t.w / 2) * SCALE, (by + t.h / 2) * SCALE)
    }
}

class SelectView(ctx: Context) : View(ctx) {
    var r: Rect? = null
    private val paint = Paint()

    init {
        paint.color = Color.YELLOW
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 4f
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val rr = r
        if (rr != null) canvas.drawRect(rr, paint)
    }
}

class MacroService : AccessibilityService() {
    private val h = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor()
    private lateinit var wm: WindowManager
    private var panel: LinearLayout? = null
    private var capture: SelectView? = null
    private var status: TextView? = null
    private var recBtn: Button? = null
    private var steps = mutableListOf<Step>()
    private var recording = false
    private var playing = false
    private var scanning = false
    private var clicking = false
    private var capMode = 0
    private var rightSide = false
    private var idx = 0
    private var round = 0
    private var clickCount = 0
    private var lastAfk = 0L
    private var stopAt = 0L
    private var token = 0
    private var lastEnd = 0L
    private var downWall = 0L

    override fun onServiceConnected() {
        super.onServiceConnected()
        wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        steps = Store.loadSteps(this)
        buildPanel()
        showPanel()
        setStatus("Hazır: ${steps.size} adım")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}

    override fun onInterrupt() {}

    override fun onDestroy() {
        token++
        h.removeCallbacksAndMessages(null)
        worker.shutdown()
        if (::wm.isInitialized) {
            val pn = panel
            if (pn != null) {
                try { wm.removeView(pn) } catch (e: Exception) {}
            }
            val c = capture
            if (c != null) {
                try { wm.removeView(c) } catch (e: Exception) {}
            }
        }
        super.onDestroy()
    }

    // ---------- Panel ----------

    private fun btn(t: String, onClick: () -> Unit): Button {
        val b = Button(this)
        b.text = t
        b.textSize = 11f
        b.setAllCaps(false)
        b.setPadding(8, 0, 8, 0)
        b.minWidth = 0
        b.minimumWidth = 0
        b.setOnClickListener { onClick() }
        return b
    }

    private fun buildPanel() {
        val box = LinearLayout(this)
        box.orientation = LinearLayout.VERTICAL
        box.setBackgroundColor(Color.argb(200, 30, 30, 30))
        box.setPadding(8, 8, 8, 8)

        val st = TextView(this)
        st.setTextColor(Color.WHITE)
        st.textSize = 12f
        status = st

        val row1 = LinearLayout(this)
        row1.orientation = LinearLayout.HORIZONTAL
        val rec = btn("Kayıt") { toggleRecord() }
        recBtn = rec
        row1.addView(rec)
        row1.addView(btn("Oynat") { startPlay() })
        row1.addView(btn("Dur") { stopPlay("Durdu") })
        row1.addView(btn("Yan") {
            rightSide = !rightSide
            showPanel()
        })

        val row2 = LinearLayout(this)
        row2.orientation = LinearLayout.HORIZONTAL
        row2.addView(btn("Resim+") { startSelect() })
        row2.addView(btn("Tara") { startScan() })
        row2.addView(btn("Tık") { startPick() })
        row2.addView(btn("ResSil") { clearTemplates() })

        box.addView(st)
        box.addView(row1)
        box.addView(row2)
        panel = box
    }

    private fun showPanel() {
        val p = panel ?: return
        try { wm.removeView(p) } catch (e: Exception) {}
        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON,
            PixelFormat.TRANSLUCENT
        )
        lp.gravity = Gravity.TOP or (if (rightSide) Gravity.END else Gravity.START)
        lp.y = 120
        wm.addView(p, lp)
    }

    private fun setStatus(t: String) {
        status?.text = t
    }

    // ---------- Dokunuş yakalama katmanı ----------

    private fun showCapture(mode: Int) {
        hideCapture()
        capMode = mode
        val v = SelectView(this)
        v.setBackgroundColor(Color.argb(50, 0, 120, 255))
        var sx = 0f
        var sy = 0f
        var lx = 0f
        var ly = 0f
        v.setOnTouchListener { _, e ->
            if (e.action == MotionEvent.ACTION_DOWN) {
                sx = e.rawX
                sy = e.rawY
                lx = e.x
                ly = e.y
                downWall = System.currentTimeMillis()
            } else if (e.action == MotionEvent.ACTION_MOVE) {
                if (capMode == 1) {
                    v.r = Rect(
                        minOf(lx, e.x).toInt(), minOf(ly, e.y).toInt(),
                        maxOf(lx, e.x).toInt(), maxOf(ly, e.y).toInt()
                    )
                    v.invalidate()
                }
            } else if (e.action == MotionEvent.ACTION_UP) {
                onCaptureUp(sx, sy, e)
            }
            true
        }
        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON,
            PixelFormat.TRANSLUCENT
        )
        wm.addView(v, lp)
        capture = v
    }

    private fun hideCapture() {
        val c = capture
        if (c != null) {
            try { wm.removeView(c) } catch (e: Exception) {}
        }
        capture = null
    }

    private fun onCaptureUp(sx: Float, sy: Float, e: MotionEvent) {
        if (capMode == 0) {
            val dx = (e.rawX - sx).toDouble()
            val dy = (e.rawY - sy).toDouble()
            val moved = Math.hypot(dx, dy) > 40
            val delay = if (lastEnd == 0L) 500L
            else (downWall - lastEnd).coerceAtLeast(200L)
            val dur = (e.eventTime - e.downTime).coerceIn(50L, 3000L)
            val ex = if (moved) e.rawX else sx
            val ey = if (moved) e.rawY else sy
            steps.add(Step(sx, sy, ex, ey, dur, delay))
            lastEnd = System.currentTimeMillis()
            setStatus("Kayıt: ${steps.size} adım")
        } else if (capMode == 1) {            val r = Rect(
                minOf(sx, e.rawX).toInt(), minOf(sy, e.rawY).toInt(),
                maxOf(sx, e.rawX).toInt(), maxOf(sy, e.rawY).toInt()
            )
            hideCapture()
            saveTemplate(r)
        } else {
            hideCapture()
            startClick(sx, sy)
        }
    }

    // ---------- Makro kaydı ----------

    private fun toggleRecord() {
        if (recording) {
            recording = false
            hideCapture()
            Store.saveSteps(this, steps)
            recBtn?.text = "Kayıt"
            setStatus("Kaydedildi: ${steps.size} adım")
        } else {
            stopPlay("Kayıt modu")
            steps = mutableListOf()
            recording = true
            lastEnd = 0L
            recBtn?.text = "Bitir"
            showCapture(0)
            showPanel()
            setStatus("Kayıt: dokun")
        }
    }

    // ---------- Ortak ----------

    private fun jittered(ms: Long, pct: Int): Long {
        if (pct <= 0) return ms
        val span = ms * pct / 100
        return (ms - span + Random.nextLong(0, 2 * span + 1)).coerceAtLeast(50L)
    }

    private fun stopPlay(msg: String) {
        playing = false
        scanning = false
        clicking = false
        token++
        h.removeCallbacksAndMessages(null)
        if (!recording) hideCapture()
        setStatus(msg)
    }

    private fun gesture(
        x1: Float, y1: Float, x2: Float, y2: Float,
        dur: Long, done: () -> Unit
    ) {
        val path = Path()
        path.moveTo(x1, y1)
        if (x1 != x2 || y1 != y2) path.lineTo(x2, y2)
        val g = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0L, dur.coerceAtLeast(1L)))
            .build()
        val cb = object : AccessibilityService.GestureResultCallback() {
            override fun onCompleted(gestureDescription: GestureDescription?) {
                done()
            }

            override fun onCancelled(gestureDescription: GestureDescription?) {
                done()
            }
        }
        val ok = dispatchGesture(g, cb, null)
        if (!ok) h.postDelayed({ done() }, 1000L)
    }

    private fun shoot(cb: (Bitmap?) -> Unit) {
        takeScreenshot(
            Display.DEFAULT_DISPLAY,
            mainExecutor,
            object : AccessibilityService.TakeScreenshotCallback {
                override fun onSuccess(result: AccessibilityService.ScreenshotResult) {
                    var out: Bitmap? = null
                    try {
                        val hb = result.hardwareBuffer
                        val hw = Bitmap.wrapHardwareBuffer(hb, result.colorSpace)
                        out = hw?.copy(Bitmap.Config.ARGB_8888, false)
                        hb.close()
                    } catch (e: Exception) {
                        out = null
                    }
                    cb(out)
                }

                override fun onFailure(errorCode: Int) {
                    cb(null)
                }
            }
        )
    }
    // ---------- Makro oynatma ----------

    private fun startPlay() {
        if (recording) toggleRecord()
        if (steps.isEmpty()) {
            setStatus("Önce kayıt yap")
            return
        }
        stopPlay("Başlıyor")
        val p = Store.prefs(this)
        val startMs = p.getInt("startMin", 0) * 60000L
        val stopMin = p.getInt("stopMin", 0)
        playing = true
        val my = ++token
        if (startMs > 0) setStatus("${startMs / 60000} dk sonra başlar")
        h.postDelayed({
            if (playing && my == token) {
                val now = System.currentTimeMillis()
                stopAt = if (stopMin > 0) now + stopMin * 60000L else 0L
                lastAfk = now
                idx = 0
                round = 0
                next(my)
            }
        }, startMs)
    }

    private fun next(my: Int) {
        if (!playing || my != token) return
        val p = Store.prefs(this)
        val now = System.currentTimeMillis()
        if (stopAt > 0 && now >= stopAt) {
            stopPlay("Süre doldu")
            return
        }
        val afkMs = p.getInt("afkMin", 10) * 60000L
        if (afkMs > 0 && now - lastAfk >= afkMs) {
            lastAfk = now
            val m = resources.displayMetrics
            val cx = m.widthPixels * 0.75f
            val cy = m.heightPixels * 0.5f
            gesture(cx, cy, cx + 80f, cy, 250L) {
                h.postDelayed({ next(my) }, 500L)
            }
            return
        }
        val s = steps[idx]
        setStatus("Tur ${round + 1}  adım ${idx + 1}/${steps.size}")
        val wait = jittered(s.delay, p.getInt("jitter", 20))
        h.postDelayed({
            if (playing && my == token) {
                gesture(s.x1, s.y1, s.x2, s.y2, s.dur) { afterStep(my) }
            }
        }, wait)
    }

    private fun afterStep(my: Int) {
        if (!playing || my != token) return
        idx++
        if (idx >= steps.size) {
            idx = 0
            round++
            val maxR = Store.prefs(this).getInt("rounds", 0)
            if (maxR > 0 && round >= maxR) {
                stopPlay("Bitti: $round tur")
                return
            }
        }
        next(my)
    }

    // ---------- Ekran tanıma ----------

    private fun startSelect() {
        if (recording) toggleRecord()
        stopPlay("Resmin üstünü sürükleyerek seç")
        showCapture(1)
        showPanel()
    }

    private fun saveTemplate(r: Rect) {
        if (r.width() < 32 || r.height() < 32) {
            setStatus("Alan çok küçük, tekrar dene")
            return
        }
        val count = Store.prefs(this).getInt("tplCount", 0)
        if (count >= 8) {
            setStatus("En fazla 8 resim")
            return
        }
        panel?.visibility = View.INVISIBLE
        h.postDelayed({
            shoot { bmp ->
                panel?.visibility = View.VISIBLE
                if (bmp == null) {
                    setStatus("Ekran alınamadı")
                } else {
                    val l = r.left.coerceIn(0, bmp.width - 2)
                    val t = r.top.coerceIn(0, bmp.height - 2)
                    val w = (minOf(r.right, bmp.width) - l).coerceAtLeast(1)
                    val hh = (minOf(r.bottom, bmp.height) - t).coerceAtLeast(1)
                    val crop = Bitmap.createBitmap(bmp, l, t, w, hh)
                    FileOutputStream(File(filesDir, "tpl_$count.png")).use {
                        crop.compress(Bitmap.CompressFormat.PNG, 100, it)
                    }
                    Store.prefs(this).edit().putInt("tplCount", count + 1).apply()
                    setStatus("Resim eklendi: ${count + 1}")
                }
            }
        }, 400L)
    }

    private fun loadTemplates(): List<Bitmap> {
        val n = Store.prefs(this).getInt("tplCount", 0)
        val out = mutableListOf<Bitmap>()
        for (i in 0 until n) {
            val f = File(filesDir, "tpl_$i.png")
            if (f.exists()) {
                val b = BitmapFactory.decodeFile(f.path)
                if (b != null) out.add(b)
            }
        }
        return out
    }

    private fun clearTemplates() {
        val n = Store.prefs(this).getInt("tplCount", 0)
        for (i in 0 until n) {
            File(filesDir, "tpl_$i.png").delete()
        }
        Store.prefs(this).edit().putInt("tplCount", 0).apply()
        setStatus("Resimler silindi")
    }
