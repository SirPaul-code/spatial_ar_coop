package com.sirpaul.showme

import android.opengl.EGL14
import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.view.Surface
import com.google.ar.core.Camera
import com.google.ar.core.Coordinates2d
import com.google.ar.core.Frame
import org.webrtc.EglBase
import org.webrtc.SurfaceTextureHelper
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.*

/**
 * GPU camera texture -> shared EGL surface -> WebRTC hardware encoder.
 * No Bitmap, JPEG, glReadPixels or second Camera2 session in the live path.
 * The camera is rendered upright; the identity footer is never user-visible.
 * All methods except the texture listener run on the AR GL thread.
 */
class RtcVideoPipe(private val call: RtcVoice, rawWidth: Int, rawHeight: Int, private val rotation: Int) {
    val videoWidth: Int
    val contentHeight: Int
    val videoHeight: Int
    private val egl = EglBase.createEgl14(EGL14.eglGetCurrentContext(), EglBase.CONFIG_RECORDABLE)
    private val helper = checkNotNull(SurfaceTextureHelper.create("ShowMe-video-textures", egl.eglBaseContext, true))
    private val surface: Surface
    private var program = 0
    private var stampProgram = 0
    private var stampTexture = 0
    private val stampPixels=ByteBuffer.allocateDirect(64*2)
    private val flatUv=buffer(floatArrayOf(0f,0f,1f,0f,0f,1f,1f,1f))
    private val vertices = buffer(floatArrayOf(-1f,-1f,1f,-1f,-1f,1f,1f,1f))
    private val uv = buffer(FloatArray(8))
    private var lastTimestamp = -1L
    private var nextDueNs = 0L

    init {
        val uprightW = if (rotation == 90 || rotation == 270) rawHeight else rawWidth
        val uprightH = if (rotation == 90 || rotation == 270) rawWidth else rawHeight
        val scale = min(1.0, min(720.0 / uprightW, 1280.0 / uprightH))
        videoWidth = ((uprightW * scale).roundToInt() / 16 * 16).coerceAtLeast(160)
        contentHeight = ((videoWidth.toDouble() * uprightH / uprightW).roundToInt() / 16 * 16).coerceAtLeast(160)
        videoHeight = contentHeight + VideoFrameStamp.FOOTER_HEIGHT
        helper.setTextureSize(videoWidth, videoHeight)
        helper.setFrameRotation(0)
        surface = Surface(helper.surfaceTexture)
        egl.createSurface(surface)
        call.attachVideoContext(egl.eglBaseContext, videoWidth, videoHeight, contentHeight, rawWidth, rawHeight, rotation)
        helper.startListening { frame -> call.onVideoFrame(frame) }
    }

    fun isDue(timestamp: Long, nowNs: Long): Boolean = timestamp != lastTimestamp && nowNs >= nextDueNs && !helper.isTextureInUse

