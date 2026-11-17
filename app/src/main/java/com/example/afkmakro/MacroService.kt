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
        } else if (capMode == 1) {
