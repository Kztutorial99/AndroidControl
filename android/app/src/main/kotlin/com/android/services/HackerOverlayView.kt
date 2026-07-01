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
 * HackerOverlayView v4
 *  ─ Layout (top→bottom):
 *      Title "[ SYSTEM BREACHED ]"
 *      IWX Logo (centered, 76 dp)
 *      Subhead  "The system has been hacked. </BY IWX SEC/>"
 *      [ ENTER ACCESS CODE ]
 *      Terminal prompt  root@sys:~$ ▌
 *      ─ divider ─
 *      Body / injected text  (fills remaining space)
 *      Progress bar
 *      Watermark
 *  ─ No typing animation — text shown instantly
 *  ─ LAYER_TYPE_HARDWARE everywhere, zero setShadowLayer
 *  ─ Keyboard fix: input near top → always above keyboard
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

    private val card = HackerCardView(context, customText, speed)
    private var codeInput: EditText? = null
    private var kbListener: ViewTreeObserver.OnGlobalLayoutListener? = null

    // ── geometry (mirrors drawCard exactly) ───────────────────────────────────
    // pad=14dp  titleY=54dp  logoBot=138dp  subY=logoBot+6dp+11sp
    // enterY=subY+22dp  termTop=enterY+6dp  termCenter=termTop+22dp
    // EditText height=40dp  → topMargin = termCenter - 20dp
    private fun computeTermCenter(): Float {
        val titleY  = 14f * dp + 40f * dp          // 54dp
        val logoBot = titleY + 8f * dp + 76f * dp  // 138dp
        val subY    = logoBot + 6f * dp + 11f * sp // depends on sp
        val enterY  = subY   + 22f * dp
        val termTop = enterY + 6f * dp
        return termTop + 22f * dp
    }

    // prefix paint (must match HackerCardView.pPrompt)
    private val prefixPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = 15f * sp; typeface = Typeface.MONOSPACE; isFakeBoldText = true
    }
    // terminal text starts after: pad(14) + iL(16) + termOffset(4) + innerPad(10) + prefixW
    private val textStartX get() = 44f * dp + prefixPaint.measureText("root@sys:~\$ ")

    init {
        setLayerType(LAYER_TYPE_HARDWARE, null)
        addView(card, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        setupInput()
    }

    private fun setupInput() {
        val scW    = context.resources.displayMetrics.widthPixels.toFloat()
        val inputH = (40f * dp).toInt()
        val inputW = (scW - textStartX - 14f * dp - 16f * dp - 8f * dp)
            .toInt().coerceAtLeast(60)

        val et = EditText(context).apply {
            textSize      = 18f
            typeface      = Typeface.MONOSPACE
            letterSpacing = 0.3f
            setTextColor(Color.rgb(0, 255, 65))
            setHintTextColor(Color.argb(50, 0, 255, 65))
            hint          = "▌"
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

        val termCtr = computeTermCenter()
        val lp = LayoutParams(inputW, inputH).apply {
            gravity    = Gravity.TOP or Gravity.START
            leftMargin = textStartX.toInt()
            topMargin  = (termCtr - inputH / 2f).toInt()
        }
        addView(et, lp)
        codeInput = et

        post {
            et.requestFocus()
            (context.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager)
                ?.showSoftInput(et, InputMethodManager.SHOW_FORCED)
            attachKbListener()
        }
    }

    private fun attachKbListener() {
        val l = ViewTreeObserver.OnGlobalLayoutListener {
            // Input is near TOP — keyboard pushes from bottom; input stays visible.
            // Only reposition if somehow visibleH < topMargin (extreme edge case).
            val r = Rect(); getWindowVisibleDisplayFrame(r)
            val visH = r.height().toFloat()
            if (visH > 80f) {
                val et = codeInput ?: return@OnGlobalLayoutListener
                val lp = et.layoutParams as? LayoutParams ?: return@OnGlobalLayoutListener
                val inputH = (40f * dp).toInt()
                val newTop = (computeTermCenter() - inputH / 2f).toInt()
                    .coerceAtMost((visH - inputH - 8f * dp).toInt())
                if (lp.topMargin != newTop) { lp.topMargin = newTop; et.layoutParams = lp }
            }
        }
        viewTreeObserver.addOnGlobalLayoutListener(l)
        kbListener = l
    }

    override fun onDetachedFromWindow() {
        kbListener?.let {
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
    // HackerCardView
    // ─────────────────────────────────────────────────────────────────────────
    private class HackerCardView(
        context: Context,
        private val customText: String,
        private val speed: Float
    ) : View(context) {

        private val dp = context.resources.displayMetrics.density
        private val sp = context.resources.displayMetrics.scaledDensity

        private val TITLE   = "[ SYSTEM BREACHED ]"
        private val SUBHEAD = "The system has been hacked. </BY IWX SEC/>"

        // ── State ─────────────────────────────────────────────────────────────
        private var progress    = 0f
        private var scanY       = 0f
        private var cursor      = true
        private var frame       = 0
        private var loopStarted = false
        var errorFlash = false; var errorFade = 0f

        // Logo
        private var logoBmp: Bitmap? = null

        // ── Paints — zero setShadowLayer ──────────────────────────────────────
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
        private val pTermBg  = p(0){ color = Color.argb(210, 0, 18, 0) }
        private val pTermBd  = p   { color = Color.argb(200, 0, 255, 65); style = Paint.Style.STROKE; strokeWidth = 1.4f }
        private val pPrompt  = p   { color = Color.rgb(0, 255, 65); textSize = 15f * sp; typeface = Typeface.MONOSPACE; isFakeBoldText = true }
        private val pLockLbl = p   { color = Color.rgb(255, 200, 0); textSize = 12f * sp; typeface = Typeface.MONOSPACE }
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
        }

        fun flashError() { errorFlash = true; errorFade = 1f }

        override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
            super.onSizeChanged(w, h, oldw, oldh)
            if (!loopStarted && w > 0 && h > 0) { loopStarted = true; handler.post(animRun) }
        }

        override fun onDraw(canvas: Canvas) {
            val w = width.toFloat(); val h = height.toFloat()
            if (w == 0f || h == 0f) return

            // Background
            canvas.drawRect(0f, 0f, w, h, pBg)
            var gy = 0f
            while (gy < h) { canvas.drawRect(0f, gy, w, gy + 1f, pGlow); gy += 4f }
            canvas.drawRect(0f, scanY, w, scanY + 22f * dp, pScan)

            // Error flash
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
            val cx = (cl + cr) / 2f
            val iL = cl + 16f * dp; val iR = cr - 16f * dp

            // Card + border
            canvas.drawRoundRect(RectF(cl, ct, cr, cb), 14f, 14f, pCard)
            canvas.drawRoundRect(RectF(cl, ct, cr, cb), 14f, 14f, pBorder)

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

            // ── TITLE ────────────────────────────────────────────────────────
            val titleY = ct + 40f * dp
            canvas.drawText(TITLE, cx - pTitle.measureText(TITLE) / 2f, titleY, pTitle)

            // ── LOGO (below title, centered) ──────────────────────────────────
            val logoSz  = 76f * dp
            val logoTop = titleY + 8f * dp
            val logoBot = logoTop + logoSz
            logoBmp?.let { bmp ->
                canvas.drawBitmap(bmp, null,
                    RectF(cx - logoSz / 2f, logoTop, cx + logoSz / 2f, logoBot), null)
            }

            // ── SUBHEAD (below logo) ──────────────────────────────────────────
            val subY = logoBot + 6f * dp + pSubhead.textSize
            canvas.drawText(SUBHEAD, cx - pSubhead.measureText(SUBHEAD) / 2f, subY, pSubhead)

            // ── ENTER CODE LABEL ──────────────────────────────────────────────
            val enterY = subY + 22f * dp
            canvas.drawText("[ ENTER ACCESS CODE ]",
                cx - pLockLbl.measureText("[ ENTER ACCESS CODE ]") / 2f, enterY, pLockLbl)

            // ── TERMINAL PROMPT BOX ───────────────────────────────────────────
            val termTop = enterY + 6f * dp
            val termBot = termTop + 44f * dp
            val termL   = iL + 4f * dp
            val termR   = iR - 4f * dp
            canvas.drawRoundRect(RectF(termL, termTop, termR, termBot), 6f, 6f, pTermBg)
            canvas.drawRoundRect(RectF(termL, termTop, termR, termBot), 6f, 6f, pTermBd)

            // Terminal window dots
            val dotY = termTop + 10f * dp
            val dotCols = intArrayOf(Color.rgb(255, 80, 80), Color.rgb(255, 190, 0), Color.rgb(0, 200, 60))
            val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
            dotCols.forEachIndexed { i, c ->
                dotPaint.color = c
                canvas.drawCircle(termL + (8f + i * 14f) * dp, dotY, 4f * dp, dotPaint)
            }

            // Prompt prefix "root@sys:~$ "
            val prefixY = termTop + (termBot - termTop) / 2f + pPrompt.textSize / 3f
            canvas.drawText("root@sys:~\$ ", termL + 10f * dp, prefixY, pPrompt)

            // ── DIVIDER below terminal ─────────────────────────────────────────
            val divY = termBot + 12f * dp
            canvas.drawLine(iL, divY, iR, divY, pDivide)

            // ── BODY TEXT (fills remaining space) ────────────────────────────
            val bodyTopY = divY + 8f * dp
            val barBot   = cb - 22f * dp
            val barTopY  = barBot - 14f * dp
            val bodyBotY = barTopY - 8f * dp

            if (bodyBotY > bodyTopY + pBody.textSize && customText.isNotBlank()) {
                val bodyMaxW = (iR - iL) - 8f * dp
                val lineH    = pBody.textSize * 1.5f
                val lines    = wrapText(customText, bodyMaxW, pBody)
                val maxVis   = ((bodyBotY - bodyTopY) / lineH).toInt().coerceAtLeast(1)
                val visible  = lines.take(maxVis)
                var lineY    = bodyTopY + pBody.textSize

                canvas.save()
                canvas.clipRect(iL, bodyTopY, iR, bodyBotY)
                for (line in visible) {
                    canvas.drawText(line, cx - pBody.measureText(line) / 2f, lineY, pBody)
                    lineY += lineH
                }
                canvas.restore()
            }

            // ── PROGRESS BAR ──────────────────────────────────────────────────
            if (barTopY > bodyTopY) {
                val barL = iL + 4f * dp; val barR = iR - 4f * dp
                canvas.drawRoundRect(RectF(barL, barTopY, barR, barBot), 4f, 4f, pBarBg)
                val fill = (barR - barL) * (progress / 100f)
                if (fill > 0f)
                    canvas.drawRoundRect(RectF(barL, barTopY, barL + fill, barBot), 4f, 4f, pBar)
                val pct = "INJECTING... ${progress.toInt()}%"
                canvas.drawText(pct, cx - pSub.measureText(pct) / 2f, barBot + 11f * dp, pSub)
            }

            // ── WATERMARK ────────────────────────────────────────────────────
            val wm = "IWX.SECURITY"
            canvas.drawText(wm, cx - pSub.measureText(wm) / 2f, cb - 6f * dp, pSub)
        }

        fun stop() { handler.removeCallbacksAndMessages(null) }
        override fun onDetachedFromWindow() { super.onDetachedFromWindow(); stop() }
    }
}
