package com.openreplay.managers

import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import NetworkManager
import android.app.Activity
import android.content.Context
import android.graphics.*
import android.view.View
import com.openreplay.OpenReplay
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.util.*
import java.util.zip.GZIPOutputStream
import kotlin.concurrent.fixedRateTimer

object ScreenshotManager {
    private var timer: Timer? = null
    private var screenshots: MutableList<Pair<ByteArray, Long>> = mutableListOf()
    private var screenshotsBackup: MutableList<Pair<ByteArray, Long>> = mutableListOf()
    private var tick: Long = 0
    private var lastTs: Long = 0
    private var firstTs: Long = 0
    private var bufferTimer: Timer? = null
    private lateinit var appContext: Context
    private var sanitizedElements: MutableList<View> = mutableListOf()

    fun setSettings(settings: Pair<Double, Double>) {
        // Set up the screenshot manager
    }

    fun start(context: Context, startTs: Long) {
        this.appContext = context
//        firstTs = startTs
        startCapturing()
//        startCycleBuffer()
    }


    private fun startCapturing(intervalMillis: Long = 1000) {
        stopCapturing()
        timer = fixedRateTimer("screenshotTimer", false, 0L, intervalMillis) {
            captureScreenshot()
        }
    }

    private fun stopCapturing() {
        timer?.cancel()
        timer = null
    }

    private fun captureScreenshot() {
        val view = (this.appContext as Activity).window.decorView.rootView

        (this.appContext as Activity).runOnUiThread {
            val bitmap = viewToBitmap(view, view.width, view.height)
            compressAndSend(bitmap)
        }
    }

    private val maskPaint = Paint().apply {
        style = Paint.Style.FILL
        val patternBitmap = createCrossStripedPatternBitmap()
        shader = BitmapShader(patternBitmap, Shader.TileMode.REPEAT, Shader.TileMode.REPEAT)
    }

    private fun viewToBitmap(view: View, width: Int, height: Int): Bitmap {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        view.draw(canvas)


        // Draw masks over sanitized elements
        sanitizedElements.forEach { sanitizedView ->
            if (sanitizedView.visibility == View.VISIBLE) {
                val location = IntArray(2)
                sanitizedView.getLocationInWindow(location)
                val rootViewLocation = IntArray(2)
                view.getLocationInWindow(rootViewLocation)
                val x = location[0] - rootViewLocation[0]
                val y = location[1] - rootViewLocation[1]

                // Draw the striped mask over the sanitized view
                canvas.save()
                canvas.translate(x.toFloat(), y.toFloat())
                canvas.drawRect(0f, 0f, sanitizedView.width.toFloat(), sanitizedView.height.toFloat(), maskPaint)
                canvas.restore()
            }
        }
        return bitmap
    }

    private fun createCrossStripedPatternBitmap(): Bitmap {
        val patternSize = 80 // This is the size of the square pattern
        val patternBitmap = Bitmap.createBitmap(patternSize, patternSize, Bitmap.Config.ARGB_8888)
        val patternCanvas = Canvas(patternBitmap)
        val paint = Paint().apply {
            color = Color.DKGRAY // Color of the stripes
            style = Paint.Style.FILL
        }

        patternCanvas.drawColor(Color.WHITE)

        val stripeWidth = 20f // Width of the stripes
        val gap = stripeWidth / 4 // Gap between stripes
        for (i in -patternSize until patternSize * 2 step (stripeWidth + gap).toInt()) {
            patternCanvas.drawLine(i.toFloat(), -gap, i.toFloat() + patternSize, patternSize.toFloat() + gap, paint)
        }

        patternCanvas.rotate(90f, patternSize / 2f, patternSize / 2f)

        for (i in -patternSize until patternSize * 2 step (stripeWidth + gap).toInt()) {
            patternCanvas.drawLine(i.toFloat(), -gap, i.toFloat() + patternSize, patternSize.toFloat() + gap, paint)
        }

        return patternBitmap
    }

    private fun gzipCompress(data: ByteArray): ByteArray {
        ByteArrayOutputStream().use { byteArrayOutputStream ->
            GZIPOutputStream(byteArrayOutputStream).use { gzipOutputStream ->
                gzipOutputStream.write(data)
            }
            return byteArrayOutputStream.toByteArray()
        }
    }

    private fun compressAndSend(bitmap: Bitmap) = GlobalScope.launch {
        ByteArrayOutputStream().use { outputStream ->
            bitmap.compress(Bitmap.CompressFormat.JPEG, 50, outputStream)
            val screenshotData = outputStream.toByteArray()
            val gzippedData = gzipCompress(screenshotData)

            saveToLocalFilesystem(appContext, screenshotData, "screenshot-${System.currentTimeMillis()}.jpg")
            screenshots.add(Pair(gzippedData, System.currentTimeMillis()))
            sendScreenshots()
        }
    }

    private fun saveToLocalFilesystem(context: Context, imageData: ByteArray, filename: String) {
        val file = File(context.filesDir, filename)
        FileOutputStream(file).use { out ->
            out.write(imageData)
        }
    }

    suspend fun syncBuffers() {
        val buf1 = screenshots.size
        val buf2 = screenshotsBackup.size
        tick = 0

        if (buf1 > buf2) {
            screenshotsBackup.clear()
        } else {
            screenshots = ArrayList(screenshotsBackup)
            screenshotsBackup.clear()
        }

        sendScreenshots()
    }

    private suspend fun sendScreenshots() {
        val sessionId = NetworkManager.sessionId ?: return
        val archiveName = "$sessionId-$lastTs.tar.gz"
        val combinedData = ByteArrayOutputStream()

        // Compress images into a single GZIP file (simplified)
        withContext(Dispatchers.IO) {
            GZIPOutputStream(combinedData).use { gzipOutputStream ->
                screenshots.forEach { (imageData, _) ->
                    gzipOutputStream.write(imageData)
                }
            }
        }

        val gzData = combinedData.toByteArray()
        withContext(Dispatchers.IO) {
            try {
                MessageCollector.sendImagesBatch(gzData, archiveName)
                screenshots.clear()
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    fun startCycleBuffer() {
        bufferTimer = fixedRateTimer("cycleBuffer", false, 0L, 30_000) {
            cycleBuffer()
        }
    }

    fun stopCycleBuffer() {
        bufferTimer?.cancel()
        bufferTimer = null
    }

    private fun cycleBuffer() {
        if (OpenReplay.options.bufferingMode) {
            if ((tick % 2).toInt() == 0) {
                screenshots.clear()
            } else {
                screenshotsBackup.clear()
            }
            tick++
        }
        // Note: This code runs on a background thread, ensure any UI updates are posted to the main thread
    }

    fun addSanitizedElement(view: View) {
        if (OpenReplay.options.debugLogs) {
            DebugUtils.log("Sanitizing view: $view")
        }
        sanitizedElements.add(view)
    }

    fun removeSanitizedElement(view: View) {
        if (OpenReplay.options.debugLogs) {
            DebugUtils.log("Removing sanitized view: $view")
        }
        sanitizedElements.remove(view)
    }
}