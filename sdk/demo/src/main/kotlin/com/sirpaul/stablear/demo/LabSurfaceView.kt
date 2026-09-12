package com.sirpaul.stablear.demo

import android.content.Context
import android.opengl.GLSurfaceView
import android.view.MotionEvent

/** Touch and accessibility actions share the same displayed-frame placement path. */
class LabSurfaceView(context: Context,private val place: (Float,Float)->Unit): GLSurfaceView(context) {
    private var touch: Pair<Float,Float>?=null
    init { isClickable=true; contentDescription=context.getString(R.string.camera_accessibility) }
    override fun onTouchEvent(event: MotionEvent): Boolean {
        when(event.actionMasked) {
            MotionEvent.ACTION_UP -> { touch=Pair(event.x,event.y); performClick() }
            MotionEvent.ACTION_CANCEL -> touch=null
        }
        return true
    }
    override fun performClick(): Boolean {
        super.performClick()
        val p=touch ?: Pair(width/2f,height/2f)
        touch=null; place(p.first,p.second); return true
    }
}
