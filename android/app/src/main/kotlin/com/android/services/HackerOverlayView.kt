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

/**
 * Hacker overlay — clean, hardware-accelerated, keyboard-aware.
 *
 * Performance fixes vs previous version:
 *  • LAYER_TYPE_HARDWARE everywhere (no software rendering)
 *  • Zero setShadowLayer calls (shadows force software rendering)
 *  • No matrix rain (was heaviest CPU consumer)
 *  • Single animation loop, 80 ms interval, guarded against double-start
 *  • loopRun guard flag prevents duplicate loops on onSizeChanged
 */
class HackerOverlayView(
    context: Context,
    val customText: String,
    val style: String = "hacker",
    private val unlockCode: String = "2719",
    private val speed: Float = 0.60f,
    private val onUnlock: () -> Unit = {}
) : FrameLayout(context) {

    private val card = HackerCardView(context, customText, speed)
    private var codeInput: EditText? = null

    init {
        // Hardware layer on the container — children inherit hardware acceleration
        setLayerType(LAYER_TYPE_HARDWARE, null)
        addView(card, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        setupInput()
    }

    // ── Same formula as HackerCardView.drawCard() ─────────────────────────────
    private fun boxCenterY(h: Float): Float {
        val dp = context.resources.displayMetrics.density
        val pad = 14f * dp
        val cb  = h - pad
        // unlock section is always the last 158dp of the card
        val unlockTop  = cb - 158f * dp
        val lockLabelY = unlockTop + 24f * dp
        val lockSubY   = lockLabelY + 20f * dp
        val boxTop     = lockSubY + 14f * dp
        return boxTop + 24f * dp          // 24dp = half of 48dp box height
    }

    private fun setupInput() {
        val dp = context.resources.displayMetrics.density

        val et = EditText(context).apply {
            textSize     = 22f
            typeface     = Typeface.MONOSPACE
            letterSpacing = 0.5f
            setTextColor(Color.rgb(0, 255, 65))
            setHintTextColor(Color.argb(90, 0, 255, 65))
            hint         = "● ● ● ●"
            gravity      = Gravity.CENTER
            maxLines     = 1
            inputType    = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD
            filters      = arrayOf(InputFilter.LengthFilter(unlockCode.length.coerceAtLeast(4)))
            setBackgroundColor(Color.TRANSPARENT)

            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, st: Int, c: Int, a: Int) {}
                override fun onTextChanged(s: CharSequence?, st: Int, b: Int, c: Int) {}
                override fun afterTextChanged(s: Editable?) {
                    val input = s.toString()
                    if (input.length == unlockCode.length) {
                        if (input == unlockCode) {
                            postDelayed({ onUnlock() }, 200)
                        } else {
                            card.flashError()
                            try {
                                val tg = ToneGenerator(AudioManager.STREAM_SYSTEM, 80)
                                tg.startTone(ToneGenerator.TONE_PROP_NACK, 280)
                                postDelayed({ tg.release() }, 480)
                            } catch (_: Exception) {}
                            setTextColor(Color.rgb(255, 60, 60))
                            postDelayed({
                                setText("")
                                setTextColor(Color.rgb(0, 255, 65))
                            }, 600)
                        }
                    }
                }
            })
        }

        val inputW = (196f * dp).toInt()
        val screenH = context.resources.displayMetrics.heightPixels.toFloat()
        val lp = LayoutParams(inputW, LayoutParams.WRAP_CONTENT).apply {
            gravity   = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            topMargin = (boxCenterY(screenH) - 20f * dp).toInt()
        }
        addView(et, lp)
        codeInput = et

        post {
            et.requestFocus()
            (context.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager)
                ?.showSoftInput(et, InputMethodManager.SHOW_FORCED)
        }
    }

    // Called when SOFT_INPUT_ADJUST_RESIZE shrinks the view height (keyboard appeared).
    // Reposition EditText so it stays aligned with the drawn code boxes.
    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (h <= 0 || h == oldh) return
        val dp = context.resources.displayMetrics.density
        codeInput?.let { et ->
            (et.layoutParams as? LayoutParams)?.let { lp ->
                lp.topMargin = (boxCenterY(h.toFloat()) - 20f * dp).toInt()
                et.layoutParams = lp
            }
        }
    }

    fun stop() {
        card.stop()
        try {
            (context.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager)
                ?.hideSoftInputFromWindow(windowToken, 0)
        } catch (_: Exception) {}
    }

    override fun onDetachedFromWindow() { super.onDetachedFromWindow(); card.stop() }

    // ─────────────────────────────────────────────────────────────────────────
    // HackerCardView  — hardware-rendered canvas, zero shadows, clean design
    // ─────────────────────────────────────────────────────────────────────────
    private class HackerCardView(
        context: Context,
        private val customText: String,
        private val speed: Float
    ) : View(context) {

        private val dp   = context.resources.displayMetrics.density
        private val sp   = context.resources.displayMetrics.scaledDensity

        // Typewriter interval synced with TTS speed
        private val typeMs: Long = (55L / speed.coerceIn(0.1f, 2.0f)).toLong().coerceIn(20L, 140L)

        // ── Paints — NO setShadowLayer anywhere ───────────────────────────────
        private fun p(flags: Int = Paint.ANTI_ALIAS_FLAG, b: Paint.() -> Unit) = Paint(flags).apply(b)

        private val pBg     = p(0)  { color = Color.rgb(2, 5, 2) }
        private val pScan   = p(0)  { color = Color.argb(18, 0, 255, 65) }
        private val pCard   = p(0)  { color = Color.argb(248, 4, 10, 4) }
        private val pBorder = p     { color = Color.rgb(0, 180, 45); style = Paint.Style.STROKE; strokeWidth = 1.8f }
        private val pCorner = p     { color = Color.rgb(0, 255, 65); style = Paint.Style.STROKE; strokeWidth = 2.8f }
        private val pDivide = p     { color = Color.argb(100, 0, 200, 50); style = Paint.Style.STROKE; strokeWidth = 1f }
        // Text paints — no shadow, use alpha contrast for depth
        private val pTitle  = p     { color = Color.rgb(0, 255, 65); textSize = 17f * sp; typeface = Typeface.MONOSPACE }
        private val pBody   = p     { color = Color.rgb(0, 230, 80); textSize = 14f * sp; typeface = Typeface.MONOSPACE }
        private val pSub    = p     { color = Color.argb(180, 0, 190, 55); textSize = 10f * sp; typeface = Typeface.MONOSPACE }
        private val pBar    = p(0)  { color = Color.rgb(0, 210, 55) }
        private val pBarBg  = p(0)  { color = Color.argb(45, 0, 255, 65) }
        private val pLock   = p     { color = Color.rgb(255, 195, 0); textSize = 14f * sp; typeface = Typeface.MONOSPACE }
        private val pLockS  = p     { color = Color.argb(200, 0, 200, 65); textSize = 10f * sp; typeface = Typeface.MONOSPACE }
        private val pBoxBg  = p(0)  { color = Color.argb(55, 0, 255, 65) }
        private val pBoxBd  = p     { color = Color.rgb(0, 180, 50); style = Paint.Style.STROKE; strokeWidth = 1.5f }
        private val pErrBg  = p(0)  { color = Color.argb(170, 200, 20, 20) }
        private val pErrBd  = p     { color = Color.rgb(255, 75, 75); style = Paint.Style.STROKE; strokeWidth = 2f }
        private val pErrTxt = p     { color = Color.rgb(255, 90, 90); textSize = 14f * sp; typeface = Typeface.MONOSPACE }
        // Subtle scan-line accent — drawn as a thin rect sweeping down
        private val pGlow   = p(0)  { color = Color.argb(8, 0, 255, 65) }

        private val handler = Handler(Looper.getMainLooper())
        private var loopStarted = false

        private var scanY    = 0f
        private var progress = 0f
        private var titleTxt = ""
        private var bodyTxt  = ""
        private var titleDone = false
        private var bodyDone  = false
        private var cursor   = true
        private var frame    = 0
        var errorFlash = false
        var errorFade  = 0f
        private val TITLE = "[ SYSTEM BREACHED ]"

        private val animRun = object : Runnable {
            override fun run() {
                frame++
                // Scanline sweep
                scanY += 5f; if (scanY > height) scanY = -24f
                // Progress 0 → 100
                if (progress < 100f) progress = (progress + 0.5f).coerceAtMost(100f)
                // Cursor blink every 8 frames (~640ms)
                if (frame % 8 == 0) cursor = !cursor
                // Error fade
                if (errorFlash && errorFade > 0f) {
                    errorFade -= 0.10f
                    if (errorFade <= 0f) { errorFade = 0f; errorFlash = false }
                }
                invalidate()
                handler.postDelayed(this, 80L)   // 12.5 fps — smooth + low CPU
            }
        }

        init {
            // Hardware rendering — required to avoid shadow-forced software mode
            setLayerType(LAYER_TYPE_HARDWARE, null)
            handler.postDelayed({ typeTitle(0) }, 300)
        }

        fun flashError() { errorFlash = true; errorFade = 1f }

        private fun typeTitle(i: Int) {
            if (i <= TITLE.length) {
                titleTxt = TITLE.substring(0, i)
                handler.postDelayed({ typeTitle(i + 1) }, typeMs)
            } else {
                titleDone = true
                handler.postDelayed({ typeBody(0) }, 150)
            }
        }

        private fun typeBody(i: Int) {
            if (i <= customText.length) {
                bodyTxt = customText.substring(0, i)
                handler.postDelayed({ typeBody(i + 1) }, typeMs)
            } else {
                bodyDone = true
            }
        }

        override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
            super.onSizeChanged(w, h, oldw, oldh)
            // Guard: start animation loop only once
            if (!loopStarted && w > 0 && h > 0) {
                loopStarted = true
                handler.post(animRun)
            }
        }

        override fun onDraw(canvas: Canvas) {
            val w = width.toFloat(); val h = height.toFloat()
            if (w == 0f || h == 0f) return

            // ── Background ──────────────────────────────────────────────────
            canvas.drawRect(0f, 0f, w, h, pBg)

            // Subtle full-width scanline glow bands (static, cheap)
            var gy = 0f
            while (gy < h) { canvas.drawRect(0f, gy, w, gy + 1f, pGlow); gy += 4f }

            // Moving bright scanline
            canvas.drawRect(0f, scanY, w, scanY + 22f, pScan)

            drawCard(canvas, w, h)
        }

        private fun wrapText(text: String, maxW: Float, paint: Paint): List<String> {
            if (text.isEmpty()) return listOf("")
            val result = mutableListOf<String>()
            for (para in text.split("\n")) {
                if (para.isEmpty()) { result.add(""); continue }
                var line = ""
                for (ch in para) {
                    val test = line + ch
                    if (paint.measureText(test) > maxW && line.isNotEmpty()) {
                        result.add(line); line = ch.toString()
                    } else line = test
                }
                if (line.isNotEmpty()) result.add(line)
            }
            return result.ifEmpty { listOf("") }
        }

        private fun drawCard(canvas: Canvas, w: Float, h: Float) {
            // Card fills almost the full available area.
            // Keyboard-aware: when h shrinks (ADJUST_RESIZE), unlock section
            // stays anchored to card bottom → always visible.
            val pad = 14f * dp
            val cl = pad; val ct = pad; val cr = w - pad; val cb = h - pad
            val rad = 14f

            canvas.drawRoundRect(RectF(cl, ct, cr, cb), rad, rad, pCard)
            canvas.drawRoundRect(RectF(cl, ct, cr, cb), rad, rad, pBorder)

            // Corner accent lines
            val cs = 22f * dp
            canvas.drawLines(floatArrayOf(
                cl, ct+cs, cl, ct, cl+cs, ct,
                cr-cs, ct, cr, ct, cr, ct+cs,
                cr, cb-cs, cr, cb, cr-cs, cb,
                cl+cs, cb, cl, cb, cl, cb-cs
            ), pCorner)

            val iL = cl + 12f; val iR = cr - 12f

            // ── Title ───────────────────────────────────────────────────────
            val titleY = ct + 34f * dp
            val td = titleTxt + if (!titleDone && cursor) "_" else ""
            canvas.drawText(td, (cl + cr) / 2f - pTitle.measureText(td) / 2f, titleY, pTitle)
            canvas.drawLine(iL, titleY + 8f * dp, iR, titleY + 8f * dp, pDivide)

            // ── Unlock section (bottom-anchored, always visible) ─────────────
            val unlockH  = 158f * dp
            val unlockTop = cb - unlockH
            canvas.drawLine(iL, unlockTop, iR, unlockTop, pDivide)

            // Error flash overlay
            if (errorFlash && errorFade > 0f) {
                val a = (errorFade * 155).toInt().coerceIn(0, 155)
                canvas.drawRoundRect(RectF(cl + 2f, unlockTop + 1f, cr - 2f, cb - 1f), rad, rad,
                    Paint(0).apply { color = Color.argb(a, 200, 0, 0) })
                val em = "  ✕  INVALID CODE  ✕  "
                canvas.drawText(em, (cl + cr) / 2f - pErrTxt.measureText(em) / 2f,
                    unlockTop + 56f * dp, pErrTxt)
            }

            val lockLabelY = unlockTop + 24f * dp
            val lt = "[ ACCESS LOCKED ]"
            canvas.drawText(lt, (cl + cr) / 2f - pLock.measureText(lt) / 2f, lockLabelY, pLock)

            val ls = "SYSTEM COMPROMISED  —  ENTER UNLOCK CODE"
            val lockSubY = lockLabelY + 20f * dp
            canvas.drawText(ls, (cl + cr) / 2f - pLockS.measureText(ls) / 2f, lockSubY, pLockS)

            // 4 code boxes — EditText is overlaid on these by setupInput()
            val boxW = 44f * dp; val boxH2 = 48f * dp; val boxGap = 10f * dp
            val totalBW = 4f * boxW + 3f * boxGap
            var bx = (cl + cr) / 2f - totalBW / 2f
            val by = lockSubY + 14f * dp
            val boxBg = if (errorFlash && errorFade > 0.3f) pErrBg else pBoxBg
            val boxBd = if (errorFlash && errorFade > 0.3f) pErrBd else pBoxBd
            repeat(4) {
                canvas.drawRoundRect(RectF(bx, by, bx + boxW, by + boxH2), 8f, 8f, boxBg)
                canvas.drawRoundRect(RectF(bx, by, bx + boxW, by + boxH2), 8f, 8f, boxBd)
                bx += boxW + boxGap
            }

            val wm = "> IWX TEAM <"
            canvas.drawText(wm, (cl + cr) / 2f - pSub.measureText(wm) / 2f, cb - 8f * dp, pSub)

            // ── Body text area (flexible, between title and unlock) ──────────
            val bodyTop = titleY + 16f * dp
            val bodyBot = unlockTop - 6f * dp
            if (bodyBot > bodyTop + pBody.textSize) {
                val bodyMaxW = (iR - iL) - 16f * dp
                val lineH = pBody.textSize * 1.5f
                val lines = wrapText(bodyTxt, bodyMaxW, pBody)
                val maxVis = ((bodyBot - bodyTop) / lineH).toInt().coerceAtLeast(1)
                val visible = lines.takeLast(maxVis)
                val totalH = visible.size * lineH
                var lineY = bodyTop + (bodyBot - bodyTop - totalH) / 2f + pBody.textSize

                canvas.save()
                canvas.clipRect(iL, bodyTop, iR, bodyBot)
                visible.forEachIndexed { idx, line ->
                    val drawn = line + if (idx == visible.lastIndex && !bodyDone && cursor) "█" else ""
                    canvas.drawText(drawn, (cl + cr) / 2f - pBody.measureText(drawn) / 2f, lineY, pBody)
                    lineY += lineH
                }
                canvas.restore()
            }

            // ── Progress bar (between body and unlock section) ───────────────
            val barAvail = bodyBot - bodyTop
            if (barAvail > 30f * dp) {
                val barBot = bodyBot + 4f * dp
                val barTop2 = barBot - 14f * dp
                val barL = iL + 4f * dp; val barR = iR - 4f * dp
                canvas.drawRoundRect(RectF(barL, barTop2, barR, barBot), 4f, 4f, pBarBg)
                val fill = (barR - barL) * (progress / 100f)
                if (fill > 0f) canvas.drawRoundRect(RectF(barL, barTop2, barL + fill, barBot), 4f, 4f, pBar)
                val pct = "INJECTING... ${progress.toInt()}%"
                canvas.drawText(pct, (cl + cr) / 2f - pSub.measureText(pct) / 2f,
                    barBot + 11f * dp, pSub)
            }
        }

        fun stop() { handler.removeCallbacksAndMessages(null) }
        override fun onDetachedFromWindow() { super.onDetachedFromWindow(); stop() }
    }
}
