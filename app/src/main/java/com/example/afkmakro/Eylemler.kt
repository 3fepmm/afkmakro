package com.example.afkmakro

import android.graphics.Bitmap
import android.graphics.Color
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random

/**
 * Botun "beyni". Ekran NORMAL iken (ölüm, yeniden doğuş vb. yokken) ne yapılacağına karar verir.
 * Her adim() çağrısında EN FAZLA bir hareket yapar ve bir sonraki taramaya kadar beklenecek ms döner.
 *
 * Öncelik sırası:
 *  1. Seviyemizi MORE > Türler menüsünden oku
 *  2. Canımız azsa kaç (ve kalkan kullan)
 *  3. Gücümüz yeten bir oyuncu varsa ona git ve ısır, becerileri kullan
 *  4. Yerdeki parlak yiyeceklere git, yoksa gezin
 *
 * Her özellik ana ekrandaki ayarlardan açılıp kapatılır (bBeslen, bSaldir, bKac, bBecer, bSenkron).
 */
class Beyin(
    private val tikla: (Float, Float) -> Unit,
    private val surukle: (Float, Float, Float, Float, Long) -> Unit,
    private val seri: (Float, Float, Int) -> Unit,
    private val ayar: (String, Int) -> Int
) {
    private class Etiket(val lv: Int, val x: Float, val y: Float)

    // Ekran düzeni (0..1 oranı)
    private val JOY_X = 0.116f
    private val JOY_Y = 0.863f
    private val ISIRIK_X = 0.776f
    private val ISIRIK_Y = 0.835f
    private val BECERI_X = 0.819f
    private val BECERI_Y = 0.634f
    private val MORE_X = 0.075f
    private val MORE_Y = 0.608f
    private val KAPAT_X = 0.820f
    private val KAPAT_Y = 0.283f
    private val KARAKTER_X = 0.50f
    private val KARAKTER_Y = 0.58f

    // Hedef bu kadar yakınsa (ekran yüksekliği cinsinden) ısırmaya başla
    private val YAKIN = 0.22f

    private val LV = Regex("lv\\s*(\\d{1,2})")
    private val ASAMA = Regex("asama\\s*(\\d{1,2})\\s*/\\s*(\\d{1,2})")

    private var mesgulBitis = 0L
    private var kendiLv = 0
    private var sonSenkron = 0L
    private var senkronDurum = 0      // 0 yok, 1 Türler bekleniyor, 2 Evrimler bekleniyor, 3 kapanıyor
    private var senkronSon = 0L
    private var hpOnceki = 1f
    private var beceriSon = 0L
    private var gezAci = 0f
    private var gezSayac = 0
    private var sonEylem = ""

    fun durdur() {
        mesgulBitis = 0L
        senkronDurum = 0
    }

    /** Aşama değişti (evrimleşildi ya da yeniden doğuldu): seviye yeniden okunsun. */
    fun seviyeDegisti() {
        kendiLv = 0
        sonSenkron = 0L
    }

    /** Durum satırına eklenecek kısa bilgi (ne yaptığını görmek için). */
    fun bilgi(): String {
        val s = if (kendiLv > 0) kendiLv.toString() else "?"
        return " | Ben: seviye " + s + ", can %" + (hpOnceki * 100f).toInt() + ", " + sonEylem
    }

    fun adim(bmp: Bitmap, ekran: Ekran, gen: Float, yuk: Float): Long {
        val simdi = System.currentTimeMillis()
        if (simdi < mesgulBitis) return mesgulBitis - simdi + 100L
        if (senkronDurum != 0) return senkronAdim(ekran, gen, yuk, simdi)

        val hp = canOrani(bmp)
        val hasar = hp < hpOnceki - 0.03f
        hpOnceki = hp
        val oyuncular = etiketler(ekran)

        // 1. Seviyemizi menüden oku
        if (ayar("bSenkron", 1) == 1) {
            val eskidi = simdi - sonSenkron > 120000L
            if (kendiLv == 0 || (eskidi && oyuncular.isEmpty())) {
                sonEylem = "menü"
                return senkronBasla(ekran, gen, yuk, simdi)
            }
        }

        // 2. Canımız azsa kaç
        val kacEsik = ayar("bKacCan", 30) / 100f
        val dusukCan = ayar("bKac", 1) == 1 && hp < kacEsik
        if (dusukCan && (hasar || oyuncular.isNotEmpty())) {
            if (beceriKullan(ekran, gen, yuk, false, hp, simdi, true)) return 600L
            var yx = Random.nextFloat() - 0.5f
            var yy = Random.nextFloat() - 0.5f
            var enYakin = 9f
            for (o in oyuncular) {
                val d = uzaklik(o.x, o.y + 0.10f, gen, yuk)
                if (d < enYakin) {
                    enYakin = d
                    yx = -((o.x - KARAKTER_X) * gen)
                    yy = -((o.y + 0.10f - KARAKTER_Y) * yuk)
                }
            }
            sonEylem = "kaçış"
            yuru(yx, yy, gen, yuk, 900L)
            return 1000L
        }

        // 3. Gücümüz yeten bir oyuncuya saldır
        val lv = if (kendiLv > 0) kendiLv else ayar("bLv", 0)
        if (!dusukCan && ayar("bSaldir", 1) == 1 && lv > 0) {
            val tol = ayar("bTolerans", 0)
            var hedef: Etiket? = null
            var hd = 9f
            for (o in oyuncular) {
                if (o.lv <= lv + tol) {
                    val d = uzaklik(o.x, o.y + 0.10f, gen, yuk)
                    if (d < hd) {
                        hd = d
                        hedef = o
                    }
                }
            }
            if (hedef != null) {
                if (hd <= YAKIN) {
                    if (beceriKullan(ekran, gen, yuk, true, hp, simdi, false)) return 600L
                    sonEylem = "ısırık hedef " + hedef.lv
                    seri(ISIRIK_X * gen, ISIRIK_Y * yuk, 8)
                    mesgulBitis = simdi + 1900L
                    return 2000L
                } else {
                    if (hd > 0.35f && beceriKullan(ekran, gen, yuk, true, hp, simdi, false)) return 600L
                    sonEylem = "yaklaşma hedef " + hedef.lv
                    yuru((hedef.x - KARAKTER_X) * gen, (hedef.y + 0.10f - KARAKTER_Y) * yuk, gen, yuk, 700L)
                    return 800L
                }
            }
        }

        // 4. Yiyecek topla / gezin
        if (ayar("bBeslen", 1) == 1) {
            val y = yiyecekBul(bmp)
            if (y != null) {
                sonEylem = "yiyecek"
                yuru(y[0] - KARAKTER_X * gen, y[1] - KARAKTER_Y * yuk, gen, yuk, 700L)
                return 800L
            }
            gezSayac--
            if (gezSayac <= 0) {
                gezAci = Random.nextFloat() * 6.2831855f
                gezSayac = 3
            }
            sonEylem = "gezinme"
            yuru(cos(gezAci), sin(gezAci), gen, yuk, 900L)
            return 1000L
        }
        return 1500L
    }

    // ---------- Hareket ----------

    /** Joystick'i (dx, dy) yönüne belirtilen süre boyunca iter. */
    private fun yuru(dx: Float, dy: Float, gen: Float, yuk: Float, sureMs: Long) {
        val uz = sqrt(dx * dx + dy * dy)
        if (uz < 0.0001f) return
        val ux = dx / uz
        val uy = dy / uz
        val cx = JOY_X * gen
        val cy = JOY_Y * yuk
        val yaricap = 0.075f * yuk
        surukle(cx, cy, cx + ux * yaricap, cy + uy * yaricap, sureMs)
        mesgulBitis = System.currentTimeMillis() + sureMs
    }

    /** Karakterden (x, y) noktasına uzaklık, ekran yüksekliği cinsinden. */
    private fun uzaklik(x: Float, y: Float, gen: Float, yuk: Float): Float {
        val dx = (x - KARAKTER_X) * gen / yuk
        val dy = y - KARAKTER_Y
        return sqrt(dx * dx + dy * dy)
    }

    // ---------- Okuma ----------

    /** Can çubuğunun doluluk oranı (0..1). Çubuk görünmüyorsa 1 döner. */
    private fun canOrani(bmp: Bitmap): Float {
        val w = bmp.width
        val h = bmp.height
        val satirlar = floatArrayOf(0.930f, 0.945f, 0.960f)
        var enSag = -1f
        for (sy in satirlar) {
            val py = (sy * h).toInt().coerceIn(0, h - 1)
            var fx = 0.600f
            while (fx >= 0.418f) {
                val px = (fx * w).toInt().coerceIn(0, w - 1)
                val p = bmp.getPixel(px, py)
                val r = Color.red(p)
                val g = Color.green(p)
                val b = Color.blue(p)
                if (r > 140 && r > g + 60 && r > b + 60) {
                    if (fx > enSag) enSag = fx
                    break
                }
                fx -= 0.004f
            }
        }
        if (enSag < 0f) return 1f
        val oran = (enSag - 0.418f) / (0.598f - 0.418f)
        return oran.coerceIn(0f, 1f)
    }

    /** Ekrandaki oyuncu etiketleri ("LV16 Handa"). "SAFE" yazanlara (korumalı) saldırılamaz, atlanır. */
    private fun etiketler(ekran: Ekran): List<Etiket> {
        val liste = ArrayList<Etiket>()
        for (y in ekran.yazilar) {
            val m = Gozcu.duzle(y.metin)
            if (m.contains("gozcu") || m.contains("rakip") || m.contains("kademe") || m.contains("afk")) continue
            if (m.contains("safe")) continue
            val e = LV.find(m)
            if (e != null) {
                val n = e.groupValues[1].toIntOrNull()
                if (n != null) liste.add(Etiket(n, y.x, y.y))
            }
        }
        return liste
    }

    // ---------- Yiyecek ----------

    private fun yasakBolge(nx: Float, ny: Float): Boolean {
        if (nx < 0.19f && ny > 0.20f) return true                     // sol menü ve joystick
        if (nx > 0.64f && ny < 0.60f) return true                     // panel ve sağ üst kutular
        if (nx > 0.64f && ny > 0.40f) return true                     // sağdaki düğmeler
        if (nx > 0.40f && nx < 0.62f && ny > 0.82f) return true       // kill streak ve can çubuğu
        return false
    }

    /** Yerdeki parlak sarı/turkuaz kümelerin en yakınını bulur. Piksel [x, y] döner, yoksa null. */
    private fun yiyecekBul(bmp: Bitmap): FloatArray? {
        val w = bmp.width
        val h = bmp.height
        val adim = maxOf(8, w / 220)
        val noktalar = ArrayList<IntArray>()
        var y = (0.22f * h).toInt()
        val yBitis = (0.88f * h).toInt()
        while (y < yBitis) {
            var x = 0
            while (x < w) {
                val nx = x.toFloat() / w
                val ny = y.toFloat() / h
                if (!yasakBolge(nx, ny)) {
                    val p = bmp.getPixel(x, y)
                    val r = Color.red(p)
                    val g = Color.green(p)
                    val b = Color.blue(p)
                    val sari = r > 190 && g > 225 && b < 120
                    val mavi = r < 140 && g > 225 && b > 225
                    if (sari || mavi) noktalar.add(intArrayOf(x, y))
                }
                x += adim
            }
            y += adim
        }
        val n = minOf(noktalar.size, 1200)
        if (n < 3) return null
        val cx = KARAKTER_X * w
        val cy = KARAKTER_Y * h
        val komsu = 3 * adim
        var enIyi = -1
        var enIyiD = Float.MAX_VALUE
        for (i in 0 until n) {
            var say = 0
            for (j in 0 until n) {
                if (i != j &&
                    abs(noktalar[i][0] - noktalar[j][0]) <= komsu &&
                    abs(noktalar[i][1] - noktalar[j][1]) <= komsu
                ) {
                    say++
                }
            }
            if (say >= 2) {
                val dx = noktalar[i][0] - cx
                val dy = noktalar[i][1] - cy
                val d = sqrt(dx * dx + dy * dy)
                if (d > 0.04f * h && d < enIyiD) {
                    enIyiD = d
                    enIyi = i
                }
            }
        }
        if (enIyi < 0) return null
        return floatArrayOf(noktalar[enIyi][0].toFloat(), noktalar[enIyi][1].toFloat())
    }

    // ---------- Beceri ----------

    /**
     * Sağdaki beceri düğmesinin yazısını (Kalkan / Sıçrama / Sürü) okuyup kurala göre basar.
     * Düğmenin üstünde sayı varsa beklemede demektir, basılmaz.
     */
    private fun beceriKullan(
        ekran: Ekran, gen: Float, yuk: Float,
        savasta: Boolean, hp: Float, simdi: Long, kacis: Boolean
    ): Boolean {
        if (ayar("bBecer", 1) != 1) return false
        if (simdi - beceriSon < 3500L) return false
        var etiket = ""
        var bekliyor = false
        for (y in ekran.yazilar) {
            if (y.x >= 0.76f && y.x <= 0.88f) {
                val m = Gozcu.duzle(y.metin).trim()
                if (y.y >= 0.58f && y.y <= 0.72f) {
                    if (m.contains("kalkan")) etiket = "kalkan"
                    if (m.contains("sicrama")) etiket = "sicrama"
                    if (m.contains("suru")) etiket = "suru"
                }
                if (y.y >= 0.50f && y.y <= 0.65f) {
                    if (m.toIntOrNull() != null) bekliyor = true
                }
            }
        }
        if (bekliyor) return false
        val bas = when (etiket) {
            "kalkan" -> hp < 0.65f && (savasta || kacis)
            "sicrama" -> savasta || kacis
            "suru" -> savasta
            else -> savasta && simdi - beceriSon > 8000L
        }
        if (!bas) return false
        beceriSon = simdi
        sonEylem = "beceri " + etiket
        tikla(BECERI_X * gen, BECERI_Y * yuk)
        return true
    }

    // ---------- Seviye okuma (MORE > Türler > "Aşama x / 21") ----------

    private fun senkronBasla(ekran: Ekran, gen: Float, yuk: Float, simdi: Long): Long {
        sonSenkron = simdi
        senkronSon = simdi + 9000L
        senkronDurum = 1
        var acik = false
        for (y in ekran.yazilar) {
            if (Gozcu.duzle(y.metin).contains("turler") && y.x < 0.25f) acik = true
        }
        // Menü zaten açıksa MORE'a tekrar basma (kapanır)
        if (!acik) tikla(MORE_X * gen, MORE_Y * yuk)
        return 1200L
    }

    private fun senkronAdim(ekran: Ekran, gen: Float, yuk: Float, simdi: Long): Long {
        var turler: Yazi? = null
        var evrimler = false
        var asama = -1
        for (y in ekran.yazilar) {
            val m = Gozcu.duzle(y.metin)
            if (m.contains("turler") && y.x < 0.25f) turler = y
            if (m.contains("evrimler")) evrimler = true
            val e = ASAMA.find(m)
            if (e != null) {
                val a = e.groupValues[1].toIntOrNull()
                if (a != null) asama = a
            }
        }
        if (simdi > senkronSon) {
            // Zaman aşımı: pencere açık kaldıysa kapat ve bırak
            if (evrimler) tikla(KAPAT_X * gen, KAPAT_Y * yuk)
            senkronDurum = 0
            return 1500L
        }
        if (senkronDurum == 1) {
            if (evrimler) {
                senkronDurum = 2
                return 300L
            }
            if (turler != null) {
                tikla(turler.x * gen, (turler.y - 0.07f) * yuk)
                senkronDurum = 2
                senkronSon = simdi + 8000L
            }
            return 1000L
        }
        if (senkronDurum == 2) {
            if (evrimler && asama > 0) {
                kendiLv = asama
                senkronDurum = 3
                senkronSon = simdi + 6000L
                tikla(KAPAT_X * gen, KAPAT_Y * yuk)
            }
            return 1000L
        }
        // Durum 3: pencerenin kapanmasını bekle
        if (evrimler) {
            tikla(KAPAT_X * gen, KAPAT_Y * yuk)
        } else {
            senkronDurum = 0
        }
        return 1000L
    }
}
