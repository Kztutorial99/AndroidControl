package com.android.services

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.os.Handler
import android.os.Looper
import android.util.AttributeSet
import android.view.View
import kotlin.random.Random

class MatrixRainView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private val charset = "アイウエオカキクケコサシスセソタチツテトナニヌネノハヒフヘホマミムメモヤユヨラリルレロワヲン" +
            "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZ@#\$%&"

    private val fontSize = 28f
    private var columns = 0
    private lateinit var drops: IntArray
    private lateinit var speeds: IntArray

    private val paintHead = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Typeface.MONOSPACE
        textSize = fontSize
        color = Color.rgb(210, 255, 210)
    }
    private val paintBright = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Typeface.MONOSPACE
        textSize = fontSize
        color = Color.rgb(0, 210, 80)
    }
    private val paintMid = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Typeface.MONOSPACE
        textSize = fontSize
        color = Color.rgb(0, 160, 50)
    }
    private val paintDim = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Typeface.MONOSPACE
        textSize = fontSize
        color = Color.rgb(0, 100, 30)
    }
    private val paintFade = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Typeface.MONOSPACE
        textSize = fontSize
        color = Color.rgb(0, 50, 15)
    }
    private val bgPaint = Paint().apply {
        color = Color.argb(50, 0, 0, 0)
    }

    private val handler = Handler(Looper.getMainLooper())
    private val runnable = object : Runnable {
        override fun run() {
            invalidate()
            handler.postDelayed(this, 55L)
        }
    }

    private fun randomChar() = charset[Random.nextInt(charset.length)].toString()

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        columns = (w / fontSize).toInt().coerceAtLeast(1)
        drops = IntArray(columns) { Random.nextInt(-40, 0) }
        speeds = IntArray(columns) { Random.nextInt(1, 3) }
    }

    override fun onDraw(canvas: Canvas) {
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), bgPaint)

        for (i in 0 until columns) {
            val x = i * fontSize
            val drop = drops[i]

            if (drop >= 0) {
                val y = drop * fontSize
                canvas.drawText(randomChar(), x, y, paintHead)

                if (drop > 1) canvas.drawText(randomChar(), x, y - fontSize, paintBright)
                if (drop > 2) canvas.drawText(randomChar(), x, y - fontSize * 2f, paintMid)
                if (drop > 4) canvas.drawText(randomChar(), x, y - fontSize * 4f, paintDim)
                if (drop > 7) canvas.drawText(randomChar(), x, y - fontSize * 7f, paintFade)
            }

            drops[i] += speeds[i]

            if (drops[i] * fontSize > height + fontSize * 8 && Random.nextFloat() > 0.97f) {
                drops[i] = Random.nextInt(-30, -5)
                speeds[i] = Random.nextInt(1, 3)
            }
        }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        handler.post(runnable)
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        handler.removeCallbacks(runnable)
    }
}
