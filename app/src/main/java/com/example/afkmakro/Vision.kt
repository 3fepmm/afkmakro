package com.example.afkmakro

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.view.View

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
    var hint: String = ""
    var hintTop: Float = 0.3f
    private val paint = Paint()
    private val tp = Paint()
    private val bg = Paint()

    init {
        paint.color = Color.YELLOW
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 4f
        tp.color = Color.WHITE
        tp.isAntiAlias = true
        tp.textAlign = Paint.Align.CENTER
        tp.textSize = 16f * ctx.resources.displayMetrics.density
        bg.color = Color.argb(210, 20, 22, 28)
        bg.isAntiAlias = true
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val rr = r
        if (rr != null) canvas.drawRect(rr, paint)
        if (hint.isNotEmpty()) {
            val lines = hint.split("\n")
            val lh = tp.textSize * 1.45f
            val boxH = lines.size * lh + tp.textSize * 0.8f
            val top = height * hintTop
            canvas.drawRoundRect(width * 0.05f, top, width * 0.95f, top + boxH, 28f, 28f, bg)
            for (i in lines.indices) {
                canvas.drawText(lines[i], width / 2f, top + tp.textSize * 1.25f + i * lh, tp)
            }
        }
    }
}
