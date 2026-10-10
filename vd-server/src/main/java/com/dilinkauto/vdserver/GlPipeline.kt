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
import java.util.concurrent.atomic.AtomicLong
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

    // ── Frame-arrival signal ──────────────────────────────────────────────
    //
    // 2026-10-10 实机缺陷（车机屏概率性全黑，~50%，MI 9 / Android 15）：
    // 帧到达回调曾注册在 pipelineLoop() 里，而 pipelineLoop 要等"客户端连接
    // + unpark"才开始 —— VD 创建 → 客户端连接之间 SurfaceFlinger 提交的所有
    // 帧（HOME 启动动画、随后的稳定画面）既不会触发回调、也不会被
    // updateTexImage 消费；buffer queue 被占满后 SF 的后续提交还会阻塞。
    // 结果 tex/cb 停在 0 或某个小值后永久静止、"最新纹理"停在某张陈旧或
    // 未初始化的黑帧上，编码器无限重编码黑帧 → 车机屏全黑。
    //
    // 现在回调在 initEglAndSurfaceTexture() 里注册（早于 VD 创建），VD 的
    // 第一帧起就有记录：pipelineLoop 启动时 flag 已累积置位，第一轮就
    // updateTexImage 取到"队列最新帧"（SF 的最后一次提交 = 当前稳定画面）。
    private val frameAvail = AtomicBoolean(false)
    private val frameCbCount = AtomicLong(0)
    private var cbThread: android.os.HandlerThread? = null

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

        // Register the frame-arrival listener NOW — before the VirtualDisplay
        // exists (PipelineServer creates it only after this method signals
        // ready). Must not live in pipelineLoop(): see the field comment above.
        cbThread = android.os.HandlerThread("PipeCB").apply { start() }
        stTexture!!.setOnFrameAvailableListener({
            frameCbCount.incrementAndGet()
            frameAvail.set(true)
        }, android.os.Handler(cbThread!!.looper))

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

        // Frame sync: the arrival listener was registered in
        // initEglAndSurfaceTexture (see the field comment there for why).
        // Frames that arrived while this loop was still parked waiting for the
        // car are already flagged — the first iteration below drains the flag,
        // so updateTexImage() picks the queue's most recent frame instead of a
        // stale/black one.
        var nextFrameNanos = System.nanoTime()
        var frameCount = 0L; var keyFrameCount = 0L; var lastLogAt = 0L
        var texUpdates = 0L; var swapFails = 0L
        val adaptive = AdaptiveBitrate(bitrate)
        PipeLog.log("Pipeline: ${encodeWidth}x${encodeHeight} ${fps}fps ${bitrate/1_000_000}Mbps")

        try {
            while (running()) {
                val waitNs = nextFrameNanos - System.nanoTime()
                if (waitNs > 0) LockSupport.parkNanos(waitNs)
                nextFrameNanos += frameIntervalNanos
                if (nextFrameNanos <= System.nanoTime()) nextFrameNanos = System.nanoTime() + frameIntervalNanos

                if (frameAvail.getAndSet(false)) { st.updateTexImage(); texUpdates++ }

                GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
                GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, stTexId)
                val qb = quadBuf!!; qb.position(0)
                GLES20.glVertexAttribPointer(glPosLoc, 2, GLES20.GL_FLOAT, false, 16, qb); GLES20.glEnableVertexAttribArray(glPosLoc)
                qb.position(2); GLES20.glVertexAttribPointer(glTexLoc, 2, GLES20.GL_FLOAT, false, 16, qb); GLES20.glEnableVertexAttribArray(glTexLoc)
                GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
                if (!EGL14.eglSwapBuffers(eglDisplay, eglSurface)) swapFails++

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
                if (frameCount - lastLogAt >= 120) {
                    lastLogAt = frameCount
                    PipeLog.log("Pipeline: $frameCount frames ${adaptive.currentBitrate/1_000_000}Mbps keys=$keyFrameCount tex=$texUpdates cb=${frameCbCount.get()} swapFails=$swapFails ts=${st.timestamp} glErr=${GLES20.glGetError()}")
                }
            }
        } finally {
            // cbThread is non-daemon; if writeFrame throws or GL faults, the
            // parked Looper would prevent a clean JVM exit. quitSafely in
            // finally — cleanup() also quits it as a fallback for the path
            // where this loop never ran (bind/accept failure).
            cbThread?.quitSafely()
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
        // Stop the frame-callback looper BEFORE releasing the SurfaceTexture it
        // may still be posting from. Fallback for the path where pipelineLoop
        // never ran (its own finally is the primary stopper); quitSafely is
        // idempotent, so the double call is safe.
        cbThread?.quitSafely(); cbThread = null
        vdInputSurface?.release(); vdInputSurface = null
        stTexture?.release(); stTexture = null
    }
}
