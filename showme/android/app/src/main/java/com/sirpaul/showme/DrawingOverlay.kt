package com.sirpaul.showme

import android.content.Context
import android.graphics.*
import android.view.View
import kotlin.math.*

data class OverlayDrawing(val id:String,val tool:String,val color:String,val label:String,val points:List<PointF?>,val verified:Boolean)
class DrawingOverlay(context:Context):View(context) {
    @Volatile private var items:List<OverlayDrawing> = emptyList()
    private val density=resources.displayMetrics.density
    private val paint=Paint(Paint.ANTI_ALIAS_FLAG).apply { strokeCap=Paint.Cap.ROUND; strokeJoin=Paint.Join.ROUND }
    private val text=Paint(Paint.ANTI_ALIAS_FLAG).apply { color=Color.WHITE; textSize=13*density; typeface=Typeface.create("sans-serif-medium",Typeface.NORMAL) }
    fun update(drawings:List<OverlayDrawing>) { items=drawings; postInvalidateOnAnimation() }
    override fun onDraw(canvas:Canvas) {
        super.onDraw(canvas)
        for(item in items) {
            val points=item.points; val first=points.firstOrNull { it!=null } ?: continue
            val color=accent(item.color)
            paint.color=color; paint.style=Paint.Style.STROKE; paint.strokeWidth=3*density
            if(item.tool=="pin" || item.tool=="pointer") {
                val p=first; paint.alpha=60; paint.strokeWidth=8*density; canvas.drawCircle(p.x,p.y,16*density,paint)
                paint.alpha=255; paint.strokeWidth=2.5f*density; canvas.drawCircle(p.x,p.y,12*density,paint)
                paint.style=Paint.Style.FILL; canvas.drawCircle(p.x,p.y,3*density,paint)
            } else {
                val path=Path(); var started=false
                points.forEach { p -> if(p==null) started=false else if(!started) { path.moveTo(p.x,p.y); started=true } else path.lineTo(p.x,p.y) }
                paint.color=Color.BLACK; paint.alpha=110; paint.strokeWidth=7*density; canvas.drawPath(path,paint)
                paint.color=color; paint.alpha=255; paint.strokeWidth=3*density; canvas.drawPath(path,paint)
            }
            val label=item.label.ifBlank { if(item.tool=="pin") "POINT ${item.id.takeLast(3)}" else "" }
            if(label.isNotBlank()) {
                val display=label.take(42)+(if(item.verified) "  / VERIFIED" else "")
                val w=min(text.measureText(display)+24*density,width-16*density)
                val left=(first.x+24*density).coerceIn(8*density,max(8*density,width-w-8*density))
                val top=(first.y-18*density).coerceIn(8*density,max(8*density,height-38*density))
                paint.style=Paint.Style.FILL; paint.color=0xe5121c28.toInt(); paint.alpha=240
                canvas.drawRoundRect(left,top,left+w,top+34*density,10*density,10*density,paint)
                canvas.save(); canvas.clipRect(left,top,left+w,top+34*density)
                canvas.drawText(display,left+12*density,top+22*density,text); canvas.restore()
            }
        }
    }
    companion object {
        fun accent(name:String):Int=when(name) { "amber" -> 0xffffd083.toInt(); "coral" -> 0xffff8c8c.toInt(); "blue" -> 0xff91baff.toInt(); else -> 0xff70e4cf.toInt() }
    }
}
