package com.example.afkmakro

import android.graphics.Bitmap
import android.graphics.Color
import android.os.Handler
import android.os.Looper
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions

/** Ekranda okunan tek bir satır yazı. x ve y satırın merkezi (0..1 oranı). */
data class Yazi(val metin: String, val x: Float, val y: Float)

/** Bir ekran görüntüsünden okunan tüm satırlar. */
data class Ekran(val yazilar: List<Yazi>)

/** Oyun ekranının hangi durumda olduğu. */
enum class Durum {
    NORMAL, OLUM, YD_HAZIR, YD_HAZIR_DEGIL, YD_ONAY, YD_BITTI, DUGME
}

/** İncelemenin sonucu. */
data class Sonuc(
    val durum: Durum,
    val dugme: String?,
    val seviyeler: List<Int>,
    val asama: String?,
    val tiklanacakX: Float? = null,   // 0..1
    val tiklanacakY: Float? = null
)

object Gozcu {
    // Üst ortadaki ilerleme düğmesinin bölgesi (yazısını aramak için)
    private const val DUGME_X1 = 0.38f
    private const val DUGME_X2 = 0.62f
    private const val DUGME_Y1 = 0.14f
    private const val DUGME_Y2 = 0.23f

    // Üstteki ilerleme düğmesi hep aynı yerde duruyor
    private const val SABIT_DUGME_X = 0.486f
    private const val SABIT_DUGME_Y = 0.183f

    // Yeniden doğuş penceresindeki büyük düğmenin yeri
    private const val YD_DUGME_X = 0.50f
    private const val YD_DUGME_Y = 0.87f

    // Ölüm ekranındaki yeşil "Şimdi yeniden canlan" düğmesinin yeri
    private const val OLUM_X = 0.50f
    private const val OLUM_Y = 0.72f

    private val LV = Regex("lv\\s*(\\d{1,2})")
    private val ASAMA = Regex("asama\\s*(\\d{1,2})\\s*/\\s*(\\d{1,2})")

    private val KENDI_YAZIMIZ = listOf("gozcu", "kademe", "rakip", "afk")

    // Bu yazıları içeren hiçbir satıra ASLA basılmaz (Robux gerektirenler)
    private val YASAK = listOf("burada canlan", "revenge", "kill now", "unlock", "robux", "satin")

    fun duzle(s: String): String {
        val sb = StringBuilder()
        for (ch in s) {
            val c = when (ch) {
                'İ', 'I', 'ı' -> 'i'
                'Ş', 'ş' -> 's'
                'Ğ', 'ğ' -> 'g'
                'Ö', 'ö' -> 'o'
                'Ü', 'ü' -> 'u'
                'Ç', 'ç' -> 'c'
                else -> ch.lowercaseChar()
            }
            sb.append(c)
        }
        return sb.toString()
    }

    private fun yasakli(m: String): Boolean {
        for (k in YASAK) {
            if (m.contains(k)) return true
        }
        return false
    }

    /**
     * Üstteki bar dolunca YEŞİL oluyor (dolmadan turuncu/kahverengi).
     * Yazıyı okumak yerine barın iki ucundaki renge bakıyoruz.
     * Yazının üstüne denk gelmemesi için sadece sol ve sağ uçtan örnek alınır.
     */
    fun barYesil(bmp: Bitmap): Boolean {
        val w = bmp.width
        val h = bmp.height
        val satirlar = floatArrayOf(0.07f, 0.09f, 0.105f)
        var yesil = 0
        var toplam = 0
        for (sy in satirlar) {
            val py = (sy * h).toInt().coerceIn(0, h - 1)
            for (i in 0 until 20) {
                val fx = if (i < 10) {
                    0.345f + 0.035f * i / 9f
                } else {
                    0.60f + 0.03f * (i - 10) / 9f
                }
                val px = (fx * w).toInt().coerceIn(0, w - 1)
                val p = bmp.getPixel(px, py)
                val r = Color.red(p)
                val g = Color.green(p)
                val b = Color.blue(p)
                toplam++
                if (g > r + 20 && g > b + 25 && g > 100) yesil++
            }
        }
        return toplam > 0 && yesil * 100 / toplam >= 80
    }

