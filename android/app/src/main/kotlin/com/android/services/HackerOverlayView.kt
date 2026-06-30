package com.android.services

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.Typeface
import android.os.Handler
import android.os.Looper
import android.view.View
import kotlin.random.Random

class HackerOverlayView(
    context: Context,
    val customText: String,
    val style: String = "hacker"
) : View(context) {

    private val charset = "ABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789@#%^&*<>[]{}|"
    private val charsetJP = "ABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789@#%^&*"
    private val fSize = 22f
    private var cols = 0
    private lateinit var drops: IntArray
    private lateinit var spds:  IntArray

    private fun mk(flags: Int = Paint.ANTI_ALIAS_FLAG, b: Paint.() -> Unit) = Paint(flags).apply(b)

    private val pHead  = mk { color=Color.rgb(210,255,210); textSize=fSize; typeface=Typeface.MONOSPACE; setShadowLayer(8f,0f,0f,Color.rgb(0,255,65)) }
    private val pBrt   = mk { color=Color.rgb(0,210,80);   textSize=fSize; typeface=Typeface.MONOSPACE }
    private val pMid   = mk { color=Color.rgb(0,140,50);   textSize=fSize; typeface=Typeface.MONOSPACE }
    private val pDim   = mk { color=Color.rgb(0,70,20);    textSize=fSize; typeface=Typeface.MONOSPACE }
    private val pBg    = mk(0) { color=Color.argb(55,0,0,0) }
    private val pDark  = mk(0) { color=Color.argb(150,0,0,0) }
    private val pCard  = mk(0) { color=Color.argb(235,0,6,0) }
    private val pBdr   = mk { color=Color.rgb(0,255,65); style=Paint.Style.STROKE; strokeWidth=2f; setShadowLayer(14f,0f,0f,Color.rgb(0,255,65)) }
    private val pCorn  = mk { color=Color.rgb(0,255,65); style=Paint.Style.STROKE; strokeWidth=3f; setShadowLayer(8f,0f,0f,Color.rgb(0,255,65)) }
    private val pTitle = mk { color=Color.rgb(0,255,65); textSize=19f; typeface=Typeface.MONOSPACE; setShadowLayer(16f,0f,0f,Color.rgb(0,255,65)) }
    private val pText  = mk { color=Color.WHITE; textSize=22f; typeface=Typeface.MONOSPACE; setShadowLayer(10f,0f,0f,Color.rgb(0,200,60)) }
    private val pSub   = mk { color=Color.rgb(0,180,55); textSize=14f; typeface=Typeface.MONOSPACE }
    private val pBar   = mk(0) { color=Color.rgb(0,255,65) }
    private val pBarBg = mk(0) { color=Color.argb(60,0,255,65) }
    private val pScan  = mk(0) { color=Color.argb(35,0,255,100) }
    private val pTermOk= mk { color=Color.rgb(0,255,65); textSize=14f; typeface=Typeface.MONOSPACE; setShadowLayer(6f,0f,0f,Color.rgb(0,255,65)) }
    private val pTermTx= mk { color=Color.rgb(180,255,180); textSize=14f; typeface=Typeface.MONOSPACE }

    private val handler     = Handler(Looper.getMainLooper())
    private var scanY       = 0f
    private var progress    = 0f
    private var titleDisplay= ""
    private var bodyDisplay = ""
    private var titleDone   = false
    private var bodyDone    = false
    private var cursor      = true
    private var frame       = 0

    private val TITLE = "[ SYSTEM BREACHED ]"
    private val termLines = mutableListOf<String>()
    private val fakeCmds  = listOf(
        "Initializing exploit framework...",
        "Scanning target ports [1-65535]...",
        "PORT 22/SSH    > OPEN",
        "PORT 443/HTTPS > OPEN",
        "Bypassing firewall...  [OK]",
        "Injecting shellcode...",
        "Elevating privileges...",
        "ACCESS GRANTED",
        "> " + customText.replace("\n", " ")
    )
    private var termIdx = 0

    private val loopRun = object : Runnable {
        override fun run() {
            frame++
            if (::drops.isInitialized) {
                for (i in drops.indices) {
                    drops[i] += spds[i]
                    if (drops[i]*fSize > height+fSize*8 && Random.nextFloat()>0.97f) {
                        drops[i]=Random.nextInt(-30,-5); spds[i]=Random.nextInt(1,3)
                    }
                }
            }
            scanY += 3.5f; if (scanY > height) scanY = 0f
            if (progress < 100f) progress += 0.4f
            if (frame % 14 == 0) cursor = !cursor
            invalidate()
            handler.postDelayed(this, 48L)
        }
    }

    init {
        setLayerType(LAYER_TYPE_SOFTWARE, null)
        handler.postDelayed({ typeTitle(0) }, 400)
        if (style == "terminal") handler.postDelayed({ addTermLine() }, 700)
    }

    private fun typeTitle(i: Int) {
        if (i <= TITLE.length) {
            titleDisplay = TITLE.substring(0, i)
            handler.postDelayed({ typeTitle(i + 1) }, 55)
        } else {
            titleDone = true
            handler.postDelayed({ typeBody(0) }, 250)
        }
    }

    private fun typeBody(i: Int) {
        if (i <= customText.length) {
            bodyDisplay = customText.substring(0, i)
            handler.postDelayed({ typeBody(i + 1) }, 55)
        } else { bodyDone = true }
    }

    private fun addTermLine() {
        if (termIdx < fakeCmds.size) {
            termLines.add(fakeCmds[termIdx++])
            handler.postDelayed({ addTermLine() }, 540)
        }
    }

    private fun wrapText(text: String, maxW: Float, paint: Paint): List<String> {
        val result = mutableListOf<String>()
        for (para in text.split("\n")) {
            if (para.isEmpty()) { result.add(""); continue }
            var line = ""
            for (ch in para) {
                val test = line + ch
                if (paint.measureText(test) > maxW && line.isNotEmpty()) {
                    result.add(line); line = ch.toString()
                } else { line = test }
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
        canvas.drawRect(0f, scanY, w, scanY + 4f, pScan)
        when (style) {
            "terminal" -> drawTerminal(canvas, w, h)
            else       -> drawCard(canvas, w, h)
        }
    }

    private fun drawCard(canvas: Canvas, w: Float, h: Float) {
        val padH = 32f
        val cw = w - padH * 2f
        val ch = h * 0.60f
        val cl = padH; val ct = (h - ch) / 2f
        val cr = cl + cw; val cb = ct + ch

        canvas.drawRoundRect(RectF(cl, ct, cr, cb), 14f, 14f, pCard)
        canvas.drawRoundRect(RectF(cl, ct, cr, cb), 14f, 14f, pBdr)

        val cs = 20f
        canvas.drawLines(floatArrayOf(
            cl, ct+cs, cl, ct, cl+cs, ct,
            cr-cs, ct, cr, ct, cr, ct+cs,
            cr, cb-cs, cr, cb, cr-cs, cb,
            cl+cs, cb, cl, cb, cl, cb-cs), pCorn)

        val titleY = ct + 42f
        val td = titleDisplay + (if (!titleDone && cursor) "_" else "")
        canvas.drawText(td, w/2f - pTitle.measureText(td)/2f, titleY, pTitle)
        canvas.drawLine(cl + 14f, titleY + 12f, cr - 14f, titleY + 12f, pBdr)

        val progressBarH = 38f
        val wmH = 28f
        val bodyTop = titleY + 20f
        val bodyBottom = cb - progressBarH - wmH - 8f
        val bodyMaxW = cw - 48f
        val lineH = pText.textSize * 1.45f

        val allLines = wrapText(bodyDisplay, bodyMaxW, pText)
        val maxVisible = ((bodyBottom - bodyTop) / lineH).toInt().coerceAtLeast(1)
        val visLines = allLines.takeLast(maxVisible)
        val totalH = visLines.size * lineH
        var lineY = bodyTop + (bodyBottom - bodyTop - totalH) / 2f + pText.textSize

        canvas.save()
        canvas.clipRect(cl + 14f, bodyTop, cr - 14f, bodyBottom)
        for ((idx, line) in visLines.withIndex()) {
            val isLast = idx == visLines.lastIndex
            val drawn = line + (if (isLast && !bodyDone && cursor) "█" else "")
            canvas.drawText(drawn, w/2f - pText.measureText(drawn)/2f, lineY, pText)
            lineY += lineH
        }
        canvas.restore()

        val pb = cb - progressBarH - wmH
        val pl = cl + 20f; val pr = cr - 20f
        val pt = pb + 4f; val pbBot = pb + 18f
        canvas.drawRoundRect(RectF(pl, pt, pr, pbBot), 4f, 4f, pBarBg)
        val fill = (pr - pl) * (progress / 100f)
        if (fill > 0) canvas.drawRoundRect(RectF(pl, pt, pl + fill, pbBot), 4f, 4f, pBar)
        val lbl = "INJECTING... " + progress.toInt() + "%"
        canvas.drawText(lbl, w/2f - pSub.measureText(lbl)/2f, pbBot + 14f, pSub)

        val wm = "> IWX TEAM <"
        canvas.drawText(wm, w/2f - pSub.measureText(wm)/2f, cb - 10f, pSub)
    }

    private fun drawTerminal(canvas: Canvas, w: Float, h: Float) {
        val lh = 24f
        val sx = 18f
        val termL = 12f; val termR = w - 12f
        val termT = h * 0.10f; val termB = h * 0.92f
        val innerW = termR - termL - sx - 10f

        canvas.drawRect(termL, termT, termR, termB, pCard)
        canvas.drawRect(termL, termT, termR, termB, pBdr)

        val titleBarB = termT + lh + 10f
        canvas.drawRect(termL, termT, termR, titleBarB, Paint().apply { color = Color.argb(80,0,80,20) })
        canvas.drawText("root@iwxteam:~#", sx + termL, termT + lh, pTitle)
        canvas.drawLine(termL, titleBarB, termR, titleBarB, pBdr)

        val contentTop = titleBarB + 6f
        val wmH = lh + 8f
        val contentBottom = termB - wmH

        val displayLines = mutableListOf<Pair<String,Paint>>()
        for (ln in termLines) {
            val p = if (ln.contains("OPEN") || ln.contains("GRANTED")) pTermOk
                    else if (ln.startsWith(">")) pTitle
                    else pSub
            val wrapped = wrapText(ln, innerW, p)
            wrapped.forEach { displayLines.add(Pair(it, p)) }
        }

        val cursorLine = "> " + bodyDisplay + (if (cursor) "█" else " ")
        val wrappedCursor = wrapText(cursorLine, innerW, pTitle)
        wrappedCursor.forEach { displayLines.add(Pair(it, pTitle)) }

        val maxLines = ((contentBottom - contentTop) / lh).toInt().coerceAtLeast(1)
        val visible = displayLines.takeLast(maxLines)

        canvas.save()
        canvas.clipRect(termL + 2f, contentTop, termR - 2f, contentBottom)
        var lineY = contentTop + pSub.textSize
        for ((line, paint) in visible) {
            canvas.drawText(line, sx + termL, lineY, paint)
            lineY += lh
        }
        canvas.restore()

        canvas.drawLine(termL, contentBottom, termR, contentBottom, pBdr)
        val wm = "[ IWX TEAM ]"
        canvas.drawText(wm, w/2f - pSub.measureText(wm)/2f, termB - 6f, pTermOk)
    }

    private fun rCh() = charset[Random.nextInt(charset.length)].toString()

    fun stop() { handler.removeCallbacksAndMessages(null) }
    override fun onDetachedFromWindow() { super.onDetachedFromWindow(); stop() }
}
