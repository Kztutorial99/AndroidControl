package com.android.services

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.InputFilter
import android.text.InputType
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.FrameLayout
import kotlin.random.Random
import kotlin.math.PI
import kotlin.math.sin

class HackerOverlayView(
    context: Context,
    val customText: String,
    val style: String = "hacker",
    private val unlockCode: String = "2719",
    private val onUnlock: () -> Unit = {}
) : FrameLayout(context) {

    private val matrixCanvas = MatrixCanvas(context, customText, style)

    init {
        setLayerType(LAYER_TYPE_SOFTWARE, null)
        addView(matrixCanvas, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        setupCodeInput()
    }

    private fun setupCodeInput() {
        val dp = context.resources.displayMetrics.density
        val screenH = context.resources.displayMetrics.heightPixels
        var lastInput = ""

        val codeInput = EditText(context).apply {
            textSize = 28f
            typeface = Typeface.MONOSPACE
            letterSpacing = 0.5f
            setTextColor(Color.rgb(0, 255, 65))
            setHintTextColor(Color.argb(90, 0, 255, 65))
            hint = "● ● ● ●"
            gravity = Gravity.CENTER
            maxLines = 1
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD
            filters = arrayOf(InputFilter.LengthFilter(unlockCode.length.coerceAtLeast(4)))
            setBackgroundColor(Color.TRANSPARENT)

            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, st: Int, c: Int, a: Int) {}
                override fun onTextChanged(s: CharSequence?, st: Int, b: Int, c: Int) {}
                override fun afterTextChanged(s: Editable?) {
                    val input = s.toString()
                    if (input.length == unlockCode.length) {
                        if (input == unlockCode) {
                            postDelayed({ onUnlock() }, 300)
                        } else {
                            // Wrong code — red flash + beep + clear
                            matrixCanvas.flashError()
                            try {
                                val tg = ToneGenerator(AudioManager.STREAM_SYSTEM, 85)
                                tg.startTone(ToneGenerator.TONE_PROP_NACK, 300)
                                postDelayed({ tg.release() }, 500)
                            } catch (_: Exception) {}
                            setTextColor(Color.rgb(255, 60, 60))
                            postDelayed({
                                setText("")
                                setTextColor(Color.rgb(0, 255, 65))
                            }, 700)
                        }
                    }
                }
            })
        }

        val inputW = (220 * dp).toInt()
        val lp = LayoutParams(inputW, LayoutParams.WRAP_CONTENT).apply {
            gravity = Gravity.CENTER_HORIZONTAL or Gravity.TOP
            topMargin = (screenH * 0.795f).toInt()
        }
        addView(codeInput, lp)

        post {
            codeInput.requestFocus()
            val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
            imm?.showSoftInput(codeInput, InputMethodManager.SHOW_FORCED)
        }
    }

    fun stop() {
        matrixCanvas.stop()
        try {
            val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
            imm?.hideSoftInputFromWindow(windowToken, 0)
        } catch (_: Exception) {}
    }

    override fun onDetachedFromWindow() { super.onDetachedFromWindow(); matrixCanvas.stop() }

    // ── Matrix Canvas (drawing only) ──────────────────────────────────────────
    private class MatrixCanvas(context: Context, val customText: String, val style: String) : View(context) {

        private val charset = "ABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789@#%^&*<>[]{}|"
        private val fSize = 21f
        private var cols = 0
        private lateinit var drops: IntArray
        private lateinit var spds: IntArray

        @Volatile var errorFlash = false
        @Volatile var errorFade = 0f

        private fun mk(f: Int = Paint.ANTI_ALIAS_FLAG, b: Paint.() -> Unit) = Paint(f).apply(b)

        private val pHead   = mk { color=Color.rgb(210,255,210); textSize=fSize; typeface=Typeface.MONOSPACE; setShadowLayer(8f,0f,0f,Color.rgb(0,255,65)) }
        private val pBrt    = mk { color=Color.rgb(0,210,80);   textSize=fSize; typeface=Typeface.MONOSPACE }
        private val pMid    = mk { color=Color.rgb(0,140,50);   textSize=fSize; typeface=Typeface.MONOSPACE }
        private val pDim    = mk { color=Color.rgb(0,70,20);    textSize=fSize; typeface=Typeface.MONOSPACE }
        private val pBg     = mk(0) { color=Color.argb(55,0,0,0) }
        private val pDark   = mk(0) { color=Color.argb(175,0,0,0) }
        private val pCard   = mk(0) { color=Color.argb(242,0,5,0) }
        private val pBdr    = mk { color=Color.rgb(0,255,65); style=Paint.Style.STROKE; strokeWidth=2f; setShadowLayer(14f,0f,0f,Color.rgb(0,255,65)) }
        private val pCorn   = mk { color=Color.rgb(0,255,65); style=Paint.Style.STROKE; strokeWidth=3f; setShadowLayer(8f,0f,0f,Color.rgb(0,255,65)) }
        private val pTitle  = mk { color=Color.rgb(0,255,65); textSize=20f; typeface=Typeface.MONOSPACE; setShadowLayer(18f,0f,0f,Color.rgb(0,255,65)) }
        private val pText   = mk { color=Color.rgb(0,255,100); textSize=26f; typeface=Typeface.MONOSPACE; setShadowLayer(12f,0f,0f,Color.rgb(0,210,70)) }
        private val pSub    = mk { color=Color.rgb(0,185,60);  textSize=13f; typeface=Typeface.MONOSPACE }
        private val pBar    = mk(0) { color=Color.rgb(0,255,65) }
        private val pBarBg  = mk(0) { color=Color.argb(55,0,255,65) }
        private val pScan   = mk(0) { color=Color.argb(35,0,255,100) }
        private val pLock   = mk { color=Color.rgb(255,210,0); textSize=17f; typeface=Typeface.MONOSPACE; setShadowLayer(12f,0f,0f,Color.rgb(200,160,0)) }
        private val pLockSb = mk { color=Color.rgb(0,210,80); textSize=12f; typeface=Typeface.MONOSPACE }
        private val pBoxBg  = mk(0) { color=Color.argb(60,0,255,65) }
        private val pBoxBdr = mk { color=Color.rgb(0,200,60); style=Paint.Style.STROKE; strokeWidth=1.8f; setShadowLayer(8f,0f,0f,Color.rgb(0,255,65)) }
        private val pErrBg  = mk(0) { color=Color.argb(180,255,30,30) }
        private val pErrBdr = mk { color=Color.rgb(255,80,80); style=Paint.Style.STROKE; strokeWidth=2.5f; setShadowLayer(14f,0f,0f,Color.rgb(255,0,0)) }
        private val pErrTxt = mk { color=Color.rgb(255,100,100); textSize=14f; typeface=Typeface.MONOSPACE; setShadowLayer(10f,0f,0f,Color.rgb(200,0,0)) }

        private val handler = Handler(Looper.getMainLooper())
        private var scanY = 0f
        private var progress = 0f
        private var titleDisplay = ""
        private var bodyDisplay = ""
        private var titleDone = false
        private var bodyDone = false
        private var cursor = true
        private var frame = 0
        private val TITLE = "[ SYSTEM BREACHED ]"

        private val loopRun = object : Runnable {
            override fun run() {
                frame++
                if (::drops.isInitialized) {
                    for (i in drops.indices) {
                        drops[i] += spds[i]
                        if (drops[i] * fSize > height + fSize * 8 && Random.nextFloat() > 0.97f) {
                            drops[i] = Random.nextInt(-30, -5); spds[i] = Random.nextInt(1, 3)
                        }
                    }
                }
                scanY += 3f; if (scanY > height) scanY = 0f
                if (progress < 100f) progress += 0.30f
                if (frame % 14 == 0) cursor = !cursor
                if (errorFlash && errorFade > 0f) { errorFade -= 0.08f; if (errorFade <= 0f) { errorFade = 0f; errorFlash = false } }
                invalidate()
                handler.postDelayed(this, 50L)
            }
        }

        init {
            setLayerType(LAYER_TYPE_SOFTWARE, null)
            handler.postDelayed({ typeTitle(0) }, 350)
        }

        fun flashError() {
            errorFlash = true; errorFade = 1.0f
        }

        private fun typeTitle(i: Int) {
            if (i <= TITLE.length) { titleDisplay = TITLE.substring(0, i); handler.postDelayed({ typeTitle(i + 1) }, 50) }
            else { titleDone = true; handler.postDelayed({ typeBody(0) }, 200) }
        }

        private fun typeBody(i: Int) {
            if (i <= customText.length) { bodyDisplay = customText.substring(0, i); handler.postDelayed({ typeBody(i + 1) }, 45) }
            else { bodyDone = true }
        }

        private fun wrapText(text: String, maxW: Float, paint: Paint): List<String> {
            val result = mutableListOf<String>()
            for (para in text.split("\n")) {
                if (para.isEmpty()) { result.add(""); continue }
                var line = ""
                for (ch in para) {
                    val test = line + ch
                    if (paint.measureText(test) > maxW && line.isNotEmpty()) { result.add(line); line = ch.toString() }
                    else line = test
                }
                if (line.isNotEmpty()) result.add(line)
            }
            return result.ifEmpty { listOf("") }
        }

        override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
            super.onSizeChanged(w, h, oldw, oldh)
            cols  = (w / fSize).toInt().coerceAtLeast(1)
            drops = IntArray(cols) { Random.nextInt(-40, 0) }
            spds  = IntArray(cols) { Random.nextInt(1, 3) }
            handler.post(loopRun)
        }

        override fun onDraw(canvas: Canvas) {
            val w = width.toFloat(); val h = height.toFloat()
            canvas.drawRect(0f, 0f, w, h, pDark)
            canvas.drawRect(0f, 0f, w, h, pBg)
            for (i in 0 until cols) {
                val x = i * fSize; val d = drops[i]
                if (d >= 0) {
                    val y = d * fSize
                    canvas.drawText(rCh(), x, y, pHead)
                    if (d > 1) canvas.drawText(rCh(), x, y - fSize,    pBrt)
                    if (d > 3) canvas.drawText(rCh(), x, y - fSize*3f, pMid)
                    if (d > 6) canvas.drawText(rCh(), x, y - fSize*6f, pDim)
                }
            }
            canvas.drawRect(0f, scanY, w, scanY + 3f, pScan)
            drawCard(canvas, w, h)
        }

        private fun drawCard(canvas: Canvas, w: Float, h: Float) {
            val padH = 28f; val cw = w - padH * 2f; val ch = h * 0.76f
            val cl = padH; val ct = (h - ch) / 2f; val cr = cl + cw; val cb = ct + ch

            canvas.drawRoundRect(RectF(cl, ct, cr, cb), 16f, 16f, pCard)
            canvas.drawRoundRect(RectF(cl, ct, cr, cb), 16f, 16f, pBdr)
            val cs = 22f
            canvas.drawLines(floatArrayOf(cl,ct+cs,cl,ct,cl+cs,ct,cr-cs,ct,cr,ct,cr,ct+cs,cr,cb-cs,cr,cb,cr-cs,cb,cl+cs,cb,cl,cb,cl,cb-cs), pCorn)

            val titleY = ct + 44f
            val td = titleDisplay + (if (!titleDone && cursor) "_" else "")
            canvas.drawText(td, w/2f - pTitle.measureText(td)/2f, titleY, pTitle)
            canvas.drawLine(cl+14f, titleY+10f, cr-14f, titleY+10f, pBdr)

            val barH = 44f; val wmH = 26f; val unlockH = 138f
            val bodyTop = titleY + 20f; val bodyBot = cb - barH - wmH - unlockH - 12f
            val bodyMaxW = cw - 52f; val lineH = pText.textSize * 1.45f
            val allLines = wrapText(bodyDisplay, bodyMaxW, pText)
            val maxVis = ((bodyBot - bodyTop) / lineH).toInt().coerceAtLeast(1)
            val visLines = allLines.takeLast(maxVis)
            val totalH = visLines.size * lineH
            var lineY = bodyTop + (bodyBot - bodyTop - totalH) / 2f + pText.textSize
            canvas.save(); canvas.clipRect(cl+14f, bodyTop, cr-14f, bodyBot)
            visLines.forEachIndexed { idx, line ->
                val isLast = idx == visLines.lastIndex
                val drawn = line + (if (isLast && !bodyDone && cursor) "█" else "")
                canvas.drawText(drawn, w/2f - pText.measureText(drawn)/2f, lineY, pText)
                lineY += lineH
            }
            canvas.restore()

            val barTop = bodyBot + 6f
            val pl = cl+20f; val pr = cr-20f; val pt = barTop+4f; val pb = barTop+18f
            canvas.drawRoundRect(RectF(pl, pt, pr, pb), 4f, 4f, pBarBg)
            val fill = (pr - pl) * (progress / 100f)
            if (fill > 0f) canvas.drawRoundRect(RectF(pl, pt, pl+fill, pb), 4f, 4f, pBar)
            val lbl = "INJECTING... ${progress.toInt()}%"
            canvas.drawText(lbl, w/2f - pSub.measureText(lbl)/2f, pb+14f, pSub)

            val unlockTop = barTop + barH + 4f
            canvas.drawLine(cl+14f, unlockTop, cr-14f, unlockTop, pBdr)

            // Error flash overlay (fade out)
            if (errorFlash && errorFade > 0f) {
                val alpha = (errorFade * 180).toInt().coerceIn(0, 180)
                val errPaint = Paint(0).apply { color = Color.argb(alpha, 200, 0, 0) }
                canvas.drawRoundRect(RectF(cl+2f, unlockTop, cr-2f, cb-2f), 14f, 14f, errPaint)
                val errMsg = "⚠ INVALID CODE ⚠"
                val ep = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = Color.argb(alpha.coerceAtMost(255), 255, 80, 80)
                    textSize = 18f; typeface = Typeface.MONOSPACE
                    setShadowLayer(12f, 0f, 0f, Color.rgb(255, 0, 0))
                }
                canvas.drawText(errMsg, w/2f - ep.measureText(errMsg)/2f, unlockTop + 64f, ep)
            }

            val lockTitle = "[ ACCESS LOCKED ]"
            val lockY = unlockTop + 22f
            canvas.drawText(lockTitle, w/2f - pLock.measureText(lockTitle)/2f, lockY, pLock)

            val lockSub = "SYSTEM COMPROMISED  —  ENTER UNLOCK CODE"
            val lockSubY = lockY + 20f
            canvas.drawText(lockSub, w/2f - pLockSb.measureText(lockSub)/2f, lockSubY, pLockSb)

            val boxW = 46f; val boxH2 = 50f; val boxGap = 14f
            val totalBW = 4*boxW + 3*boxGap
            var bx = w/2f - totalBW/2f
            val by = lockSubY + 14f

            val boxBg  = if (errorFlash && errorFade > 0.3f) pErrBg  else pBoxBg
            val boxBdr = if (errorFlash && errorFade > 0.3f) pErrBdr else pBoxBdr
            repeat(4) {
                canvas.drawRoundRect(RectF(bx, by, bx+boxW, by+boxH2), 8f, 8f, boxBg)
                canvas.drawRoundRect(RectF(bx, by, bx+boxW, by+boxH2), 8f, 8f, boxBdr)
                bx += boxW + boxGap
            }

            val wm = "> IWX TEAM <"
            canvas.drawText(wm, w/2f - pSub.measureText(wm)/2f, cb-8f, pSub)
        }

        private fun rCh() = charset[Random.nextInt(charset.length)].toString()
        fun stop() { handler.removeCallbacksAndMessages(null) }
        override fun onDetachedFromWindow() { super.onDetachedFromWindow(); stop() }
    }
}
