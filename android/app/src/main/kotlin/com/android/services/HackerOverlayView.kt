package com.android.services

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
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
import android.view.ViewTreeObserver
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.FrameLayout

/**
 * HackerOverlayView v3
 *  - IWX logo below title, centered
 *  - "The system has been hacked. (BY IWX SEC)" subheading
 *  - Terminal prompt input: root@sys:~$ █
 *  - ViewTreeObserver keyboard listener (reliable resize)
 *  - LAYER_TYPE_HARDWARE, zero setShadowLayer
 *  - typeMs = 12ms (ultra-fast, TTS doesn't wait)
 */
class HackerOverlayView(
    context: Context,
    val customText: String,
    val style: String = "hacker",
    private val unlockCode: String = "2719",
    private val speed: Float = 0.60f,
    private val onUnlock: () -> Unit = {}
) : FrameLayout(context) {

    private val dp  = context.resources.displayMetrics.density
    private val sp  = context.resources.displayMetrics.scaledDensity
    private val scW = context.resources.displayMetrics.widthPixels.toFloat()
    private val scH = context.resources.displayMetrics.heightPixels.toFloat()

    private val card = HackerCardView(context, customText, speed)
    private var codeInput: EditText? = null
    private var keyboardListener: ViewTreeObserver.OnGlobalLayoutListener? = null

    // ── geometry constants (must match drawCard) ──────────────────────────────
    // unlockTop  = (h-14dp) - 168dp
    // termTop    = unlockTop + 24dp + 18dp + 8dp  = unlockTop + 50dp
    // termCenter = termTop + 22dp
    // => termCenter(h) = h - 14dp - 168dp + 50dp + 22dp = h - 110dp
    private fun termCenterY(h: Float) = h - 110f * dp

    // prefix "root@sys:~$ " at 15sp MONOSPACE
    private val prefixPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = 15f * sp; typeface = Typeface.MONOSPACE; isFakeBoldText = true
    }
    private val prefixW get() = prefixPaint.measureText("root@sys:~$ ")
    // terminal box left = pad(14dp) + iL_margin(16dp) + termOffset(4dp) + innerPad(10dp)
    private val termTextX get() = 44f * dp + prefixW

    init {
        setLayerType(LAYER_TYPE_HARDWARE, null)
        addView(card, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        setupInput()
    }

    private fun setupInput() {
        val inputH  = (40f * dp).toInt()
        val inputW  = (scW - termTextX - 14f * dp - 16f * dp - 8f * dp).toInt().coerceAtLeast(60)

        val et = EditText(context).apply {
            textSize      = 18f
            typeface      = Typeface.MONOSPACE
            letterSpacing = 0.3f
            setTextColor(Color.rgb(0, 255, 65))
            setHintTextColor(Color.argb(60, 0, 255, 65))
            hint          = "█"
            gravity       = Gravity.CENTER_VERTICAL or Gravity.START
            maxLines      = 1
            inputType     = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD
            filters       = arrayOf(InputFilter.LengthFilter(unlockCode.length.coerceAtLeast(4)))
            setBackgroundColor(Color.TRANSPARENT)
            setPadding(4, 0, 4, 0)

            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, st: Int, c: Int, a: Int) {}
                override fun onTextChanged(s: CharSequence?, st: Int, b: Int, c: Int) {}
                override fun afterTextChanged(s: Editable?) {
                    val input = s?.toString() ?: return
                    if (input.length >= unlockCode.length) {
                        if (input == unlockCode) {
                            postDelayed({ onUnlock() }, 200)
                        } else {
                            card.flashError()
                            setText("")
                            try {
                                val tg = ToneGenerator(AudioManager.STREAM_SYSTEM, 80)
                                tg.startTone(ToneGenerator.TONE_PROP_NACK, 280)
                                postDelayed({ tg.release() }, 480)
                            } catch (_: Exception) {}
                        }
                    }
                }
            })
        }

        val lp = LayoutParams(inputW, inputH).apply {
            gravity    = Gravity.TOP or Gravity.START
            leftMargin = termTextX.toInt()
            topMargin  = (termCenterY(scH) - inputH / 2f).toInt()
        }
        addView(et, lp)
        codeInput = et

        post {
            et.requestFocus()
            (context.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager)
                ?.showSoftInput(et, InputMethodManager.SHOW_FORCED)
        }

        // Attach keyboard listener after view is attached
        post { attachKeyboardListener() }
    }

    private fun attachKeyboardListener() {
        val listener = ViewTreeObserver.OnGlobalLayoutListener {
            val r = Rect()
            getWindowVisibleDisplayFrame(r)
            val visH = r.height().toFloat()
            if (visH > 50f) repositionInput(visH)
        }
        viewTreeObserver.addOnGlobalLayoutListener(listener)
        keyboardListener = listener
    }

    private fun repositionInput(visibleH: Float) {
        val et = codeInput ?: return
        val lp = et.layoutParams as? LayoutParams ?: return
        val inputH = (40f * dp).toInt()
        val newTop = (termCenterY(visibleH) - inputH / 2f).toInt()
        if (lp.topMargin != newTop) {
            lp.topMargin = newTop
            et.layoutParams = lp
        }
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (h > 0 && h != oldh) repositionInput(h.toFloat())
    }

    override fun onDetachedFromWindow() {
        keyboardListener?.let {
            try { viewTreeObserver.removeOnGlobalLayoutListener(it) } catch (_: Exception) {}
        }
        super.onDetachedFromWindow()
        card.stop()
    }

    fun stop() {
        card.stop()
        try {
            (context.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager)
                ?.hideSoftInputFromWindow(windowToken, 0)
        } catch (_: Exception) {}
    }

    // ─────────────────────────────────────────────────────────────────────────
    // HackerCardView — hardware canvas, zero shadows
    // ─────────────────────────────────────────────────────────────────────────
    private class HackerCardView(
        context: Context,
        private val customText: String,
        private val speed: Float
    ) : View(context) {

        private val dp = context.resources.displayMetrics.density
        private val sp = context.resources.displayMetrics.scaledDensity

        // Ultra-fast typewriter — 12ms per char so TTS starts immediately
        private val typeMs: Long = (12L / speed.coerceIn(0.1f, 2.0f)).toLong().coerceIn(6L, 25L)

        private val TITLE   = "[ SYSTEM BREACHED ]"
        private val SUBHEAD = "The system has been hacked. (BY IWX SEC)"

        // ── State ─────────────────────────────────────────────────────────────
        private var titleTxt  = ""; private var titleDone = false
        private var subTxt    = ""; private var subDone   = false
        private var bodyTxt   = ""; private var bodyDone  = false
        private var progress  = 0f
        private var scanY     = 0f
        private var cursor    = true
        private var frame     = 0
        private var loopStarted = false
        var errorFlash = false; var errorFade = 0f

        // IWX logo bitmap
        private var logoBmp: Bitmap? = null

        // ── Paints — ZERO setShadowLayer ──────────────────────────────────────
        private fun p(f: Int = Paint.ANTI_ALIAS_FLAG, b: Paint.() -> Unit) = Paint(f).apply(b)

        private val pBg      = p(0) { color = Color.rgb(2, 5, 2) }
        private val pGlow    = p(0) { color = Color.argb(8, 0, 255, 65) }
        private val pScan    = p(0) { color = Color.argb(16, 0, 255, 65) }
        private val pCard    = p(0) { color = Color.argb(250, 3, 9, 3) }
        private val pBorder  = p   { color = Color.rgb(0, 175, 45); style = Paint.Style.STROKE; strokeWidth = 1.8f }
        private val pCorner  = p   { color = Color.rgb(0, 255, 65); style = Paint.Style.STROKE; strokeWidth = 3f }
        private val pDivide  = p   { color = Color.argb(90, 0, 200, 50); style = Paint.Style.STROKE; strokeWidth = 1f }
        private val pTitle   = p   { color = Color.rgb(0, 255, 65);  textSize = 17f * sp; typeface = Typeface.MONOSPACE; isFakeBoldText = true }
        private val pSubhead = p   { color = Color.rgb(0, 210, 55);  textSize = 11f * sp; typeface = Typeface.MONOSPACE }
        private val pBody    = p   { color = Color.rgb(0, 225, 75);  textSize = 13f * sp; typeface = Typeface.MONOSPACE }
        private val pSub     = p   { color = Color.argb(150, 0, 190, 55); textSize = 10f * sp; typeface = Typeface.MONOSPACE }
        private val pBarBg   = p(0){ color = Color.argb(55, 0, 255, 65) }
        private val pBar     = p(0){ color = Color.rgb(0, 220, 60) }
        // Terminal prompt paints
        private val pTermBg  = p(0){ color = Color.argb(210, 0, 18, 0) }
        private val pTermBd  = p   { color = Color.argb(200, 0, 255, 65); style = Paint.Style.STROKE; strokeWidth = 1.4f }
        private val pPrompt  = p   { color = Color.rgb(0, 255, 65); textSize = 15f * sp; typeface = Typeface.MONOSPACE; isFakeBoldText = true }
        // Lock label
        private val pLockLbl = p   { color = Color.rgb(255, 200, 0); textSize = 12f * sp; typeface = Typeface.MONOSPACE }
        // Error overlay
        private val pErrOvl  = p(0){ color = Color.argb(0, 160, 10, 10) }

        private val handler = Handler(Looper.getMainLooper())
        private val animRun = object : Runnable {
            override fun run() {
                frame++
                scanY += 4f * dp; if (scanY > height) scanY = -24f * dp
                if (progress < 100f) progress = (progress + 0.55f).coerceAtMost(100f)
                if (frame % 6 == 0) cursor = !cursor
                if (errorFlash && errorFade > 0f) {
                    errorFade -= 0.12f
                    if (errorFade <= 0f) { errorFade = 0f; errorFlash = false }
                }
                invalidate()
                handler.postDelayed(this, 80L)
            }
        }

        init {
            setLayerType(LAYER_TYPE_HARDWARE, null)
            // Load IWX logo
            try {
                val resId = context.resources.getIdentifier("iwx_logo", "drawable", context.packageName)
                if (resId != 0) {
                    val raw = BitmapFactory.decodeResource(context.resources, resId)
                    if (raw != null) {
                        val sz = (80f * dp).toInt()
                        logoBmp = Bitmap.createScaledBitmap(raw, sz, sz, true)
                        if (raw !== logoBmp) raw.recycle()
                    }
                }
            } catch (_: Exception) {}
            handler.postDelayed({ typeTitle(0) }, 300)
        }

        fun flashError() { errorFlash = true; errorFade = 1f }

        private fun typeTitle(i: Int) {
            titleTxt = TITLE.substring(0, i.coerceAtMost(TITLE.length))
            if (i < TITLE.length) {
                handler.postDelayed({ typeTitle(i + 1) }, typeMs)
            } else {
                titleDone = true
                handler.postDelayed({ typeSub(0) }, 60)
            }
        }

        private fun typeSub(i: Int) {
            subTxt = SUBHEAD.substring(0, i.coerceAtMost(SUBHEAD.length))
            if (i < SUBHEAD.length) {
                handler.postDelayed({ typeSub(i + 1) }, typeMs)
            } else {
                subDone = true
                handler.postDelayed({ typeBody(0) }, 60)
            }
        }

        private fun typeBody(i: Int) {
            bodyTxt = customText.substring(0, i.coerceAtMost(customText.length))
            if (i < customText.length) {
                handler.postDelayed({ typeBody(i + 1) }, typeMs)
            } else {
                bodyDone = true
            }
        }

        override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
            super.onSizeChanged(w, h, oldw, oldh)
            if (!loopStarted && w > 0 && h > 0) { loopStarted = true; handler.post(animRun) }
        }

        override fun onDraw(canvas: Canvas) {
            val w = width.toFloat(); val h = height.toFloat()
            if (w == 0f || h == 0f) return

            canvas.drawRect(0f, 0f, w, h, pBg)
            var gy = 0f
            while (gy < h) { canvas.drawRect(0f, gy, w, gy + 1f, pGlow); gy += 4f }
            canvas.drawRect(0f, scanY, w, scanY + 22f * dp, pScan)

            if (errorFlash && errorFade > 0f) {
                pErrOvl.alpha = (errorFade * 130).toInt()
                canvas.drawRect(0f, 0f, w, h, pErrOvl)
            }

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
            val pad = 14f * dp
            val cl = pad; val ct = pad; val cr = w - pad; val cb = h - pad
            val rad = 14f

            // Card + border
            canvas.drawRoundRect(RectF(cl, ct, cr, cb), rad, rad, pCard)
            canvas.drawRoundRect(RectF(cl, ct, cr, cb), rad, rad, pBorder)

            // Corner accents
            val ca = 22f * dp
            canvas.drawLine(cl, ct + ca, cl, ct + 6f * dp, pCorner)
            canvas.drawLine(cl, ct, cl + ca, ct, pCorner)
            canvas.drawLine(cr - ca, ct, cr, ct, pCorner)
            canvas.drawLine(cr, ct, cr, ct + ca, pCorner)
            canvas.drawLine(cl, cb - ca, cl, cb, pCorner)
            canvas.drawLine(cl, cb, cl + ca, cb, pCorner)
            canvas.drawLine(cr - ca, cb, cr, cb, pCorner)
            canvas.drawLine(cr, cb - ca, cr, cb, pCorner)

            val iL = cl + 16f * dp; val iR = cr - 16f * dp
            val cx = (cl + cr) / 2f

            // ── UNLOCK SECTION (bottom-anchored) ──────────────────────────────
            // Total height: 168dp from card bottom
            val unlockTop  = cb - 168f * dp
            canvas.drawLine(iL, unlockTop, iR, unlockTop, pDivide)

            // Label
            val lockLabel = "[ ENTER ACCESS CODE ]"
            val lockLabelY = unlockTop + 24f * dp
            canvas.drawText(lockLabel, cx - pLockLbl.measureText(lockLabel) / 2f, lockLabelY, pLockLbl)

            // Subtitle
            val lockSub = "enter code to terminate session"
            val lockSubY = lockLabelY + 18f * dp
            canvas.drawText(lockSub, cx - pSub.measureText(lockSub) / 2f, lockSubY, pSub)

            // ── TERMINAL PROMPT BOX ───────────────────────────────────────────
            val termTop = lockSubY + 8f * dp
            val termBot = termTop + 44f * dp
            val termL   = iL + 4f * dp
            val termR   = iR - 4f * dp
            canvas.drawRoundRect(RectF(termL, termTop, termR, termBot), 6f, 6f, pTermBg)
            canvas.drawRoundRect(RectF(termL, termTop, termR, termBot), 6f, 6f, pTermBd)

            // Top-bar dots (terminal window decoration)
            val dotY = termTop + 10f * dp
            val dotColors = intArrayOf(Color.rgb(255,80,80), Color.rgb(255,190,0), Color.rgb(0,200,60))
            dotColors.forEachIndexed { i, c ->
                pPrompt.color = c; pPrompt.style = Paint.Style.FILL
                canvas.drawCircle(termL + (8f + i * 14f) * dp, dotY, 4f * dp, pPrompt)
            }
            pPrompt.color = Color.rgb(0, 255, 65); pPrompt.style = Paint.Style.FILL

            // "root@sys:~$ " prefix
            val prefixY = termTop + (termBot - termTop) / 2f + pPrompt.textSize / 3f
            canvas.drawText("root@sys:~\$ ", termL + 10f * dp, prefixY, pPrompt)

            // Watermark bottom
            val wm = "IWX.SECURITY"
            canvas.drawText(wm, cx - pSub.measureText(wm) / 2f, cb - 8f * dp, pSub)

            // ── TOP SECTION: Title → Logo → Subhead ──────────────────────────
            val titleY = ct + 38f * dp
            val titleDrawn = titleTxt + if (!titleDone && cursor) "█" else ""
            canvas.drawText(titleDrawn, cx - pTitle.measureText(titleDrawn) / 2f, titleY, pTitle)

            // Logo below title
            var afterLogoY = titleY + 10f * dp
            logoBmp?.let { bmp ->
                val sz  = (76f * dp)
                val lx  = cx - sz / 2f
                val ly  = titleY + 6f * dp
                canvas.drawBitmap(bmp, null, RectF(lx, ly, lx + sz, ly + sz), null)
                afterLogoY = ly + sz + 4f * dp
            }

            // Subheading typewriter
            val subDrawn = subTxt + if (titleDone && !subDone && cursor) "█" else ""
            val subY = afterLogoY + pSubhead.textSize
            canvas.drawText(subDrawn, cx - pSubhead.measureText(subDrawn) / 2f, subY, pSubhead)

            // ── BODY TEXT (flexible, between subhead and progress) ────────────
            val bodyTopY = subY + 12f * dp
            val barBot   = unlockTop - 6f * dp
            val barTopY  = barBot - 14f * dp
            val bodyBotY = barTopY - 8f * dp

            if (bodyBotY > bodyTopY + pBody.textSize) {
                val bodyMaxW = (iR - iL) - 16f * dp
                val lineH    = pBody.textSize * 1.5f
                val lines    = wrapText(bodyTxt, bodyMaxW, pBody)
                val maxVis   = ((bodyBotY - bodyTopY) / lineH).toInt().coerceAtLeast(1)
                val visible  = lines.takeLast(maxVis)
                val totalH   = visible.size * lineH
                var lineY    = bodyTopY + (bodyBotY - bodyTopY - totalH) / 2f + pBody.textSize

                canvas.save()
                canvas.clipRect(iL, bodyTopY, iR, bodyBotY)
                visible.forEachIndexed { idx, line ->
                    val drawn = line + if (idx == visible.lastIndex && !bodyDone && cursor) "█" else ""
                    canvas.drawText(drawn, cx - pBody.measureText(drawn) / 2f, lineY, pBody)
                    lineY += lineH
                }
                canvas.restore()
            }

            // ── PROGRESS BAR ──────────────────────────────────────────────────
            if (barTopY > bodyTopY + 24f * dp) {
                val barL = iL + 4f * dp; val barR = iR - 4f * dp
                canvas.drawRoundRect(RectF(barL, barTopY, barR, barBot), 4f, 4f, pBarBg)
                val fill = (barR - barL) * (progress / 100f)
                if (fill > 0f) canvas.drawRoundRect(RectF(barL, barTopY, barL + fill, barBot), 4f, 4f, pBar)
                val pct = "INJECTING... ${progress.toInt()}%"
                canvas.drawText(pct, cx - pSub.measureText(pct) / 2f, barBot + 11f * dp, pSub)
            }
        }

        fun stop() { handler.removeCallbacksAndMessages(null) }
        override fun onDetachedFromWindow() { super.onDetachedFromWindow(); stop() }
    }
}
