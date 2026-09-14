package com.astro.pdfprice

import android.content.Context
import android.graphics.Bitmap
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import com.googlecode.tesseract.android.TessBaseAPI
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale

/**
 * On-device OCR for image-only/scanned PDFs.
 * Tesseract LSTM models are downloaded once to app-private storage.
 * Persian + Arabic + English are supported so product names/codes/brands
 * in mixed-language price lists can be indexed.
 */
class OcrEngine(private val context: Context) {
    companion object {
        private const val TESSDATA_URL = "https://raw.githubusercontent.com/tesseract-ocr/tessdata_fast/main/"
        private val MODELS = mapOf(
            "fas" to "fas.traineddata",
            "ara" to "ara.traineddata",
            "eng" to "eng.traineddata"
        )
    }

    private val tessDir = File(context.filesDir, "tesseract")
    private val dataDir = File(tessDir, "tessdata")

    fun ensureModels(onProgress: (String) -> Unit) {
        if (!dataDir.exists()) dataDir.mkdirs()
        MODELS.forEach { (lang, fileName) ->
            val target = File(dataDir, fileName)
            if (!target.exists() || target.length() < 100_000) {
                onProgress("دریافت مدل OCR: $lang")
                download(TESSDATA_URL + fileName, target)
            }
        }
    }

    fun recognizePage(uri: Uri, pageIndex: Int): String {
        context.contentResolver.openFileDescriptor(uri, "r")?.use { pfd ->
            PdfRenderer(pfd).use { renderer ->
                if (pageIndex !in 0 until renderer.pageCount) return ""
                renderer.openPage(pageIndex).use { page ->
                    val scale = 2.2f
                    val width = (page.width * scale).toInt().coerceAtMost(2600)
                    val height = (page.height * scale).toInt().coerceAtMost(3600)
                    val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                    bitmap.eraseColor(android.graphics.Color.WHITE)
                    page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_PRINT)
                    return recognizeBitmap(bitmap)
                }
            }
        }
        return ""
    }

    private fun recognizeBitmap(bitmap: Bitmap): String {
        val api = TessBaseAPI()
        return try {
            api.init(tessDir.absolutePath, "fas+ara+eng")
            api.pageSegMode = TessBaseAPI.PageSegMode.PSM_AUTO
            api.setImage(bitmap)
            api.getUTF8Text().orEmpty()
        } finally {
            api.clear()
            api.end()
            bitmap.recycle()
        }
    }

    private fun download(url: String, target: File) {
        val tmp = File(target.parentFile, target.name + ".part")
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 20_000
            readTimeout = 60_000
            requestMethod = "GET"
            setRequestProperty("User-Agent", "SmartPDFPriceFinder/1.0")
        }
        try {
            if (conn.responseCode !in 200..299) error("OCR model download failed: ${conn.responseCode}")
            conn.inputStream.use { input ->
                FileOutputStream(tmp).use { output -> input.copyTo(output, 64 * 1024) }
            }
            if (!tmp.renameTo(target)) {
                target.delete()
                if (!tmp.renameTo(target)) error("Cannot install OCR model ${target.name}")
            }
        } finally {
            conn.disconnect()
            if (tmp.exists()) tmp.delete()
        }
    }
}
