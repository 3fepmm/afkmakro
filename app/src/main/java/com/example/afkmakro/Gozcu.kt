package com.example.afkmakro

import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions

// ADIM 1: SADECE İZLEME. Bu dosya ekrana hiçbir şekilde dokunmaz.
// Ekran görüntüsünü alır, yazıları okur, ne gördüğünü panelin durum satırına yazar.

/** Ekranda okunan tek bir satır yazı. x ve y satırın merkezi (ekranın 0..1 oranı). */
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
    val asama: String?
)

object Gozcu {
    // Üst ortadaki ilerleme düğmesinin bölgesi (ekran oranı).
    // Evrimleş / Açma / Yeniden Doğuş! yazıları hep burada çıkıyor.
    private const val DUGME_X1 = 0.38f
    private const val DUGME_X2 = 0.62f
    private const val DUGME_Y1 = 0.14f
    private const val DUGME_Y2 = 0.23f

    private val LV = Regex("lv\\s*(\\d{1,2})")
    private val ASAMA = Regex("asama\\s*(\\d{1,2})\\s*/\\s*(\\d{1,2})")

    // Panelin kendi durum yazısını yanlışlıkla oyun yazısı sanmamak için.
    private val KENDI_YAZIMIZ = listOf("gozcu", "kademe", "rakip")

    /** Küçük harfe çevirir ve Türkçe harfleri sadeleştirir: "Doğuş" -> "dogus". */
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
        // Kendi yazdığımız durum satırlarını ayıkla
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

        // Üst ortadaki ilerleme düğmesi
        var dugme: String? = null
        for (s in satirlar) {
            if (s.x in DUGME_X1..DUGME_X2 && s.y in DUGME_Y1..DUGME_Y2) {
                val m = duzle(s.metin)
                if (m.contains("yeniden dogus")) dugme = "YENİDEN DOĞUŞ"
                if (m.contains("evrimles")) dugme = "EVRİMLEŞ"
                if (m.contains("acma")) dugme = "AÇMA"
            }
        }

        // Oyuncu etiketlerindeki LV değerleri
        val seviyeler = ArrayList<Int>()
        for (m in metinler) {
            for (e in LV.findAll(m)) {
                val n = e.groupValues[1].toIntOrNull()
                if (n != null) seviyeler.add(n)
            }
        }

        // Evrimler penceresindeki "Aşama 4 / 21"
        var asama: String? = null
        for (m in metinler) {
            val e = ASAMA.find(m)
            if (e != null) asama = e.groupValues[1] + "/" + e.groupValues[2]
        }

        val durum = when {
            tumu.contains("tekrar dokunun") -> Durum.YD_ONAY
            tumu.contains("yeniden dogmaya hazir") -> Durum.YD_HAZIR
            tumu.contains("henuz hazir degil") -> Durum.YD_HAZIR_DEGIL
            tumu.contains("yeniden canlan") -> Durum.OLUM
            tumu.contains("guc sonsuza kadar") -> Durum.YD_BITTI
            dugme != null -> Durum.DUGME
            else -> Durum.NORMAL
        }
        return Sonuc(durum, dugme, seviyeler, asama)
    }

    /** Panelin durum satırına yazılacak KISA özet. (Satır sarmasın diye kısa tutuldu.) */
    fun ozet(s: Sonuc): String {
        val ana = when (s.durum) {
            Durum.OLUM -> "ÖLÜM ekranı"
            Durum.YD_ONAY -> "YD onay"
            Durum.YD_HAZIR -> "YD pencere HAZIR"
            Durum.YD_HAZIR_DEGIL -> "YD pencere hazır değil"
            Durum.YD_BITTI -> "YD tamamlandı"
            Durum.DUGME -> "düğme " + (s.dugme ?: "?")
            Durum.NORMAL -> "normal"
        }
        var t = "Gözcü: " + ana
        if (s.asama != null) t += " | Kademe " + s.asama
        if (s.seviyeler.isNotEmpty()) t += " | Rakip " + s.seviyeler.take(3).joinToString(",")
        return t
    }
}

/**
 * Birkaç saniyede bir ekran görüntüsü alır, yazıları okur, durumu bildirir.
 * ekranAl: MacroService'teki shoot() fonksiyonu.
 * durumYaz: MacroService'teki setStatus() fonksiyonu.
 */
class OyunBotu(
    private val ekranAl: ((Bitmap?) -> Unit) -> Unit,
    private val durumYaz: (String) -> Unit
) {
    private val el = Handler(Looper.getMainLooper())
    private val okuyucu = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
    private var calisiyor = false
    private var jeton = 0

    fun baslat() {
        if (calisiyor) return
        calisiyor = true
        jeton++
        durumYaz("Gözcü başladı")
        dongu(jeton)
    }

    fun durdur() {
        calisiyor = false
        jeton++
        el.removeCallbacksAndMessages(null)
        try {
            okuyucu.close()
        } catch (e: Exception) {
        }
    }

    private fun dongu(my: Int) {
        if (!calisiyor || my != jeton) return
        ekranAl({ bmp ->
            if (calisiyor && my == jeton) {
                if (bmp == null) {
                    durumYaz("Gözcü: ekran alınamadı")
                    planla(my)
                } else {
                    oku(bmp, { ekran ->
                        if (calisiyor && my == jeton) {
                            if (ekran == null) {
                                durumYaz("Gözcü: yazı okunamadı")
                            } else {
                                durumYaz(Gozcu.ozet(Gozcu.incele(ekran)))
                            }
                            planla(my)
                        }
                    })
                }
            }
        })
    }

    private fun planla(my: Int) {
        el.postDelayed({ dongu(my) }, 2500L)
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
                            liste.add(Yazi(satir.text, r.exactCenterX() / gen, r.exactCenterY() / yuk))
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