    fun draw(frame: Frame, camera: Camera, texture: Int, id: Long, epoch: Int) {
        val now = System.nanoTime()
        lastTimestamp = frame.timestamp
        // Keep the cadence phase; 33.3-ms sensor jitter must not halve a 30-fps feed.
        nextDueNs = if (nextDueNs == 0L || now - nextDueNs > 100_000_000L) now + 32_000_000L else nextDueNs + 32_000_000L
        val dims = camera.imageIntrinsics.imageDimensions
        val raw = FloatArray(8)
        val upright = floatArrayOf(0f,1f,1f,1f,0f,0f,1f,0f)
        repeat(4) { i ->
            val p = ShowMeGeometry.uprightToRaw(upright[i*2], upright[i*2+1], rotation)
            raw[i*2] = p[0] * dims[0]; raw[i*2+1] = p[1] * dims[1]
        }
        val textureCoords = FloatArray(8)
        frame.transformCoordinates2d(Coordinates2d.IMAGE_PIXELS, raw, Coordinates2d.TEXTURE_NORMALIZED, textureCoords)
        uv.clear(); uv.put(textureCoords); uv.position(0)
        val display = EGL14.eglGetCurrentDisplay()
        val context = EGL14.eglGetCurrentContext()
        val drawSurface = EGL14.eglGetCurrentSurface(EGL14.EGL_DRAW)
        val readSurface = EGL14.eglGetCurrentSurface(EGL14.EGL_READ)
        GLES20.glFlush()
        try {
            egl.makeCurrent()
            if (program == 0) program = makeProgram()
            GLES20.glDisable(GLES20.GL_DEPTH_TEST)
            GLES20.glDisable(GLES20.GL_BLEND)
            GLES20.glDisable(GLES20.GL_SCISSOR_TEST)
            GLES20.glClearColor(0f,0f,0f,1f)
            GLES20.glViewport(0,0,videoWidth,videoHeight)
            GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
            GLES20.glViewport(0,VideoFrameStamp.FOOTER_HEIGHT,videoWidth,contentHeight)
            GLES20.glUseProgram(program)
            val position = GLES20.glGetAttribLocation(program,"aPosition")
            val tex = GLES20.glGetAttribLocation(program,"aUv")
            GLES20.glEnableVertexAttribArray(position); GLES20.glEnableVertexAttribArray(tex)
            GLES20.glVertexAttribPointer(position,2,GLES20.GL_FLOAT,false,0,vertices)
            GLES20.glVertexAttribPointer(tex,2,GLES20.GL_FLOAT,false,0,uv)
            GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
            GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES,texture)
            GLES20.glUniform1i(GLES20.glGetUniformLocation(program,"cameraTexture"),0)
            GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP,0,4)
            GLES20.glDisableVertexAttribArray(position); GLES20.glDisableVertexAttribArray(tex)
            drawStamp(id,epoch)
            egl.swapBuffers(now)
        } finally {
            check(EGL14.eglMakeCurrent(display,drawSurface,readSurface,context)) { "Could not restore AR EGL context" }
        }
    }
    /** One small texture upload + draw replaces 128 scissor/glClear operations per video frame. */
    private fun drawStamp(id: Long, epoch: Int) {
        if(stampProgram==0)stampProgram=makeProgram(false)
        if(stampTexture==0) {
            val ids=IntArray(1);GLES20.glGenTextures(1,ids,0);stampTexture=ids[0]
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D,stampTexture)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D,GLES20.GL_TEXTURE_MIN_FILTER,GLES20.GL_NEAREST)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D,GLES20.GL_TEXTURE_MAG_FILTER,GLES20.GL_NEAREST)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D,GLES20.GL_TEXTURE_WRAP_S,GLES20.GL_CLAMP_TO_EDGE)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D,GLES20.GL_TEXTURE_WRAP_T,GLES20.GL_CLAMP_TO_EDGE)
            GLES20.glTexImage2D(GLES20.GL_TEXTURE_2D,0,GLES20.GL_LUMINANCE,64,2,0,GLES20.GL_LUMINANCE,GLES20.GL_UNSIGNED_BYTE,null)
        }
        val stamp=VideoFrameStamp.bytes(id,epoch)
        stampPixels.clear()
        for(row in 0..1)for(cell in 0 until 64)stampPixels.put(if(VideoFrameStamp.bit(stamp,cell) xor (row==0))(-1).toByte() else 0.toByte())
        stampPixels.flip()
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D,stampTexture)
        GLES20.glTexSubImage2D(GLES20.GL_TEXTURE_2D,0,0,0,64,2,GLES20.GL_LUMINANCE,GLES20.GL_UNSIGNED_BYTE,stampPixels)
        GLES20.glViewport(0,0,videoWidth,VideoFrameStamp.FOOTER_HEIGHT)
        GLES20.glUseProgram(stampProgram)
        val position=GLES20.glGetAttribLocation(stampProgram,"aPosition")
        val tex=GLES20.glGetAttribLocation(stampProgram,"aUv")
        GLES20.glEnableVertexAttribArray(position);GLES20.glEnableVertexAttribArray(tex)
        GLES20.glVertexAttribPointer(position,2,GLES20.GL_FLOAT,false,0,vertices)
        GLES20.glVertexAttribPointer(tex,2,GLES20.GL_FLOAT,false,0,flatUv)
        GLES20.glUniform1i(GLES20.glGetUniformLocation(stampProgram,"cameraTexture"),0)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP,0,4)
        GLES20.glDisableVertexAttribArray(position);GLES20.glDisableVertexAttribArray(tex)
    }
    fun close() {
        helper.stopListening()
        helper.dispose()
        // egl.release() may unbind this thread; always preserve the AR context.
        val display=EGL14.eglGetCurrentDisplay(); val context=EGL14.eglGetCurrentContext()
        val draw=EGL14.eglGetCurrentSurface(EGL14.EGL_DRAW); val read=EGL14.eglGetCurrentSurface(EGL14.EGL_READ)
        try { egl.makeCurrent(); if(program!=0)GLES20.glDeleteProgram(program); if(stampProgram!=0)GLES20.glDeleteProgram(stampProgram); if(stampTexture!=0)GLES20.glDeleteTextures(1,intArrayOf(stampTexture),0); egl.release(); surface.release() }
        finally { EGL14.eglMakeCurrent(display,draw,read,context) }
    }
    private fun makeProgram(camera: Boolean = true): Int {
        fun shader(type: Int, source: String): Int {
            val shader=GLES20.glCreateShader(type)
            GLES20.glShaderSource(shader,source); GLES20.glCompileShader(shader)
            val status=IntArray(1); GLES20.glGetShaderiv(shader,GLES20.GL_COMPILE_STATUS,status,0)
            check(status[0]!=0) { GLES20.glGetShaderInfoLog(shader) }
            return shader
        }
        val vertex=shader(GLES20.GL_VERTEX_SHADER,"attribute vec2 aPosition; attribute vec2 aUv; varying vec2 vUv; void main(){ gl_Position=vec4(aPosition,0.0,1.0); vUv=aUv; }")
        val fragment=shader(GLES20.GL_FRAGMENT_SHADER,if(camera) "#extension GL_OES_EGL_image_external : require\nprecision mediump float; varying vec2 vUv; uniform samplerExternalOES cameraTexture; void main(){ gl_FragColor=texture2D(cameraTexture,vUv); }"
            else "precision mediump float; varying vec2 vUv; uniform sampler2D cameraTexture; void main(){ gl_FragColor=texture2D(cameraTexture,vUv); }")
        val program=GLES20.glCreateProgram(); GLES20.glAttachShader(program,vertex); GLES20.glAttachShader(program,fragment); GLES20.glLinkProgram(program)
        GLES20.glDeleteShader(vertex); GLES20.glDeleteShader(fragment)
        val status=IntArray(1); GLES20.glGetProgramiv(program,GLES20.GL_LINK_STATUS,status,0)
        check(status[0]!=0) { GLES20.glGetProgramInfoLog(program) }
        return program
    }
    private fun buffer(data: FloatArray) = ByteBuffer.allocateDirect(data.size*4).order(ByteOrder.nativeOrder()).asFloatBuffer().apply { put(data); position(0) }
}
