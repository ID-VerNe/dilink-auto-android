package com.dilinkauto.vdserver

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.opengl.*
import android.os.Bundle
import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent
import android.view.Surface
import com.dilinkauto.protocol.*
import java.lang.reflect.Method
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.nio.channels.SocketChannel
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.locks.LockSupport

/**
 * EGL + GLES2 pipeline that renders the VD's SurfaceTexture into the
 * MediaCodec encoder's input surface.
 *
 * All EGL/GL state lives on the pipeline thread — created in
 * [initEglAndSurfaceTexture], used in [pipelineLoop], and torn down by that same
 * thread (audit S-M8: cross-thread eglDestroy* on a context made current elsewhere
 * is a crash, not a leak). [cleanup] is what the pipeline thread's finally calls. The pipeline samples the SurfaceTexture at [fps] (paced by
 * [frameIntervalNanos]) and writes each encoded frame to the car video
 * channel. Adaptive bitrate drops bandwidth when the car's socket backs up
 * and recovers it after a sustained clean interval.
 *
 * Extracted from [PipelineServer] to isolate the GL/encode hot path
 * (THREAD_PRIORITY_URGENT_DISPLAY) from the connection lifecycle.
 */
internal class GlPipeline(
    private val displayWidth: Int,
    private val displayHeight: Int,
    private val encodeWidth: Int,
    private val encodeHeight: Int,
    private val fps: Int,
    private val frameIntervalNanos: Long,
    private val bitrate: Int
) {
    private var stTexId = 0
    private var eglDisplay: EGLDisplay? = null
    private var eglContext: EGLContext? = null
    private var eglSurface: EGLSurface? = null
    private var glProgram = 0
    private var glPosLoc = 0
    private var glTexLoc = 0
    private var quadBuf: FloatBuffer? = null

    private var vdInputSurface: Surface? = null
    private var stTexture: android.graphics.SurfaceTexture? = null

    /** S-M8: guards [cleanup] so EGL handles are destroyed at most once. */
    private val glTornDown = AtomicBoolean(false)

    /** The encoder surface, supplied by the caller before [initEglAndSurfaceTexture]. */
    var encoderSurface: Surface? = null

    /** VD input surface, ready after [initEglAndSurfaceTexture] returns. */
    fun vdSurface(): Surface? = vdInputSurface

    // Logging goes straight to the PipeLog singleton — no local alias (audit R3-DRY-22③).

    // Write-loop policy (5s deadline + 100us backoff) now comes from FrameCodec.

    /**
     * Initialize EGL/GLES on the current thread, create the SurfaceTexture
     * and its GL texture, and the VD input surface. Must be called from the
     * pipeline thread.
     */
    fun initEglAndSurfaceTexture() {
        val display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
        val ver = IntArray(2); EGL14.eglInitialize(display, ver, 0, ver, 1)
        val cfgA = intArrayOf(EGL14.EGL_RED_SIZE, 8, EGL14.EGL_GREEN_SIZE, 8, EGL14.EGL_BLUE_SIZE, 8, EGL14.EGL_ALPHA_SIZE, 8, EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT, EGL14.EGL_SURFACE_TYPE, EGL14.EGL_WINDOW_BIT, EGL14.EGL_NONE)
        val cfgs = arrayOfNulls<EGLConfig>(1); val nc = IntArray(1)
        EGL14.eglChooseConfig(display, cfgA, 0, cfgs, 0, 1, nc, 0)
        val ctx = EGL14.eglCreateContext(display, cfgs[0], EGL14.EGL_NO_CONTEXT, intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE), 0)
        val surf = EGL14.eglCreateWindowSurface(display, cfgs[0], encoderSurface, intArrayOf(EGL14.EGL_NONE), 0)
        EGL14.eglMakeCurrent(display, surf, surf, ctx)
        eglDisplay = display; eglContext = ctx; eglSurface = surf

        // GL program
        glProgram = createProgram(); GLES20.glUseProgram(glProgram)
        glPosLoc = GLES20.glGetAttribLocation(glProgram, "aPosition")
        glTexLoc = GLES20.glGetAttribLocation(glProgram, "aTexCoord")
        GLES20.glViewport(0, 0, encodeWidth, encodeHeight)

        // Create GL texture for SurfaceTexture (VD input)
        val texIds = IntArray(1); GLES20.glGenTextures(1, texIds, 0); stTexId = texIds[0]
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, stTexId)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        stTexture = android.graphics.SurfaceTexture(stTexId)
        stTexture!!.setDefaultBufferSize(displayWidth, displayHeight)
        vdInputSurface = Surface(stTexture)

        // Fullscreen quad
        val quad = floatArrayOf(-1f, -1f, 0f, 1f, 1f, -1f, 1f, 1f, -1f, 1f, 0f, 0f, 1f, 1f, 1f, 0f)
        quadBuf = ByteBuffer.allocateDirect(quad.size * 4).order(ByteOrder.nativeOrder()).asFloatBuffer()
        quadBuf!!.put(quad).position(0)

        PipeLog.log("EGL/GL ready, VD input surface created")
    }

    /**
     * Render loop: pace at [frameIntervalNanos], drain the SurfaceTexture,
     * draw the fullscreen quad, swap, and ship each encoded frame to the car.
     * Returns when [running] turns false.
     */
    fun pipelineLoop(encoder: MediaCodec, carVideo: SocketChannel, running: () -> Boolean) {
        val st = stTexture ?: return
        val bufInfo = MediaCodec.BufferInfo()

        // Frame sync
        val frameLock = Any(); val frameAvail = booleanArrayOf(false)
        val cbThread = android.os.HandlerThread("PipeCB").apply { start() }
        st.setOnFrameAvailableListener({
            synchronized(frameLock) { frameAvail[0] = true; (frameLock as java.lang.Object).notifyAll() }
        }, android.os.Handler(cbThread.looper))

        var nextFrameNanos = System.nanoTime()
        var frameCount = 0L; var keyFrameCount = 0L; var lastLogAt = 0L
        val adaptive = AdaptiveBitrate(bitrate)
        PipeLog.log("Pipeline: ${encodeWidth}x${encodeHeight} ${fps}fps ${bitrate/1_000_000}Mbps")

        try {
            while (running()) {
                val waitNs = nextFrameNanos - System.nanoTime()
                if (waitNs > 0) LockSupport.parkNanos(waitNs)
                nextFrameNanos += frameIntervalNanos
                if (nextFrameNanos <= System.nanoTime()) nextFrameNanos = System.nanoTime() + frameIntervalNanos

                val hasNew: Boolean; synchronized(frameLock) { hasNew = frameAvail[0]; frameAvail[0] = false }
                if (hasNew) st.updateTexImage()

                GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
                GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, stTexId)
                val qb = quadBuf!!; qb.position(0)
                GLES20.glVertexAttribPointer(glPosLoc, 2, GLES20.GL_FLOAT, false, 16, qb); GLES20.glEnableVertexAttribArray(glPosLoc)
                qb.position(2); GLES20.glVertexAttribPointer(glTexLoc, 2, GLES20.GL_FLOAT, false, 16, qb); GLES20.glEnableVertexAttribArray(glTexLoc)
                GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
                EGL14.eglSwapBuffers(eglDisplay, eglSurface)

                var drained = 0
                while (true) {
                    val idx = encoder.dequeueOutputBuffer(bufInfo, 0)
                    if (idx < 0) break
                    if (idx >= 0) {
                        val buf = encoder.getOutputBuffer(idx)
                        if (buf != null && bufInfo.size > 0) {
                            val payload = ByteArray(bufInfo.size); buf.get(payload)
                            val isConfig = (bufInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0
                            val msgType = if (isConfig) VideoMsg.CONFIG else VideoMsg.FRAME
                            if ((bufInfo.flags and MediaCodec.BUFFER_FLAG_KEY_FRAME) != 0) keyFrameCount++
                            val ws = System.nanoTime()
                            writeFrame(carVideo, msgType, payload)
                            val wm = System.nanoTime() - ws
                            if (adaptive.onFrameWritten(wm)) applyBitrate(encoder, adaptive.currentBitrate)
                            if (adaptive.needsSyncFrame) { requestSyncFrame(encoder); adaptive.onBitrateApplied() }
                            drained++; frameCount++
                        }
                        encoder.releaseOutputBuffer(idx, false)
                    }
                }
                if (frameCount - lastLogAt >= 120) { lastLogAt = frameCount; PipeLog.log("Pipeline: $frameCount frames ${adaptive.currentBitrate/1_000_000}Mbps keys=$keyFrameCount") }
            }
        } finally {
            // cbThread is non-daemon; if writeFrame throws or GL faults, the
            // parked Looper would prevent a clean JVM exit. quitSafely in finally.
            cbThread.quitSafely()
        }
        PipeLog.log("Pipeline exited: $frameCount frames")
    }

    /**
     * Frame header layout and write deadline live in [FrameCodec] (protocol-core).
     *
     * This used to be a hand-rolled copy with a comment claiming vd-server could
     * not reach the protocol module. That was false — vd-server already depends on
     * :protocol, which re-exports :protocol-core via `api(...)`, and this file has
     * imported `com.dilinkauto.protocol.*` all along (see docs/audit-srp-dry.md
     * DRY-3). One definition now, so a protocol change cannot silently diverge.
     */
    private fun writeFrame(ch: SocketChannel, msgType: Byte, payload: ByteArray) {
        FrameCodec.writeFrameToChannel(ch, FrameCodec.Frame(Channel.VIDEO, msgType, payload))
    }
    private fun applyBitrate(enc: MediaCodec, br: Int) { try { val p = Bundle(); p.putInt(MediaCodec.PARAMETER_KEY_VIDEO_BITRATE, br); enc.setParameters(p) } catch (_: Exception) {} }
    private fun requestSyncFrame(enc: MediaCodec) { try { val p = Bundle(); p.putInt(MediaCodec.PARAMETER_KEY_REQUEST_SYNC_FRAME, 0); enc.setParameters(p) } catch (_: Exception) {} }

    private fun createProgram(): Int { val vs = loadShader(GLES20.GL_VERTEX_SHADER, "attribute vec4 aPosition;attribute vec2 aTexCoord;varying vec2 vTexCoord;void main(){gl_Position=aPosition;vTexCoord=aTexCoord;}"); val fs = loadShader(GLES20.GL_FRAGMENT_SHADER, "#extension GL_OES_EGL_image_external:require\nprecision mediump float;varying vec2 vTexCoord;uniform samplerExternalOES sTexture;void main(){gl_FragColor=texture2D(sTexture,vTexCoord);}"); return GLES20.glCreateProgram().also { GLES20.glAttachShader(it, vs); GLES20.glAttachShader(it, fs); GLES20.glLinkProgram(it) } }
    private fun loadShader(type: Int, src: String): Int = GLES20.glCreateShader(type).also { GLES20.glShaderSource(it, src); GLES20.glCompileShader(it) }

    /**
     * Tear down EGL/GL resources. Idempotent.
     *
     * S-M8: must be called from the Pipeline thread — the EGL context was made current
     * there in [initEglAndSurfaceTexture], and the encoder may still be running its
     * native path. `PipelineServer.cleanup()` therefore never calls this directly; it
     * signals the pipeline thread to exit and this runs in that thread's own finally
     * (see `PipelineServer.releaseGlResources`). The guard below keeps a double call —
     * pipeline-thread finally plus a late stray caller — from destroying handles twice.
     */
    fun cleanup() {
        if (!glTornDown.compareAndSet(false, true)) return
        val d = eglDisplay; val s = eglSurface; val c = eglContext
        if (d != null) EGL14.eglMakeCurrent(d, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
        if (d != null && s != null) EGL14.eglDestroySurface(d, s)
        if (d != null && c != null) EGL14.eglDestroyContext(d, c)
        eglDisplay = null; eglSurface = null; eglContext = null
        vdInputSurface?.release(); vdInputSurface = null
        stTexture?.release(); stTexture = null
    }
}