    fun incele(ekran: Ekran, barYesil: Boolean): Sonuc {
        val satirlar = ArrayList<Yazi>()
        for (y in ekran.yazilar) {
            val m = duzle(y.metin)
            var bizim = false
            for (k in KENDI_YAZIMIZ) {
                if (m.contains(k)) bizim = true
            }
            if (!bizim) satirlar.add(y)
        }
        val metinler = satirlar.map { duzle(it.metin) }
        val tumu = metinler.joinToString(" | ")

        // Üst ortadaki düğme: önce yazısından
        var dugme: String? = null
        var dugmeX: Float? = null
        var dugmeY: Float? = null
        for (s in satirlar) {
            if (s.x in DUGME_X1..DUGME_X2 && s.y in DUGME_Y1..DUGME_Y2) {
                val m = duzle(s.metin)
                if (m.contains("yeniden dogus")) {
                    dugme = "YENİDEN DOĞUŞ"
                    dugmeX = s.x
                    dugmeY = s.y
                }
                if (m.contains("evrimles")) {
                    dugme = "EVRİMLEŞ"
                    dugmeX = s.x
                    dugmeY = s.y
                }
                if (m.contains("acma")) {
                    dugme = "AÇMA"
                    dugmeX = s.x
                    dugmeY = s.y
                }
            }
        }

        // Yazı okunamadıysa: bar yeşilse düğme vardır ve hep aynı yerdedir.
        if (dugme == null && barYesil) {
            dugme = "DOLU BAR"
            dugmeX = SABIT_DUGME_X
            dugmeY = SABIT_DUGME_Y
        }

        // Seviyeler
        val seviyeler = ArrayList<Int>()
        for (m in metinler) {
            for (e in LV.findAll(m)) {
                e.groupValues[1].toIntOrNull()?.let { seviyeler.add(it) }
            }
        }

        // Aşama
        var asama: String? = null
        for (m in metinler) {
            val e = ASAMA.find(m)
            if (e != null) asama = e.groupValues[1] + "/" + e.groupValues[2]
        }

        // Durum + tıklanacak nokta
        var durum = Durum.NORMAL
        var tikX: Float? = null
        var tikY: Float? = null

        when {
            // 1. Ölüm ekranı: sadece "Şimdi yeniden canlan" düğmesine basılır
            tumu.contains("yeniden canlan") -> {
                durum = Durum.OLUM
                for (s in satirlar) {
                    val m = duzle(s.metin)
                    if (m.contains("imdi yeniden") && !yasakli(m)) {
                        tikX = s.x
                        tikY = s.y
                        break
                    }
                }
                // Düğmenin yazısı okunamadıysa bilinen yerine bas
                if (tikX == null) {
                    tikX = OLUM_X
                    tikY = OLUM_Y
                }
            }

            // 2. Yeniden doğuş onayı ("Tekrar dokunun")
            tumu.contains("tekrar dokunun") -> {
                durum = Durum.YD_ONAY
                for (s in satirlar) {
                    val m = duzle(s.metin)
                    if (m.contains("tekrar dokunun") && !yasakli(m)) {
                        tikX = s.x
                        tikY = s.y
                        break
                    }
                }
            }

            // 3. Yeniden doğuş penceresi açık ve HAZIR
            tumu.contains("yeniden dogmaya hazir") || tumu.contains("yeniden dogus x") -> {
                durum = Durum.YD_HAZIR

                // Pencerenin altındaki büyük "YENİDEN DOĞUŞ X..." düğmesi
                var enIyi: Yazi? = null
                for (s in satirlar) {
                    val m = duzle(s.metin)
                    if (m.contains("yeniden dogus") && m.contains("x") && s.y > 0.7f) {
                        if (enIyi == null || s.y > enIyi.y) {
                            enIyi = s
                        }
                    }
                }
                if (enIyi != null) {
                    tikX = enIyi.x
                    tikY = enIyi.y
                } else {
                    // Yazı okunamadıysa düğmenin bilinen yerine bas
                    tikX = YD_DUGME_X
                    tikY = YD_DUGME_Y
                }
            }

            tumu.contains("henuz hazir degil") -> {
                durum = Durum.YD_HAZIR_DEGIL
            }

            tumu.contains("guc sonsuza kadar") -> {
                durum = Durum.YD_BITTI
            }

            dugme != null -> {
                durum = Durum.DUGME
                tikX = dugmeX
                tikY = dugmeY
            }
        }

        return Sonuc(durum, dugme, seviyeler, asama, tikX, tikY)
    }

    fun ozet(s: Sonuc): String {
        val ana = when (s.durum) {
            Durum.OLUM -> "ÖLÜM - canlanılacak"
            Durum.YD_ONAY -> "YD onay - basılacak"
            Durum.YD_HAZIR -> "YD HAZIR - basılacak"
            Durum.YD_HAZIR_DEGIL -> "YD hazır değil"
            Durum.YD_BITTI -> "YD tamamlandı"
            Durum.DUGME -> "düğme ${s.dugme ?: "?"} - basılacak"
            Durum.NORMAL -> "normal"
        }
        var t = "Gözcü: $ana"
        if (s.asama != null) t += " | Kademe ${s.asama}"
        if (s.seviyeler.isNotEmpty()) t += " | Rakip ${s.seviyeler.take(3).joinToString(",")}"
        return t
    }
}

/**
 * Ekranı okur + gerekli durumlarda tıklar.
 * ekranAl  -> MacroService.shoot()
 * durumYaz -> MacroService.setStatus()
 * tikla    -> (x, y) EKRAN PİKSELİ cinsinden (oran değil)
 * beyin    -> ekran NORMAL iken hareket / saldırı / kaçma / beceri kararlarını verir (isteğe bağlı)
 */
