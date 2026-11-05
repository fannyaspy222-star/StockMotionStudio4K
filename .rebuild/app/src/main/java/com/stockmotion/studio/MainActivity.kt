package com.stockmotion.studio

import android.content.ContentValues
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.media.MediaMetadataRetriever
import android.media.MediaExtractor
import android.media.MediaMuxer
import android.media.MediaCodec
import android.media.MediaFormat
import java.nio.ByteBuffer
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
    private lateinit var durationChoice: Spinner

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
        root.addView(label("Durasi video", 15f))
        durationChoice = Spinner(this).apply {
            adapter = ArrayAdapter(this@MainActivity, android.R.layout.simple_spinner_dropdown_item,
                listOf("3,5 detik", "5 detik", "7,5 detik", "10 detik", "15 detik (2 segmen)", "20 detik (2 segmen)"))
            setSelection(3)
        }
        root.addView(durationChoice)
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
                val requestedDuration = listOf(3.5, 5.0, 7.5, 10.0, 15.0, 20.0)[durationChoice.selectedItemPosition]
                val clips = mutableListOf<File>()
                var remaining = requestedDuration
                var segmentInput = file
                var segmentIndex = 1
                while (remaining > 0.01) {
                    val segmentDuration = minOf(10.0, remaining)
                    runOnUiThread { status.text = "Membuat segmen " + segmentIndex + " untuk video " + requestedDuration.toInt() + " detik..." }
                    val segmentPrompt = if (segmentIndex == 1) text else "Continue seamlessly from the previous segment. " + text
                    val remote = callSpace(segmentInput, segmentPrompt, segmentDuration)
                    val clip = downloadToCache(remote, "stockmotion-segment-" + segmentIndex + ".mp4")
                    clips.add(clip)
                    remaining -= segmentDuration
                    if (remaining > 0.01) segmentInput = extractLastFrame(clip, segmentIndex)
                    segmentIndex++
                }
                val finalFile = if (clips.size == 1) clips.first() else mergeVideoClips(clips)
                val saved = saveLocalMp4(finalFile)
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
        val mime = contentResolver.getType(uri).orEmpty().lowercase()
        val source: Bitmap = contentResolver.openInputStream(uri).use { input ->
            requireNotNull(input) { "File gambar tidak dapat dibaca." }
            if (mime.contains("svg") || uri.toString().lowercase().contains(".svg")) {
                val svg = SVG.getFromInputStream(input)
                val originalW = if (svg.documentWidth > 0) svg.documentWidth.toInt() else 1024
                val originalH = if (svg.documentHeight > 0) svg.documentHeight.toInt() else 1024
                val factor = minOf(1f, 2048f / maxOf(originalW, originalH).coerceAtLeast(1))
                val w = (originalW * factor).toInt().coerceAtLeast(1)
                val h = (originalH * factor).toInt().coerceAtLeast(1)
                Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).also {
                    svg.renderToCanvas(android.graphics.Canvas(it))
                }
            } else {
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                contentResolver.openInputStream(uri).use { stream -> BitmapFactory.decodeStream(stream, null, bounds) }
                require(bounds.outWidth > 0 && bounds.outHeight > 0) { "Format gambar tidak terbaca." }
                var sample = 1
                while (bounds.outWidth / sample > 2048 || bounds.outHeight / sample > 2048) sample *= 2
                val options = BitmapFactory.Options().apply { inSampleSize = sample; inPreferredConfig = Bitmap.Config.ARGB_8888 }
                contentResolver.openInputStream(uri).use { stream ->
                    BitmapFactory.decodeStream(stream, null, options)
                } ?: error("Gambar tidak dapat didekode.")
            }
        }
        val maxSide = maxOf(source.width, source.height)
        val scaled = if (maxSide > 2048) {
            val ratio = 2048f / maxSide
            Bitmap.createScaledBitmap(source, (source.width * ratio).toInt().coerceAtLeast(1),
                (source.height * ratio).toInt().coerceAtLeast(1), true).also { if (it !== source) source.recycle() }
        } else source
        val png = File(cacheDir, "stockmotion-input.png")
        png.outputStream().use { output ->
            require(scaled.compress(Bitmap.CompressFormat.PNG, 100, output)) { "Gagal mengoptimalkan PNG." }
        }
        if (png.length() <= 12L * 1024 * 1024) {
            scaled.recycle()
            return png
        }
        // Fallback JPEG reduces very large PNGs; transparent areas become white.
        val jpg = File(cacheDir, "stockmotion-input.jpg")
        val flattened = Bitmap.createBitmap(scaled.width, scaled.height, Bitmap.Config.ARGB_8888)
        android.graphics.Canvas(flattened).apply {
            drawColor(android.graphics.Color.WHITE)
            drawBitmap(scaled, 0f, 0f, null)
        }
        scaled.recycle()
        var quality = 90
        do {
            jpg.outputStream().use { output ->
                require(flattened.compress(Bitmap.CompressFormat.JPEG, quality, output)) { "Gagal mengoptimalkan gambar." }
            }
            quality -= 10
        } while (jpg.length() > 12L * 1024 * 1024 && quality >= 60)
        flattened.recycle()
        require(jpg.length() <= 12L * 1024 * 1024) {
            "Ukuran gambar masih terlalu besar setelah optimasi. Coba gambar dengan resolusi lebih rendah."
        }
        return jpg
    }

    private fun callSpace(file: File, motion: String, durationSeconds: Double): String {
        val boundary = "----StockMotion${System.currentTimeMillis()}"
        val isJpeg = file.extension.equals("jpg", ignoreCase = true)
        val fileName = if (isJpeg) "input.jpg" else "input.png"
        val contentType = if (isJpeg) "image/jpeg" else "image/png"
        val upload = (URL("$space/gradio_api/upload").openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"; doOutput = true; connectTimeout = 30000; readTimeout = 60000
            setRequestProperty("Content-Type", "multipart/form-data; boundary=$boundary")
        }
        upload.outputStream.use { out ->
            out.write("--$boundary\r\nContent-Disposition: form-data; name=\"files\"; filename=\"$fileName\"\r\nContent-Type: $contentType\r\n\r\n".toByteArray())
            file.inputStream().use { it.copyTo(out) }
            out.write("\r\n--$boundary--\r\n".toByteArray())
        }
        val paths = JSONArray(readHttp(upload))
        val path = paths.optString(0)
        require(path.isNotBlank()) { "Upload gambar tidak mengembalikan path." }
        val imageData = JSONObject().apply {
            put("path", path); put("url", "$space/gradio_api/file=$path"); put("orig_name", fileName)
            put("size", JSONObject.NULL); put("mime_type", JSONObject.NULL)
            put("meta", JSONObject().put("_type", "gradio.FileData"))
        }
        val args = JSONArray().apply {
            put(imageData); put(JSONObject.NULL); put(motion); put(6)
            put("色调艳丽, 过曝, 静态, 低质量, 畸形的肢体, 杂乱的背景")
            put(durationSeconds); put(1); put(1); put(42); put(true); put(6)
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
        try {
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
        } catch (ready: VideoReady) {
            stream.disconnect()
            return ready.videoUrl
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

    private fun downloadToCache(remote: String, name: String): File {
        val target = File(cacheDir, name)
        val connection = URL(remote).openConnection() as HttpURLConnection
        connection.connectTimeout = 30000; connection.readTimeout = 180000
        connection.inputStream.use { input -> target.outputStream().use { input.copyTo(it) } }
        connection.disconnect()
        require(target.length() > 1024) { "Video segmen kosong atau tidak lengkap." }
        return target
    }

    private fun extractLastFrame(source: File, segment: Int): File {
        val retriever = MediaMetadataRetriever()
        try {
            retriever.setDataSource(source.absolutePath)
            val bitmap = retriever.getFrameAtTime(-1, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                ?: error("Tidak dapat mengambil frame akhir segmen " + segment + ".")
            val output = File(cacheDir, "stockmotion-continuation-" + segment + ".jpg")
            output.outputStream().use { require(bitmap.compress(Bitmap.CompressFormat.JPEG, 92, it)) }
            bitmap.recycle()
            return output
        } finally { retriever.release() }
    }

    private fun mergeVideoClips(clips: List<File>): File {
        require(clips.size >= 2)
        val output = File(cacheDir, "stockmotion-final-" + System.currentTimeMillis() + ".mp4")
        val firstExtractor = MediaExtractor()
        firstExtractor.setDataSource(clips.first().absolutePath)
        val firstTrack = (0 until firstExtractor.trackCount).firstOrNull {
            firstExtractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("video/") == true
        } ?: error("Track video tidak ditemukan.")
        val format = firstExtractor.getTrackFormat(firstTrack)
        firstExtractor.release()
        val muxer = MediaMuxer(output.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        val outputTrack = muxer.addTrack(format)
        muxer.start()
        var timeOffset = 0L
        try {
            for ((clipIndex, clip) in clips.withIndex()) {
                val extractor = MediaExtractor()
                extractor.setDataSource(clip.absolutePath)
                val track = (0 until extractor.trackCount).firstOrNull {
                    extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("video/") == true
                } ?: run { extractor.release(); error("Track video segmen " + (clipIndex + 1) + " tidak ditemukan.") }
                val sourceFormat = extractor.getTrackFormat(track)
                require(sourceFormat.getString(MediaFormat.KEY_MIME) == format.getString(MediaFormat.KEY_MIME) &&
                    sourceFormat.getInteger(MediaFormat.KEY_WIDTH) == format.getInteger(MediaFormat.KEY_WIDTH) &&
                    sourceFormat.getInteger(MediaFormat.KEY_HEIGHT) == format.getInteger(MediaFormat.KEY_HEIGHT)) {
                    "Format video antarsegmen berbeda; gabungan dibatalkan agar tidak rusak."
                }
                extractor.selectTrack(track)
                val buffer = ByteBuffer.allocate(4 * 1024 * 1024)
                val info = MediaCodec.BufferInfo()
                var lastPts = 0L
                while (true) {
                    buffer.clear()
                    val size = extractor.readSampleData(buffer, 0)
                    if (size < 0) break
                    info.offset = 0; info.size = size
                    info.presentationTimeUs = extractor.sampleTime + timeOffset
                    info.flags = extractor.sampleFlags
                    lastPts = extractor.sampleTime
                    muxer.writeSampleData(outputTrack, buffer, info)
                    extractor.advance()
                }
                val frameRate = if (sourceFormat.containsKey(MediaFormat.KEY_FRAME_RATE)) sourceFormat.getInteger(MediaFormat.KEY_FRAME_RATE).coerceAtLeast(1) else 16
                timeOffset += lastPts + 1_000_000L / frameRate
                extractor.release()
            }
        } finally { try { muxer.stop() } catch (_: Exception) {}; muxer.release() }
        require(output.length() > 1024) { "Gagal menggabungkan video." }
        return output
    }

    private fun saveLocalMp4(file: File): Uri {
        val values = ContentValues().apply {
            put(MediaStore.Video.Media.DISPLAY_NAME, "StockMotion-" + System.currentTimeMillis() + ".mp4")
            put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
            put(MediaStore.Video.Media.RELATIVE_PATH, "Movies/StockMotion")
            put(MediaStore.Video.Media.IS_PENDING, 1)
        }
        val uri = contentResolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values)
            ?: error("Tidak dapat membuat file video.")
        try {
            file.inputStream().use { input -> contentResolver.openOutputStream(uri).use { output ->
                requireNotNull(output) { "Tidak dapat menyimpan video." }; input.copyTo(output)
            } }
            values.clear(); values.put(MediaStore.Video.Media.IS_PENDING, 0)
            contentResolver.update(uri, values, null, null)
            return uri
        } catch (e: Exception) { contentResolver.delete(uri, null, null); throw e }
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
