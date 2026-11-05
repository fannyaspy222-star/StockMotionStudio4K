package com.stockmotion.studio

import android.content.ContentValues
import android.content.Intent
import android.graphics.*
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import android.net.Uri
import android.os.Bundle
import android.provider.MediaStore
import android.view.Gravity
import android.view.Surface
import android.widget.*
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import com.caverock.androidsvg.SVG
import java.io.File
import kotlin.math.min

class ShowcaseActivity : ComponentActivity() {
    private val images = mutableListOf<Bitmap>()
    private lateinit var status: TextView
    private lateinit var pickButton: Button
    private lateinit var makeButton: Button
    private lateinit var titleInput: EditText
    private lateinit var userInput: EditText
    private lateinit var layoutChoice: Spinner
    private lateinit var durationChoice: Spinner

    private val picker = registerForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isNotEmpty()) {
            Thread {
                val loaded = mutableListOf<Bitmap>()
                uris.take(12).forEach { uri ->
                    try {
                        contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        val bitmap = contentResolver.openInputStream(uri)?.use { input ->
                            val mime = contentResolver.getType(uri).orEmpty()
                            if (mime.contains("svg") || uri.toString().contains(".svg", true)) {
                                val svg = SVG.getFromInputStream(input)
                                val w = if (svg.documentWidth > 0) svg.documentWidth.toInt().coerceIn(1, 2048) else 1024
                                val h = if (svg.documentHeight > 0) svg.documentHeight.toInt().coerceIn(1, 2048) else 1024
                                Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).also { svg.renderToCanvas(Canvas(it)) }
                            } else BitmapFactory.decodeStream(input)
                        }
                        if (bitmap != null) loaded.add(bitmap)
                    } catch (_: Exception) { }
                }
                runOnUiThread {
                    images.clear(); images.addAll(loaded)
                    status.text = if (images.isEmpty()) "Gambar tidak dapat dibaca." else "${images.size} gambar siap disusun."
                    makeButton.isEnabled = images.isNotEmpty()
                }
            }.start()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(28, 20, 28, 20)
            setBackgroundColor(Color.rgb(246, 247, 251))
        }
        fun label(s: String, size: Float) = TextView(this).apply {
            text = s; textSize = size; gravity = Gravity.CENTER
            setTextColor(Color.rgb(25, 34, 54)); setPadding(0, 10, 0, 10)
        }
        root.addView(label("VECTOR SHOWCASE MAKER", 22f))
        root.addView(label("Buat video promosi desain otomatis", 14f))
        pickButton = Button(this).apply {
            text = "PILIH BANYAK GAMBAR JPG / PNG / SVG"
            setOnClickListener { picker.launch(arrayOf("image/*", "image/svg+xml")) }
        }
        root.addView(pickButton)
        titleInput = EditText(this).apply { hint = "Judul video, contoh: Vector Collection"; setSingleLine() }
        root.addView(titleInput)
        userInput = EditText(this).apply { hint = "Username / watermark, contoh: @namamu"; setSingleLine() }
        root.addView(userInput)
        root.addView(label("Susunan desain", 14f))
        layoutChoice = Spinner(this).apply {
            adapter = ArrayAdapter(this@ShowcaseActivity, android.R.layout.simple_spinner_dropdown_item,
                listOf("Grid 2 × 2", "Grid 2 × 3"))
        }
        root.addView(layoutChoice)
        root.addView(label("Durasi video", 14f))
        durationChoice = Spinner(this).apply {
            adapter = ArrayAdapter(this@ShowcaseActivity, android.R.layout.simple_spinner_dropdown_item,
                listOf("10 detik", "15 detik", "20 detik"))
        }
        root.addView(durationChoice)
        makeButton = Button(this).apply {
            text = "BUAT VIDEO MP4"; isEnabled = false
            setOnClickListener { makeVideo() }
        }
        root.addView(makeButton)
        status = label("Pilih beberapa desain untuk memulai.", 14f)
        root.addView(status)
        root.addView(Button(this).apply { text = "KEMBALI"; setOnClickListener { finish() } })
        setContentView(ScrollView(this).apply { addView(root) })
    }

    private fun makeVideo() {
        if (images.isEmpty()) return
        makeButton.isEnabled = false; pickButton.isEnabled = false
        val title = titleInput.text.toString().ifBlank { "VECTOR COLLECTION" }
        val username = userInput.text.toString()
        val cols = 2
        val rows = if (layoutChoice.selectedItemPosition == 0) 2 else 3
        val seconds = listOf(10, 15, 20)[durationChoice.selectedItemPosition]
        status.text = "Merender video di perangkat... jangan tutup aplikasi."
        Thread {
            try {
                val file = renderVideo(title, username, cols, rows, seconds) { percent ->
                    runOnUiThread { status.text = "Merender video... $percent%" }
                }
                runOnUiThread { status.text = "Menyimpan video ke galeri... 95%" }
                val saved = saveVideo(file)
                runOnUiThread {
                    status.text = "Selesai! Video tersimpan di Movies/StockMotion."
                    makeButton.isEnabled = true; pickButton.isEnabled = true
                    Toast.makeText(this, "Video berhasil dibuat", Toast.LENGTH_LONG).show()
                }
            } catch (e: Exception) {
                runOnUiThread {
                    status.text = "Render gagal: ${e.javaClass.simpleName}: ${e.message ?: "tanpa detail"}"
                    makeButton.isEnabled = true; pickButton.isEnabled = true
                }
            }
        }.start()
    }

    private fun renderVideo(title: String, username: String, cols: Int, rows: Int, seconds: Int, onProgress: (Int) -> Unit): File {
        val width = 720; val height = 1280; val fps = 24
        val output = File(cacheDir, "showcase-${System.currentTimeMillis()}.mp4")
        val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, width, height).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
            setInteger(MediaFormat.KEY_BIT_RATE, 4_000_000)
            setInteger(MediaFormat.KEY_FRAME_RATE, fps)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 2)
        }
        val encoder = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
        var surface: Surface? = null
        var muxer: MediaMuxer? = null
        var muxerStarted = false
        var trackIndex = -1
        try {
            encoder.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            surface = encoder.createInputSurface()
            encoder.start()
            muxer = MediaMuxer(output.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            val info = MediaCodec.BufferInfo()
            var frameCount = 0
            var ended = false
            fun drain(end: Boolean) {
                while (true) {
                    val index = encoder.dequeueOutputBuffer(info, if (end) 10000 else 0)
                    when {
                        index == MediaCodec.INFO_TRY_AGAIN_LATER -> if (!end) return
                        index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                            check(!muxerStarted)
                            trackIndex = muxer!!.addTrack(encoder.outputFormat)
                            muxer.start(); muxerStarted = true
                        }
                        index >= 0 -> {
                            val buffer = encoder.getOutputBuffer(index)!!
                            if (info.size > 0 && muxerStarted) {
                                buffer.position(info.offset); buffer.limit(info.offset + info.size)
                                muxer!!.writeSampleData(trackIndex, buffer, info)
                            }
                            val eos = (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0
                            encoder.releaseOutputBuffer(index, false)
                            if (eos) { ended = true; return }
                        }
                    }
                    if (end && !ended) continue
                }
            }
            val total = seconds * fps
            for (frame in 0 until total) {
                val canvas = surface!!.lockCanvas(null)
                try { drawFrame(canvas, frame, total, title, username, cols, rows) }
                finally { surface.unlockCanvasAndPost(canvas) }
                frameCount++
                drain(false)
                if (frame % 6 == 0) onProgress(((frame + 1) * 90 / total).coerceIn(1, 90))
            }
            onProgress(92)
            encoder.signalEndOfInputStream()
            drain(true)
            check(muxerStarted && output.length() > 1024) { "Encoder tidak menghasilkan video." }
        } finally {
            try { surface?.release() } catch (_: Exception) {}
            try { encoder.stop() } catch (_: Exception) {}
            encoder.release()
            try { if (muxerStarted) muxer?.stop() } catch (_: Exception) {}
            muxer?.release()
        }
        return output
    }

    private fun drawFrame(c: Canvas, frame: Int, total: Int, title: String, username: String, cols: Int, rows: Int) {
        val w = 720f; val h = 1280f
        c.drawColor(Color.rgb(13, 18, 34))
        val p = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
        p.color = Color.WHITE; p.textAlign = Paint.Align.CENTER; p.typeface = Typeface.create("sans-serif", Typeface.BOLD)
        p.textSize = 42f
        c.drawText(title.uppercase().take(30), w / 2, 105f, p)
        val gap = 14f; val top = 160f; val bottom = 150f
        val cellW = (w - gap * (cols + 1)) / cols
        val cellH = (h - top - bottom - gap * (rows + 1)) / rows
        val page = (frame / (total / 3f)).toInt().coerceIn(0, 2)
        val phase = (frame % (total / 3f)) / (total / 3f)
        for (i in 0 until cols * rows) {
            val idx = (page * cols * rows + i) % images.size
            val bitmap = images[idx]
            val x = gap + (i % cols) * (cellW + gap)
            val y = top + (i / cols) * (cellH + gap)
            val scale = 1.0f + 0.035f * phase
            val dw = cellW * scale; val dh = cellH * scale
            val dst = RectF(x - (dw-cellW)/2, y - (dh-cellH)/2, x + cellW + (dw-cellW)/2, y + cellH + (dh-cellH)/2)
            c.save()
            val clip = Path().apply { addRoundRect(RectF(x,y,x+cellW,y+cellH), 18f,18f,Path.Direction.CW) }
            c.clipPath(clip)
            c.drawColor(Color.rgb(34, 42, 62))
            val srcRatio = bitmap.width.toFloat() / bitmap.height
            val dstRatio = cellW / cellH
            val src = if (srcRatio > dstRatio) {
                val crop = (bitmap.width - bitmap.height * dstRatio) / 2
                Rect(crop.toInt(), 0, (bitmap.width-crop).toInt(), bitmap.height)
            } else {
                val crop = (bitmap.height - bitmap.width / dstRatio) / 2
                Rect(0, crop.toInt(), bitmap.width, (bitmap.height-crop).toInt())
            }
            c.drawBitmap(bitmap, src, dst, p)
            c.restore()
        }
        p.color = Color.WHITE; p.textSize = 30f
        c.drawText("VECTOR DESIGN COLLECTION", w/2, h-95f, p)
        p.textSize = 26f; p.typeface = Typeface.create("sans-serif", Typeface.NORMAL)
        if (username.isNotBlank()) c.drawText(username.take(40), w/2, h-48f, p)
    }

    private fun saveVideo(file: File): Uri {
        val values = ContentValues().apply {
            put(MediaStore.Video.Media.DISPLAY_NAME, "StockMotion-Showcase-${System.currentTimeMillis()}.mp4")
            put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
            put(MediaStore.Video.Media.RELATIVE_PATH, "Movies/StockMotion")
            put(MediaStore.Video.Media.IS_PENDING, 1)
        }
        val uri = contentResolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values)
            ?: error("Tidak dapat membuat file video.")
        try {
            file.inputStream().use { input -> contentResolver.openOutputStream(uri).use { out ->
                requireNotNull(out); input.copyTo(out)
            } }
            values.clear(); values.put(MediaStore.Video.Media.IS_PENDING, 0)
            contentResolver.update(uri, values, null, null)
            return uri
        } catch (e: Exception) { contentResolver.delete(uri, null, null); throw e }
    }
}
