package com.stockmotion.studio

import android.content.ContentValues
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Bundle
import android.provider.MediaStore
import android.view.Gravity
import android.view.View
import android.widget.*
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import com.caverock.androidsvg.SVG
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors

class MainActivity : ComponentActivity() {
    private val worker = Executors.newSingleThreadExecutor()
    private val space = "https://cbensimon-wan2-2-fp8da-aoti-preview2.hf.space"
    private var imageUri: Uri? = null
    private lateinit var prompt: EditText
    private lateinit var status: TextView
    private lateinit var generate: Button
    private lateinit var progress: ProgressBar
    private lateinit var video: VideoView

    private val picker = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            try { contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) } catch (_: Exception) {}
            imageUri = uri
            status.text = "Gambar dipilih. Tulis deskripsi gerakannya."
            generate.isEnabled = true
            video.visibility = View.GONE
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(24, 24, 24, 24)
            setBackgroundColor(0xFFF5F6FA.toInt())
        }
        fun label(value: String, size: Float) = TextView(this).apply {
            text = value; textSize = size; gravity = Gravity.CENTER
            setTextColor(0xFF172033.toInt()); setPadding(0, 12, 0, 12)
        }
        root.addView(label("StockMotion Studio 4K", 25f))
        root.addView(label("AI Image-to-Video · Prototype 0.4.0", 14f))
        root.addView(Button(this).apply {
            text = "Pilih gambar JPG / PNG / SVG"
            setOnClickListener { picker.launch(arrayOf("image/*", "image/svg+xml")) }
        })
        prompt = EditText(this).apply {
            hint = "Contoh: Daun bergerak tertiup angin, kamera perlahan mendekat"
            minLines = 3; maxLines = 5; textSize = 16f
        }
        root.addView(prompt)
        generate = Button(this).apply {
            text = "Generate Video"; isEnabled = false
            setOnClickListener { generateVideo() }
        }
        root.addView(generate)
        progress = ProgressBar(this).apply { visibility = View.GONE }
        root.addView(progress)
        status = label("Pilih gambar lalu tulis prompt Bahasa Indonesia atau Inggris.", 14f)
        root.addView(status)
        video = VideoView(this).apply { visibility = View.GONE }
        root.addView(video, LinearLayout.LayoutParams(-1, 420))
        root.addView(label("Layanan GPU publik memiliki antrean dan kuota. Hasil bukan 4K. Gambar dikirim ke layanan eksternal; jangan unggah gambar sensitif.", 12f))
        setContentView(ScrollView(this).apply { addView(root) })
    }

    private fun generateVideo() {
        val uri = imageUri ?: return
        val text = prompt.text.toString().trim()
        if (text.isEmpty()) { status.text = "Tulis prompt gerakan dahulu."; return }
        generate.isEnabled = false; progress.visibility = View.VISIBLE; video.visibility = View.GONE
        status.text = "Mengirim gambar ke layanan AI dan menunggu antrean GPU..."
        worker.execute {
            try {
                val file = convertImage(uri)
                if (file.length() > 8L * 1024 * 1024) error("Gambar hasil konversi lebih dari 8 MB.")
                val resultUrl = callSpace(file, text)
                val saved = saveMp4(resultUrl)
                runOnUiThread {
                    progress.visibility = View.GONE; generate.isEnabled = true
                    status.text = "Selesai. Video tersimpan di Movies/StockMotion."
                    video.setVideoURI(saved); video.visibility = View.VISIBLE; video.start()
                }
            } catch (e: Exception) {
                runOnUiThread {
                    progress.visibility = View.GONE; generate.isEnabled = true
                    status.text = "Gagal: ${e.message ?: "Kesalahan layanan"}"
                }
            }
        }
    }

    private fun convertImage(uri: Uri): File {
        val out = File(cacheDir, "stockmotion-input.png")
        val mime = contentResolver.getType(uri).orEmpty().lowercase()
        contentResolver.openInputStream(uri).use { input ->
            requireNotNull(input) { "File gambar tidak dapat dibaca." }
            if (mime.contains("svg") || uri.toString().lowercase().contains(".svg")) {
                val svg = SVG.getFromInputStream(input)
                val w = (if (svg.documentWidth > 0) svg.documentWidth.toInt() else 1024).coerceIn(1, 2048)
                val h = (if (svg.documentHeight > 0) svg.documentHeight.toInt() else 1024).coerceIn(1, 2048)
                val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
                svg.renderToCanvas(android.graphics.Canvas(bitmap))
                out.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
                bitmap.recycle()
            } else {
                val bitmap = BitmapFactory.decodeStream(input) ?: error("Format gambar tidak terbaca.")
                out.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
                bitmap.recycle()
            }
        }
        return out
    }

    private fun callSpace(file: File, motion: String): String {
        val boundary = "----StockMotion${System.currentTimeMillis()}"
        val upload = (URL("$space/gradio_api/upload").openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"; doOutput = true; connectTimeout = 30000; readTimeout = 60000
            setRequestProperty("Content-Type", "multipart/form-data; boundary=$boundary")
        }
        upload.outputStream.use { out ->
            out.write("--$boundary\r\nContent-Disposition: form-data; name=\"files\"; filename=\"input.png\"\r\nContent-Type: image/png\r\n\r\n".toByteArray())
            file.inputStream().use { it.copyTo(out) }
            out.write("\r\n--$boundary--\r\n".toByteArray())
        }
        val paths = JSONArray(readHttp(upload))
        val path = paths.optString(0)
        require(path.isNotBlank()) { "Upload gambar tidak mengembalikan path." }
        val imageData = JSONObject().apply {
            put("path", path); put("url", "$space/gradio_api/file=$path"); put("orig_name", "input.png")
            put("size", JSONObject.NULL); put("mime_type", JSONObject.NULL)
            put("meta", JSONObject().put("_type", "gradio.FileData"))
        }
        val args = JSONArray().apply {
            put(imageData); put(JSONObject.NULL); put(motion); put(6)
            put("色调艳丽, 过曝, 静态, 低质量, 畸形的肢体, 杂乱的背景")
            put(3.5); put(1); put(1); put(42); put(true); put(6)
            put("UniPCMultistep"); put(3.0); put(16); put(false); put(true)
        }
        val submit = (URL("$space/gradio_api/call/generate_video").openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"; doOutput = true; connectTimeout = 30000; readTimeout = 60000
            setRequestProperty("Content-Type", "application/json")
        }
        submit.outputStream.use { it.write(JSONObject().put("data", args).toString().toByteArray()) }
        val eventId = JSONObject(readHttp(submit)).optString("event_id")
        require(eventId.isNotBlank()) { "Server tidak mengembalikan ID antrean." }
        runOnUiThread { status.text = "Job diterima. Menunggu proses GPU..." }
        val stream = (URL("$space/gradio_api/call/generate_video/$eventId").openConnection() as HttpURLConnection).apply {
            connectTimeout = 30000; readTimeout = 300000; setRequestProperty("Accept", "text/event-stream")
        }
        var event = ""; val data = StringBuilder()
        stream.inputStream.bufferedReader().useLines { lines ->
            lines.forEach { line ->
                when {
                    line.startsWith("event:") -> event = line.substringAfter("event:").trim()
                    line.startsWith("data:") -> data.append(line.substringAfter("data:").trim())
                    line.isBlank() -> {
                        if (event == "error") error(data.toString().take(300))
                        if (event == "complete") {
                            val first = JSONArray(data.toString()).optJSONObject(0) ?: error("Format hasil video tidak dikenal.")
                            val url = first.optString("url").ifBlank {
                                first.optString("path").takeIf { it.isNotBlank() }?.let { "$space/gradio_api/file=$it" }.orEmpty()
                            }
                            require(url.isNotBlank()) { "URL video tidak ditemukan." }
                            throw VideoReady(url)
                        }
                        event = ""; data.clear()
                    }
                }
            }
        }
        error("Antrean terputus sebelum video selesai. Coba lagi nanti.")
    }

    private class VideoReady(val videoUrl: String) : RuntimeException()

    private fun readHttp(c: HttpURLConnection): String {
        val code = c.responseCode
        val body = (if (code in 200..299) c.inputStream else c.errorStream)?.bufferedReader()?.use { it.readText() }.orEmpty()
        c.disconnect()
        if (code !in 200..299) error("Layanan AI HTTP $code: ${body.take(200)}")
        return body
    }

    private fun saveMp4(remote: String): Uri {
        val values = ContentValues().apply {
            put(MediaStore.Video.Media.DISPLAY_NAME, "StockMotion-${System.currentTimeMillis()}.mp4")
            put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
            put(MediaStore.Video.Media.RELATIVE_PATH, "Movies/StockMotion")
            put(MediaStore.Video.Media.IS_PENDING, 1)
        }
        val uri = contentResolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values)
            ?: error("Tidak dapat membuat file video.")
        try {
            val connection = URL(remote).openConnection() as HttpURLConnection
            connection.connectTimeout = 30000; connection.readTimeout = 120000
            connection.inputStream.use { input ->
                contentResolver.openOutputStream(uri).use { output ->
                    requireNotNull(output) { "Tidak dapat menyimpan video." }; input.copyTo(output)
                }
            }
            values.clear(); values.put(MediaStore.Video.Media.IS_PENDING, 0)
            contentResolver.update(uri, values, null, null)
            connection.disconnect()
            return uri
        } catch (e: Exception) { contentResolver.delete(uri, null, null); throw e }
    }

    override fun onDestroy() { worker.shutdownNow(); super.onDestroy() }
}