class OyunBotu(
    private val ekranAl: ((Bitmap?) -> Unit) -> Unit,
    private val durumYaz: (String) -> Unit,
    private val tikla: (Float, Float) -> Unit,
    private val beyin: Beyin? = null
) {
    private val el = Handler(Looper.getMainLooper())
    private val okuyucu = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
    private var calisiyor = false
    private var jeton = 0
    private var sonTiklama = 0L
    private val tiklamaCooldown = 900L   // aynı yere çok hızlı basmasın

    // Bar yeşili art arda kaç kez görüldü (yanlış alarmı önlemek için 2 kez bekler)
    private var doluSayac = 0

    // Son ekran görüntüsünün gerçek boyutu (piksel)
    private var ekranGen = 1f
    private var ekranYuk = 1f

    fun baslat() {
        if (calisiyor) return
        calisiyor = true
        jeton++
        doluSayac = 0
        durumYaz("Gözcü + Tıklama başladı")
        dongu(jeton)
    }

    fun durdur() {
        calisiyor = false
        jeton++
        el.removeCallbacksAndMessages(null)
        beyin?.durdur()
        try { okuyucu.close() } catch (_: Exception) {}
    }

    private fun dongu(my: Int) {
        if (!calisiyor || my != jeton) return
        ekranAl { bmp ->
            if (!calisiyor || my != jeton) return@ekranAl
            if (bmp == null) {
                durumYaz("Gözcü: ekran alınamadı")
                planla(my, 1500L)
                return@ekranAl
            }
            val yesil = Gozcu.barYesil(bmp)
            oku(bmp) { ekran ->
                if (!calisiyor || my != jeton) return@oku
                var gecikme = 1500L
                if (ekran == null) {
                    durumYaz("Gözcü: yazı okunamadı")
                } else {
                    val sonuc = Gozcu.incele(ekran, yesil)
                    var yazi = Gozcu.ozet(sonuc)

                    // Yeniden doğuş adımlarında daha sık bak (onay kısa süre açık kalabilir)
                    if (sonuc.durum == Durum.YD_HAZIR || sonuc.durum == Durum.YD_ONAY) {
                        gecikme = 600L
                    }

                    // Sadece bar rengine dayanan karar 2 kez üst üste görülmeden uygulanmaz
                    var engel = false
                    if (sonuc.durum == Durum.DUGME && sonuc.dugme == "DOLU BAR") {
                        doluSayac++
                        if (doluSayac < 2) engel = true
                    } else {
                        doluSayac = 0
                    }

                    // Tıklama kararı
                    if (!engel && sonuc.tiklanacakX != null && sonuc.tiklanacakY != null) {
                        val simdi = System.currentTimeMillis()
                        if (simdi - sonTiklama > tiklamaCooldown) {
                            sonTiklama = simdi
                            val px = sonuc.tiklanacakX * ekranGen
                            val py = sonuc.tiklanacakY * ekranYuk
                            tikla(px, py)
                            if (sonuc.durum == Durum.DUGME) beyin?.seviyeDegisti()
                            yazi += " | bastı " + px.toInt() + "," + py.toInt()
                        }
                    }
                    yazi += if (yesil) " | bar: yeşil" else " | bar: dolu değil"
                    if (beyin != null) yazi += beyin.bilgi()
                    durumYaz(yazi)
                    if (sonuc.durum == Durum.YD_BITTI) beyin?.seviyeDegisti()

                    // Ekran normalse ve basılacak bir şey yoksa beyin karar verir
                    if (beyin != null && sonuc.durum == Durum.NORMAL && !engel && sonuc.tiklanacakX == null) {
                        val k = beyin.adim(bmp, ekran, ekranGen, ekranYuk)
                        if (k > 0L) gecikme = k
                    }
                }
                planla(my, gecikme)
            }
        }
    }

    private fun planla(my: Int, gecikme: Long) {
        el.postDelayed({ dongu(my) }, gecikme)
    }

    private fun oku(bmp: Bitmap, bitti: (Ekran?) -> Unit) {
        val gen = bmp.width.toFloat()
        val yuk = bmp.height.toFloat()
        ekranGen = gen
        ekranYuk = yuk
        val img = InputImage.fromBitmap(bmp, 0)
        okuyucu.process(img)
            .addOnSuccessListener { sonuc ->
                val liste = ArrayList<Yazi>()
                for (blok in sonuc.textBlocks) {
                    for (satir in blok.lines) {
                        val r = satir.boundingBox
                        if (r != null) {
                            liste.add(
                                Yazi(
                                    satir.text,
                                    r.exactCenterX() / gen,
                                    r.exactCenterY() / yuk
                                )
                            )
                        }
                    }
                }
                bitti(Ekran(liste))
            }
            .addOnFailureListener {
                bitti(null)
            }
    }
}
