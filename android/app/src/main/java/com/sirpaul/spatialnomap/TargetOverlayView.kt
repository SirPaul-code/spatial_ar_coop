package com.sirpaul.spatialnomap

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.view.View

class TargetOverlayView(context: Context) : View(context) {
    private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 7f
        color = 0xffff3b30.toInt()
    }
    private val dot = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = 0xffff3b30.toInt()
    }
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xffffffff.toInt()
        textSize = 34f
        setShadowLayer(4f, 1f, 1f, 0xff000000.toInt())
    }

    @Volatile private var px: Float? = null
    @Volatile private var py: Float? = null
    @Volatile private var label: String = ""

    fun setTarget(x: Float?, y: Float?, detail: String = "") {
        px = x
        py = y
        label = detail
        postInvalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val x = px ?: return
        val y = py ?: return
        val clampedX = x.coerceIn(28f, width - 28f)
        val clampedY = y.coerceIn(28f, height - 28f)
        canvas.drawCircle(clampedX, clampedY, 28f, ring)
        canvas.drawCircle(clampedX, clampedY, 5f, dot)
        if (label.isNotBlank()) canvas.drawText(label, (clampedX + 38f).coerceAtMost(width - 260f), (clampedY - 30f).coerceAtLeast(42f), text)
    }
}
