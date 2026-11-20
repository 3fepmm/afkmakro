package com.example.afkmakro

import android.graphics.Bitmap
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
    // Üst ortadaki ilerleme düğmesinin bölgesi
    private const val DUGME_X1 = 0.38f
    private const val DUGME_X2 = 0.62f
    private const val DUGME_Y1 = 0.14f
    private const val DUGME_Y2 = 0.23f

    private val LV = Regex("lv\\s*(\\d{1,2})")
    private val ASAMA = Regex("asama\\s*(\\d{1,2})\\s*/\\s*(\\d{1,2})")

    private val KENDI_YAZIMIZ = listOf("gozcu", "kademe", "rakip", "afk")

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

    fun incele(ekran: Ekran): Sonuc {
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

    // Üst ortadaki düğme
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
        // 1. Ölüm ekranı
        tumu.contains("yeniden canlan") || tumu.contains("simdi yeniden canlan") -> {
            durum = Durum.OLUM
            for (s in satirlar) {
                val m = duzle(s.metin)
                if (m.contains("yeniden canlan") || m.contains("simdi yeniden")) {
                    tikX = s.x
                    tikY = s.y
                    break
                }
            }
        }

        // 2. Yeniden doğuş onay ekranı ("Tekrar dokunun")
        tumu.contains("tekrar dokunun") -> {
            durum = Durum.YD_ONAY
            for (s in satirlar) {
                if (duzle(s.metin).contains("tekrar dokunun")) {
                    tikX = s.x
                    tikY = s.y
                    break
                }
            }
        }

        // 3. Yeniden doğuş penceresi açık ve HAZIR
        tumu.contains("yeniden dogmaya hazir") || tumu.contains("yeniden dogus x") -> {
            durum = Durum.YD_HAZIR

            // Öncelik: Sarı büyük "YENİDEN DOĞUŞ" butonunu bul
            var enIyi: Yazi? = null
            for (s in satirlar) {
                val m = duzle(s.metin)
                if (m.contains("yeniden dogus") && (m.contains("x") || m.contains("x6") || m.contains("x5") || m.contains("x7"))) {
                    // En alttaki ve en büyük ihtimalle buton olanı seç
                    if (enIyi == null || s.y > enIyi.y) {
                        enIyi = s
                    }
                }
            }

            // Bulamazsa "Yeniden doğmaya hazır!" yazısının biraz altını tıkla
            if (enIyi != null) {
                tikX = enIyi.x
                tikY = enIyi.y
            } else {
                for (s in satirlar) {
                    if (duzle(s.metin).contains("yeniden dogmaya hazir")) {
                        tikX = s.x
                        tikY = (s.y + 0.08f).coerceAtMost(0.92f) // biraz aşağı tıkla
                        break
                    }
                }
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
            Durum.OLUM -> "ÖLÜM → tıklanacak"
            Durum.YD_ONAY -> "YD onay → tıklanacak"
            Durum.YD_HAZIR -> "YD HAZIR → tıklanacak"
            Durum.YD_HAZIR_DEGIL -> "YD hazır değil"
            Durum.YD_BITTI -> "YD tamamlandı"
            Durum.DUGME -> "düğme ${s.dugme ?: "?"} → tıklanacak"
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
 * ekranAl  → MacroService.shoot()
 * durumYaz → MacroService.setStatus()
 * tikla    → (xOran, yOran) ekranın 0..1 oranı
 */
class OyunBotu(
    private val ekranAl: ((Bitmap?) -> Unit) -> Unit,
    private val durumYaz: (String) -> Unit,
    private val tikla: (Float, Float) -> Unit
) {
    private val el = Handler(Looper.getMainLooper())
    private val okuyucu = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
    private var calisiyor = false
    private var jeton = 0
    private var sonTiklama = 0L
    private val tiklamaCooldown = 1400L   // aynı yere çok hızlı basmasın

    fun baslat() {
        if (calisiyor) return
        calisiyor = true
        jeton++
        durumYaz("Gözcü + Tıklama başladı")
        dongu(jeton)
    }

    fun durdur() {
        calisiyor = false
        jeton++
        el.removeCallbacksAndMessages(null)
        try { okuyucu.close() } catch (_: Exception) {}
    }

    private fun dongu(my: Int) {
        if (!calisiyor || my != jeton) return
        ekranAl { bmp ->
            if (!calisiyor || my != jeton) return@ekranAl
            if (bmp == null) {
                durumYaz("Gözcü: ekran alınamadı")
                planla(my)
                return@ekranAl
            }
            oku(bmp) { ekran ->
                if (!calisiyor || my != jeton) return@oku
                if (ekran == null) {
                    durumYaz("Gözcü: yazı okunamadı")
                } else {
                    val sonuc = Gozcu.incele(ekran)
                    durumYaz(Gozcu.ozet(sonuc))

                    // Tıklama kararı
                    if (sonuc.tiklanacakX != null && sonuc.tiklanacakY != null) {
                        val simdi = System.currentTimeMillis()
                        if (simdi - sonTiklama > tiklamaCooldown) {
                            sonTiklama = simdi
                            tikla(sonuc.tiklanacakX, sonuc.tiklanacakY)
                        }
                    }
                }
                planla(my)
            }
        }
    }

    private fun planla(my: Int) {
        el.postDelayed({ dongu(my) }, 1500L)
    }

    private fun oku(bmp: Bitmap, bitti: (Ekran?) -> Unit) {
        val gen = bmp.width.toFloat()
        val yuk = bmp.height.toFloat()
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
