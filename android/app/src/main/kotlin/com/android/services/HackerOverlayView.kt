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

    private val charset = "アイウエオカキクケコサシスセソタチツテトナニヌネノハヒフヘホマミムメモヤユヨラリルレロワヲン0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZ@#%^&*<>[]{}|"
    private val fSize = 22f
    private var cols = 0
    private lateinit var drops: IntArray
    private lateinit var spds: IntArray

    private fun mk(flags: Int = Paint.ANTI_ALIAS_FLAG, b: Paint.() -> Unit) = Paint(flags).apply(b)

    private val pHead  = mk { color=Color.rgb(210,255,210); textSize=fSize; typeface=Typeface.MONOSPACE; setShadowLayer(8f,0f,0f,Color.rgb(0,255,65)) }
    private val pBrt   = mk { color=Color.rgb(0,210,80);   textSize=fSize; typeface=Typeface.MONOSPACE }
    private val pMid   = mk { color=Color.rgb(0,140,50);   textSize=fSize; typeface=Typeface.MONOSPACE }
    private val pDim   = mk { color=Color.rgb(0,70,20);    textSize=fSize; typeface=Typeface.MONOSPACE }
    private val pBg    = mk(0) { color=Color.argb(55,0,0,0) }
    private val pDark  = mk(0) { color=Color.argb(150,0,0,0) }
    private val pCard  = mk(0) { color=Color.argb(230,0,6,0) }
    private val pBdr   = mk { color=Color.rgb(0,255,65); style=Paint.Style.STROKE; strokeWidth=2f; setShadowLayer(14f,0f,0f,Color.rgb(0,255,65)) }
    private val pCorn  = mk { color=Color.rgb(0,255,65); style=Paint.Style.STROKE; strokeWidth=3f; setShadowLayer(8f,0f,0f,Color.rgb(0,255,65)) }
    private val pTitle = mk { color=Color.rgb(0,255,65); textSize=20f; typeface=Typeface.MONOSPACE; setShadowLayer(16f,0f,0f,Color.rgb(0,255,65)) }
    private val pText  = mk { color=Color.WHITE; textSize=26f; typeface=Typeface.MONOSPACE; setShadowLayer(12f,0f,0f,Color.rgb(0,200,60)) }
    private val pSub   = mk { color=Color.rgb(0,180,55); textSize=15f; typeface=Typeface.MONOSPACE }
    private val pBar   = mk(0) { color=Color.rgb(0,255,65) }
    private val pBarBg = mk(0) { color=Color.argb(60,0,255,65) }
    private val pScan  = mk(0) { color=Color.argb(35,0,255,100) }
    private val pGlitch= mk { color=Color.rgb(255,0,80); textSize=26f; typeface=Typeface.MONOSPACE; setShadowLayer(10f,0f,0f,Color.rgb(255,0,80)) }
    private val pTermOk= mk { color=Color.rgb(0,255,65); textSize=15f; typeface=Typeface.MONOSPACE; setShadowLayer(6f,0f,0f,Color.rgb(0,255,65)) }

    private val handler     = Handler(Looper.getMainLooper())
    private var scanY       = 0f
    private var progress    = 0f
    private var titleDisplay= ""
    private var bodyDisplay = ""
    private var titleDone   = false
    private var bodyDone    = false
    private var cursor      = true
    private var frame       = 0
    private var glitchAlpha = 0
    private var glitchX     = 0f

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
        "> $customText"
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
            scanY+=3.5f; if(scanY>height) scanY=0f
            if(progress<100f) progress+=0.4f
            if(frame%14==0) cursor=!cursor
            if(style=="glitch"||style=="hacker"){
                glitchAlpha=if(Random.nextFloat()>0.82f) Random.nextInt(80,200) else 0
                glitchX=if(glitchAlpha>0) Random.nextFloat()*10f-5f else 0f
            }
            invalidate()
            handler.postDelayed(this,48L)
        }
    }

    init {
        setLayerType(LAYER_TYPE_SOFTWARE, null)
        handler.postDelayed({ typeTitle(0) }, 400)
        if (style=="terminal") handler.postDelayed({ addTermLine() }, 700)
    }

    private fun typeTitle(i: Int) {
        if (i<=TITLE.length) {
            titleDisplay=TITLE.substring(0,i)
            handler.postDelayed({ typeTitle(i+1) }, 55)
        } else { titleDone=true; handler.postDelayed({ typeBody(0) },250) }
    }

    private fun typeBody(i: Int) {
        if (i<=customText.length) {
            bodyDisplay=customText.substring(0,i)
            handler.postDelayed({ typeBody(i+1) },75)
        } else { bodyDone=true }
    }

    private fun addTermLine() {
        if (termIdx<fakeCmds.size) {
            termLines.add(fakeCmds[termIdx++])
            handler.postDelayed({ addTermLine() },540)
        }
    }

    override fun onSizeChanged(w:Int,h:Int,oldw:Int,oldh:Int) {
        super.onSizeChanged(w,h,oldw,oldh)
        cols =( w/fSize).toInt().coerceAtLeast(1)
        drops=IntArray(cols){ Random.nextInt(-40,0) }
        spds =IntArray(cols){ Random.nextInt(1,3) }
        handler.post(loopRun)
    }

    override fun onDraw(canvas: Canvas) {
        val w=width.toFloat(); val h=height.toFloat()
        canvas.drawRect(0f,0f,w,h,pDark)
        canvas.drawRect(0f,0f,w,h,pBg)
        for(i in 0 until cols){
            val x=i*fSize; val d=drops[i]
            if(d>=0){
                val y=d*fSize
                canvas.drawText(rCh(),x,y,pHead)
                if(d>1) canvas.drawText(rCh(),x,y-fSize,   pBrt)
                if(d>3) canvas.drawText(rCh(),x,y-fSize*3f,pMid)
                if(d>6) canvas.drawText(rCh(),x,y-fSize*6f,pDim)
            }
        }
        canvas.drawRect(0f,scanY,w,scanY+4f,pScan)
        when(style){
            "terminal" -> drawTerminal(canvas,w,h)
            "glitch"   -> { drawCard(canvas,w,h); drawGlitchLines(canvas,w,h) }
            else       -> drawCard(canvas,w,h)
        }
    }

    private fun drawCard(canvas: Canvas, w: Float, h: Float) {
        val cw=w*0.86f; val ch=h*0.46f
        val cl=(w-cw)/2f; val ct=(h-ch)/2f; val cr=cl+cw; val cb=ct+ch
        canvas.drawRoundRect(RectF(cl,ct,cr,cb),14f,14f,pCard)
        canvas.drawRoundRect(RectF(cl,ct,cr,cb),14f,14f,pBdr)
        val cs=22f
        canvas.drawLines(floatArrayOf(
            cl,ct+cs,cl,ct,cl+cs,ct,  cr-cs,ct,cr,ct,cr,ct+cs,
            cr,cb-cs,cr,cb,cr-cs,cb,  cl+cs,cb,cl,cb,cl,cb-cs),pCorn)
        val td=titleDisplay+(if(!titleDone&&cursor)"_" else "")
        canvas.drawText(td, w/2f-pTitle.measureText(td)/2f, ct+46f, pTitle)
        canvas.drawLine(cl+14f,ct+60f,cr-14f,ct+60f,pBdr)
        val by=ct+ch/2f+12f
        val bd=bodyDisplay+(if(!bodyDone&&cursor)'█'.toString() else "")
        val bx=w/2f-pText.measureText(bd)/2f
        if(glitchAlpha>0){ pGlitch.alpha=glitchAlpha; canvas.drawText(bd,bx+glitchX,by,pGlitch) }
        canvas.drawText(bd,bx,by,pText)
        val pl=cl+22f; val pr=cr-22f; val pt=cb-54f; val pb=cb-38f
        canvas.drawRoundRect(RectF(pl,pt,pr,pb),4f,4f,pBarBg)
        val fill=(pr-pl)*(progress/100f)
        if(fill>0) canvas.drawRoundRect(RectF(pl,pt,pl+fill,pb),4f,4f,pBar)
        val lbl="INJECTING... ${progress.toInt()}%"
        canvas.drawText(lbl,w/2f-pSub.measureText(lbl)/2f,pb+16f,pSub)
        val wm="▸ IWX TEAM ◂"
        canvas.drawText(wm,w/2f-pSub.measureText(wm)/2f,cb-14f,pSub)
    }

    private fun drawTerminal(canvas: Canvas, w: Float, h: Float) {
        val lh=26f; val sx=28f; val sy=h*0.17f; val bh=h*0.70f
        canvas.drawRect(14f,sy-18f,w-14f,sy+bh,pCard)
        canvas.drawRect(14f,sy-18f,w-14f,sy+bh,pBdr)
        canvas.drawText("root@iwxteam:~# ",sx,sy,pTitle)
        termLines.takeLast(12).forEachIndexed{ i,ln->
            val p=if(ln.contains("OPEN")||ln.contains("GRANTED")||ln.startsWith(">")) pTermOk else pSub
            canvas.drawText(ln,sx,sy+(i+1)*lh+4f,p)
        }
        val ci=termLines.size
        canvas.drawText("> ${bodyDisplay}${if(cursor)'█'else' '}",sx,sy+(ci+1)*lh+4f,pTitle)
        val wm="[ IWX TEAM ]"
        canvas.drawText(wm,w/2f-pSub.measureText(wm)/2f,sy+bh-8f,pTitle)
    }

    private fun drawGlitchLines(canvas: Canvas, w: Float, h: Float) {
        repeat(Random.nextInt(2,5)){
            val y=Random.nextFloat()*h
            pGlitch.also{ it.alpha=Random.nextInt(20,90); it.strokeWidth=Random.nextFloat()*4f+1f; it.style=Paint.Style.STROKE }
            canvas.drawLine(0f,y,w*Random.nextFloat(),y,pGlitch)
        }
        pGlitch.style=Paint.Style.FILL; pGlitch.strokeWidth=0f
    }

    private fun rCh()=charset[Random.nextInt(charset.length)].toString()

    fun stop(){ handler.removeCallbacksAndMessages(null) }
    override fun onDetachedFromWindow(){ super.onDetachedFromWindow(); stop() }
}
