package com.example.afkmakro

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.view.Display
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.widget.LinearLayout
import android.widget.TextView
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.Executors
import kotlin.random.Random

class MacroService : AccessibilityService() {
    private val h = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor()
    private lateinit var wm: WindowManager
    private var panel: LinearLayout? = null
    private var bubble: TextView? = null
    private var capture: SelectView? = null
    private var status: TextView? = null
    private var recBtn: TextView? = null
    private var bOn: GradientDrawable? = null
    private var bOff: GradientDrawable? = null
    private var minimized = false
    private var delArmed = false
    private var px = 0
    private var py = 0
    private var steps = mutableListOf<Step>()
    private var recording = false
    private var playing = false
    private var scanning = false
    private var clicking = false
    private var capMode = 0
    private var idx = 0
    private var round = 0
    private var clickCount = 0
    private var lastAfk = 0L
    private var stopAt = 0L
    private var token = 0
    private var lastEnd = 0L
    private var downWall = 0L
    private var lastPkg = ""
    private var bot: OyunBotu? = null

    override fun onServiceConnected() {
        super.onServiceConnected()
        wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        steps = Store.loadSteps(this)
        val p = Store.prefs(this)
        px = p.getInt("px", dp(8))
        py = p.getInt("py", dp(120))
        buildPanel()
        showPanel()
        setStatus("Hazır: ${steps.size} adım, ${templateCount()} resim")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return
        if (event.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return
        val pkg = event.packageName?.toString() ?: return
        if (pkg == packageName || pkg == "com.android.systemui") return
        val entered = pkg == "com.roblox.client" && lastPkg != pkg
        lastPkg = pkg
        if (entered && Store.prefs(this).getInt("autoScan", 0) == 1) {
            if (!playing && !scanning && !clicking && !recording && capture == null) {
                h.postDelayed({
                    if (!playing && !scanning && !clicking && !recording && capture == null) {
                        startScan()
                    }
                }, 6000L)
            }
        }
    }

    override fun onInterrupt() {}

    override fun onDestroy() {
    token++
    h.removeCallbacksAndMessages(null)
    worker.shutdown()
    bot?.durdur()
    bot = null
    if (::wm.isInitialized) {
        for (v in listOf<View?>(panel, bubble, capture)) {
            if (v != null) {
                try { wm.removeView(v) } catch (e: Exception) {}
            }
        }
    }
    super.onDestroy()
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    private fun rounded(color: Int, radiusDp: Int): GradientDrawable {
        val g = GradientDrawable()
        g.setColor(color)
        g.cornerRadius = dp(radiusDp).toFloat()
        return g
    }

    private fun oval(color: Int): GradientDrawable {
        val g = GradientDrawable()
        g.shape = GradientDrawable.OVAL
        g.setColor(color)
        return g
    }
        // ---------- Panel ----------

    private fun templateCount(): Int = Store.prefs(this).getInt("tplCount", 0)

    private fun btn(label: String, color: Int, onClick: () -> Unit): TextView {
        val b = TextView(this)
        b.text = label
        b.setTextColor(Color.WHITE)
        b.textSize = 12f
        b.setTypeface(null, Typeface.BOLD)
        b.gravity = Gravity.CENTER
        b.maxLines = 1
        b.background = rounded(color, 12)
        val lp = LinearLayout.LayoutParams(0, dp(46), 1f)
        lp.setMargins(dp(3), dp(3), dp(3), dp(3))
        b.layoutParams = lp
        b.setOnTouchListener { view, e ->
            if (e.action == MotionEvent.ACTION_DOWN) {
                view.alpha = 0.6f
            } else if (e.action == MotionEvent.ACTION_UP ||
                e.action == MotionEvent.ACTION_CANCEL
            ) {
                view.alpha = 1f
            }
            false
        }
        b.setOnClickListener { onClick() }
        return b
    }

    private fun makeDraggable(v: View, onTap: (() -> Unit)?) {
        var sx = 0f
        var sy = 0f
        var ox = 0
        var oy = 0
        var moved = false
        v.setOnTouchListener { _, e ->
            if (e.action == MotionEvent.ACTION_DOWN) {
                sx = e.rawX
                sy = e.rawY
                ox = px
                oy = py
                moved = false
            } else if (e.action == MotionEvent.ACTION_MOVE) {
                val dx = e.rawX - sx
                val dy = e.rawY - sy
                val lim = dp(6).toFloat()
                if (Math.abs(dx) > lim || Math.abs(dy) > lim) moved = true
                if (moved) {
                    px = ox + dx.toInt()
                    py = oy + dy.toInt()
                    val lp = v.layoutParams as WindowManager.LayoutParams
                    lp.x = px
                    lp.y = py
                    try { wm.updateViewLayout(v, lp) } catch (ex: Exception) {}
                }
            } else if (e.action == MotionEvent.ACTION_UP) {
                if (moved) {
                    Store.prefs(this).edit().putInt("px", px).putInt("py", py).apply()
                } else {
                    onTap?.invoke()
                }
            }
            true
        }
    }

    private fun buildPanel() {
        val root = LinearLayout(this)
        root.orientation = LinearLayout.VERTICAL
        root.background = rounded(Color.argb(240, 24, 26, 34), 18)
        root.setPadding(dp(10), dp(10), dp(10), dp(10))

        val st = TextView(this)
        st.setTextColor(Color.WHITE)
        st.textSize = 13f
        st.setTypeface(null, Typeface.BOLD)
        st.setPadding(dp(4), dp(2), dp(4), dp(8))
        status = st

        val row1 = LinearLayout(this)
        row1.orientation = LinearLayout.HORIZONTAL
        val rec = btn("Kayıt", Color.rgb(59, 130, 246)) { toggleRecord() }
        recBtn = rec
        row1.addView(rec)
        row1.addView(btn("Oynat", Color.rgb(34, 197, 94)) { startPlay() })
        row1.addView(btn("Dur", Color.rgb(239, 68, 68)) { stopPlay("Durdu") })
        row1.addView(btn("Küçült", Color.rgb(107, 114, 128)) {
            minimized = true
            showPanel()
        })

        val row2 = LinearLayout(this)
        row2.orientation = LinearLayout.HORIZONTAL
        row2.addView(btn("Resim+", Color.rgb(139, 92, 246)) { startSelect() })
        row2.addView(btn("Tara", Color.rgb(245, 158, 11)) { startScan() })
        row2.addView(btn("Tık", Color.rgb(20, 184, 166)) { startPick() })
        row2.addView(btn("Resim-", Color.rgb(107, 114, 128)) { askDelete() })

        root.addView(st)
        root.addView(row1)
        val row3 = LinearLayout(this)
        row3.orientation = LinearLayout.HORIZONTAL
        row3.addView(btn("Gözcü", Color.rgb(236, 72, 153)) { toggleBot() })
        root.addView(row2)
        root.addView(row3)
        makeDraggable(root, null)
        panel = root

        val b = TextView(this)
        b.text = "AFK"
        b.setTextColor(Color.WHITE)
        b.textSize = 13f
        b.setTypeface(null, Typeface.BOLD)
        b.gravity = Gravity.CENTER
        bOff = oval(Color.argb(235, 55, 65, 110))
        bOn = oval(Color.rgb(34, 197, 94))
        b.background = bOff
        makeDraggable(b) {
            minimized = false
            showPanel()
        }
        bubble = b
    }

    private fun showPanel() {
        val pn = panel ?: return
        val bb = bubble ?: return
        try { wm.removeView(pn) } catch (e: Exception) {}
        try { wm.removeView(bb) } catch (e: Exception) {}
        val v: View = if (minimized) bb else pn
        val w = if (minimized) dp(52) else dp(310)
        val hgt = if (minimized) dp(52) else WindowManager.LayoutParams.WRAP_CONTENT
        val m = resources.displayMetrics
        px = px.coerceIn(0, (m.widthPixels - w).coerceAtLeast(0))
        py = py.coerceIn(0, (m.heightPixels - dp(60)).coerceAtLeast(0))
        val lp = WindowManager.LayoutParams(
            w, hgt,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON,
            PixelFormat.TRANSLUCENT
        )
        lp.gravity = Gravity.TOP or Gravity.START
        lp.x = px
        lp.y = py
        wm.addView(v, lp)
        refreshBubble()
    }

    private fun refreshBubble() {
    val b = bubble ?: return
    val on = playing || scanning || clicking || bot != null
    b.background = if (on) bOn else bOff
    b.text = when {
        scanning -> "TARA"
        clicking -> "TIK"
        playing  -> "OYN"
        bot != null -> "GÖZ"
        else -> "AFK"
    }
}

private fun toggleBot() {
    val eski = bot
    if (eski != null) {
        eski.durdur()
        bot = null
        setStatus("Gözcü durdu")
        refreshBubble()
        return
    }
    stopPlay("Gözcü açılıyor")
    val yeni = OyunBotu(
            { cb -> shoot(cb) },
            { t -> setStatus(t) },
            { x, y -> gesture(x, y, x, y, 90L) { } }
        )
    bot = yeni
    yeni.baslat()
    refreshBubble()
}

    private fun setStatus(t: String) {
        status?.text = t
        refreshBubble()
    }

    private fun askDelete() {
        if (delArmed) {
            delArmed = false
            clearTemplates()
        } else {
            delArmed = true
            setStatus("Tüm resimler silinecek. Onay için Resim- tekrar bas")
            h.postDelayed({ delArmed = false }, 4000L)
        }
    }
            private fun minimize() {
        minimized = true
        showPanel()
    }

    // ---------- Dokunuş yakalama katmanı ----------

    private fun showCapture(mode: Int) {
        hideCapture()
        capMode = mode
        val v = SelectView(this)
        v.setBackgroundColor(Color.argb(60, 0, 120, 255))
        v.hintTop = 0.45f
        v.hint = if (mode == 0) {
            "KAYIT\nOyunda dokunacağın yerlere sırayla dokun.\nBitirince panelde Bitir'e bas."
        } else if (mode == 1) {
            "RESİM SEÇ\nBastırmak istediğin düğmenin üstünü\nparmağınla sürükleyerek çerçevele.\n" +
                "Parmağını bırakınca kaydedilir."
        } else {
            "TIK NOKTASI\nSürekli tıklanacak yere bir kez dokun.\nDurdurmak için Dur'a bas."
        }
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
                    v.hint = ""
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
            val r = Rect(
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
            minimized = false
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
    bot?.durdur()
    bot = null
    if (!recording) hideCapture()
    setStatus(msg)
    refreshBubble()
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
        minimize()
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
        stopPlay("Resim seç")
        showCapture(1)
        minimized = true
        showPanel()
    }

    private fun saveTemplate(r: Rect) {
        if (r.width() < 32 || r.height() < 32) {
            minimized = false
            showPanel()
            setStatus("Alan çok küçük, tekrar dene")
            return
        }
        val count = templateCount()
        if (count >= 8) {
            minimized = false
            showPanel()
            setStatus("En fazla 8 resim. Resim- ile temizle")
            return
        }
        panel?.visibility = View.INVISIBLE
        bubble?.visibility = View.INVISIBLE
        h.postDelayed({
            shoot { bmp ->
                panel?.visibility = View.VISIBLE
                bubble?.visibility = View.VISIBLE
                minimized = false
                showPanel()
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
        val n = templateCount()
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
        val n = templateCount()
        for (i in 0 until n) {
            File(filesDir, "tpl_$i.png").delete()
        }
        Store.prefs(this).edit().putInt("tplCount", 0).apply()
        setStatus("Resimler silindi")
    }

    private fun startScan() {
        if (recording) toggleRecord()
        val tpls = loadTemplates()
        if (tpls.isEmpty()) {
            minimized = false
            showPanel()
            setStatus("Önce Resim+ ile resim ekle")
            return
        }
        stopPlay("Tarama başlıyor")
        val p = Store.prefs(this)
        val grays = tpls.map { Matcher.gray(it, Matcher.SCALE) }
        val startMs = p.getInt("startMin", 0) * 60000L
        val stopMin = p.getInt("stopMin", 0)
        scanning = true
        val my = ++token
        minimize()
        if (startMs > 0) setStatus("${startMs / 60000} dk sonra başlar")
        h.postDelayed({
            if (scanning && my == token) {
                val now = System.currentTimeMillis()
                stopAt = if (stopMin > 0) now + stopMin * 60000L else 0L
                lastAfk = now
                scanTick(my, grays)
            }
        }, startMs)
    }

    private fun scanTick(my: Int, grays: List<Gray>) {
        if (!scanning || my != token) return
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
                h.postDelayed({ scanTick(my, grays) }, 500L)
            }
            return
        }
        val every = p.getInt("scanSec", 2).coerceAtLeast(1) * 1000L
        val tol = p.getInt("tol", 25)
        shoot { bmp ->
            if (!scanning || my != token) return@shoot
            if (bmp == null) {
                setStatus("Ekran alınamadı")
                h.postDelayed({ scanTick(my, grays) }, every)
            } else {
                worker.execute {
                    val scr = Matcher.gray(bmp, Matcher.SCALE)
                    var hit: IntArray? = null
                    var which = -1
                    for (i in grays.indices) {
                        val r = Matcher.find(scr, grays[i], tol)
                        if (r != null) {
                            hit = r
                            which = i
                            break
                        }
                    }
                    val found: IntArray? = hit
                    val num = which + 1
                    h.post {
                        if (scanning && my == token) {
                            if (found != null) {
                                setStatus("Bulundu: resim $num")
                                val fx = found[0].toFloat()
                                val fy = found[1].toFloat()
                                gesture(fx, fy, fx, fy, 60L) {
                                    h.postDelayed({ scanTick(my, grays) }, 1500L)
                                }
                            } else {
                                setStatus("Aranıyor...")
                                h.postDelayed({ scanTick(my, grays) }, every)
                            }
                        }
                    }
                }
            }
        }
    }
        // ---------- Oto clicker ----------

    private fun startPick() {
        if (recording) toggleRecord()
        stopPlay("Tıklanacak yere dokun")
        showCapture(2)
        minimized = true
        showPanel()
    }

    private fun startClick(x: Float, y: Float) {
        stopPlay("Tıklıyor")
        val stopMin = Store.prefs(this).getInt("stopMin", 0)
        stopAt = if (stopMin > 0) System.currentTimeMillis() + stopMin * 60000L else 0L
        clickCount = 0
        clicking = true
        val my = ++token
        minimize()
        clickTick(my, x, y)
    }

    private fun clickTick(my: Int, x: Float, y: Float) {
        if (!clicking || my != token) return
        val p = Store.prefs(this)
        if (stopAt > 0 && System.currentTimeMillis() >= stopAt) {
            stopPlay("Süre doldu")
            return
        }
        val maxC = p.getInt("clickMax", 0)
        if (maxC > 0 && clickCount >= maxC) {
            stopPlay("Bitti: $clickCount tık")
            return
        }
        val base = p.getInt("clickMs", 200).toLong().coerceAtLeast(50L)
        val gap = jittered(base, p.getInt("jitter", 20))
        gesture(x, y, x, y, 30L) {
            clickCount++
            setStatus("Tık: $clickCount")
            h.postDelayed({ clickTick(my, x, y) }, gap)
        }
    }
}
