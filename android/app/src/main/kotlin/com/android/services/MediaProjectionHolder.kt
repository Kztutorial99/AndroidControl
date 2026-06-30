package com.android.services

import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.Image
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.util.Base64
import android.util.DisplayMetrics
import java.io.ByteArrayOutputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Singleton holder untuk MediaProjection + VirtualDisplay + ImageReader.
 *
 * Flow:
 * 1. MainActivity.kt requests permission via MediaProjectionManager.createScreenCaptureIntent()
 * 2. onActivityResult passes resultCode + data ke setup()
 * 3. ConnectorService memanggil captureFrameBase64() untuk ambil frame
 *
 * Kenapa lebih baik dari screencap -p:
 * - Tidak ada shell process spawn → ~16–33ms/frame vs 200–500ms
 * - Hardware-accelerated melalui VirtualDisplay
 * - Tidak butuh Shizuku / root
 */
object MediaProjectionHolder {

    @Volatile private var projection: MediaProjection? = null
    @Volatile private var virtualDisplay: VirtualDisplay? = null
    @Volatile private var imageReader: ImageReader? = null
    @Volatile private var screenWidth = 0
    @Volatile private var screenHeight = 0
    @Volatile private var screenDpi = 0
    @Volatile private var isReady = false

    fun isAvailable(): Boolean = isReady && projection != null && imageReader != null

    /**
     * Dipanggil dari MainActivity setelah user grant MediaProjection permission.
     * Gunakan DisplayMetrics dari Activity/Context yang valid — jangan dari Service WindowManager.
     * context harus Application context (bukan Activity) agar tidak leak.
     */
    fun setup(
        appContext: android.content.Context,
        mediaProjection: MediaProjection,
        metrics: DisplayMetrics
    ) {
        release()

        screenWidth  = metrics.widthPixels
        screenHeight = metrics.heightPixels
        screenDpi    = metrics.densityDpi

        val reader = ImageReader.newInstance(screenWidth, screenHeight, PixelFormat.RGBA_8888, 2)
        imageReader = reader

        val vd = mediaProjection.createVirtualDisplay(
            "IWX-Capture",
            screenWidth, screenHeight, screenDpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            reader.surface, null, null
        )
        virtualDisplay = vd
        projection = mediaProjection
        isReady = true

        android.util.Log.d("MediaProjHolder", "✅ MediaProjection ready ${screenWidth}x${screenHeight} @${screenDpi}dpi")
    }

    /**
     * Capture satu frame, encode ke JPEG base64.
     * Memblokir thread max [timeoutMs] ms. Return null jika gagal.
     */
    fun captureFrameBase64(maxWidth: Int = 720, quality: Int = 70, timeoutMs: Long = 500): String? {
        val reader = imageReader ?: return null
        if (!isReady) return null

        return try {
            val latch = CountDownLatch(1)
            var img: Image? = null

            val listener = ImageReader.OnImageAvailableListener { r ->
                img = r.acquireLatestImage()
                latch.countDown()
            }
            reader.setOnImageAvailableListener(listener, null)

            if (!latch.await(timeoutMs, TimeUnit.MILLISECONDS)) {
                reader.setOnImageAvailableListener(null, null)
                return null
            }
            reader.setOnImageAvailableListener(null, null)

            val image = img ?: return null
            val result = imageToJpegBase64(image, maxWidth, quality)
            image.close()
            result
        } catch (e: Exception) {
            android.util.Log.w("MediaProjHolder", "captureFrame error: ${e.message}")
            null
        }
    }

    private fun imageToJpegBase64(image: Image, maxWidth: Int, quality: Int): String? {
        return try {
            val plane = image.planes[0]
            val buf = plane.buffer
            val pixelStride = plane.pixelStride
            val rowStride = plane.rowStride
            val rowPadding = rowStride - pixelStride * image.width

            var bmp = Bitmap.createBitmap(
                image.width + rowPadding / pixelStride,
                image.height,
                Bitmap.Config.ARGB_8888
            )
            bmp.copyPixelsFromBuffer(buf)

            if (image.width + rowPadding / pixelStride > image.width) {
                bmp = Bitmap.createBitmap(bmp, 0, 0, image.width, image.height)
            }

            val scaled = if (maxWidth > 0 && bmp.width > maxWidth) {
                val ratio = maxWidth.toFloat() / bmp.width
                val scaledH = (bmp.height * ratio).toInt()
                val s = Bitmap.createScaledBitmap(bmp, maxWidth, scaledH, true)
                if (s !== bmp) bmp.recycle()
                s
            } else bmp

            val baos = ByteArrayOutputStream()
            scaled.compress(Bitmap.CompressFormat.JPEG, quality, baos)
            if (scaled !== bmp) scaled.recycle()
            bmp.recycle()

            Base64.encodeToString(baos.toByteArray(), Base64.NO_WRAP)
        } catch (e: Exception) {
            android.util.Log.w("MediaProjHolder", "imageToJpeg error: ${e.message}")
            null
        }
    }

    fun release() {
        isReady = false
        try { imageReader?.close() } catch (_: Exception) {}
        try { virtualDisplay?.release() } catch (_: Exception) {}
        try { projection?.stop() } catch (_: Exception) {}
        imageReader = null
        virtualDisplay = null
        projection = null
        android.util.Log.d("MediaProjHolder", "MediaProjection released")
    }
}
