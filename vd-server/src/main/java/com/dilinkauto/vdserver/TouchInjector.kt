package com.dilinkauto.vdserver

import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent
import com.dilinkauto.protocol.*
import java.io.OutputStream
import java.lang.reflect.Method

/**
 * Touch injection into the phone's VirtualDisplay via the InputManager service.
 *
 * Built once per server lifetime; [handleTouchFrame] decodes incoming touch
 * frames and routes them to [injectTouch], which either uses the InputManager
 * reflection path (preferred, ~zero latency) or the `input -d` shell fallback
 * when the reflection path is unavailable.
 *
 * Multi-touch state ([activePointers], [propsPool], [coordsPool]) lives here
 * so the input side stays isolated from the GL/encode pipeline.
 *
 * Extracted from [PipelineServer].
 */
internal class TouchInjector(
    private val displayWidth: Int,
    private val displayHeight: Int,
    private val displayIdProvider: () -> Int,
    private val shellProvider: () -> OutputStream?,
    private val logErr: (String) -> Unit
) {
    private var inputManager: Any? = null
    private var injectInputEventMethod: Method? = null
    private var setDisplayIdMethod: Method? = null
    private val activePointers = LinkedHashMap<Int, FloatArray>()
    private val propsPool = Array(MAX_POINTERS) { MotionEvent.PointerProperties() }
    private val coordsPool = Array(MAX_POINTERS) { MotionEvent.PointerCoords() }
    private var touchDownTime = 0L

    /** Whether the InputManager reflection path is wired and ready. */
    val hasInjection: Boolean get() = inputManager != null && injectInputEventMethod != null

    /**
     * Reflect into the InputManager service. Failure is non-fatal — touch
     * falls back to `input -d` shell taps. Safe to call once at startup.
     */
    fun initInputManager() {
        try {
            val sm = Class.forName("android.os.ServiceManager")
            val getService = sm.getDeclaredMethod("getService", String::class.java)
            val binder = getService.invoke(null, "input") as android.os.IBinder
            val stub = Class.forName("android.hardware.input.IInputManager\$Stub")
            inputManager = stub.getDeclaredMethod("asInterface", android.os.IBinder::class.java).invoke(null, binder)
            injectInputEventMethod = inputManager!!.javaClass.getMethod("injectInputEvent", android.view.InputEvent::class.java, Int::class.javaPrimitiveType)
            try { setDisplayIdMethod = MotionEvent::class.java.getDeclaredMethod("setDisplayId", Int::class.javaPrimitiveType) } catch (_: Exception) {}
            PipeLog.log("InputManager ready")
        } catch (e: Exception) { logErr("InputManager: ${e.message}") }
    }

    /** Decode a touch frame and inject it. */
    fun handleTouchFrame(f: FrameCodec.Frame) {
        when (f.messageType) {
            InputMsg.TOUCH_MOVE_BATCH -> { val b = TouchMoveBatch.decode(f.payload); for (p in b.pointers) injectTouch(1, p.pointerId, (p.x * displayWidth).toInt(), (p.y * displayHeight).toInt(), p.pressure) }
            InputMsg.TOUCH_DOWN, InputMsg.TOUCH_MOVE, InputMsg.TOUCH_UP -> { val e = TouchEvent.decode(f.payload); injectTouch(when(f.messageType){InputMsg.TOUCH_DOWN->0;InputMsg.TOUCH_MOVE->1;else->2}, e.pointerId, (e.x*displayWidth).toInt(), (e.y*displayHeight).toInt(), e.pressure) }
        }
    }

    private fun injectTouch(action: Int, ptr: Int, x: Int, y: Int, pressure: Float) {
        val displayId = displayIdProvider()
        if (inputManager == null || injectInputEventMethod == null) { try { if (action == 0 || action == 2) execShell("input -d $displayId tap $x $y") } catch (_: Exception) {}; return }
        try {
            activePointers[ptr] = floatArrayOf(x.toFloat(), y.toFloat(), pressure)
            if (activePointers.isEmpty()) return
            val now = SystemClock.uptimeMillis()
            val pts = activePointers.entries.toList()
            val ptrIndex = pts.indexOfFirst { it.key == ptr }
            if (ptrIndex < 0) return

            for ((i, e) in pts.withIndex()) {
                val k = e.key
                val v = e.value
                propsPool[i] = (propsPool[i] ?: MotionEvent.PointerProperties()).also {
                    it.id = k
                    it.toolType = MotionEvent.TOOL_TYPE_FINGER
                }
                coordsPool[i] = (coordsPool[i] ?: MotionEvent.PointerCoords()).also {
                    it.x = v[0]
                    it.y = v[1]
                    it.pressure = v[2]
                    it.size = 1f
                }
            }

            val ma = when (action) {
                0 -> {
                    touchDownTime = now
                    if (pts.size == 1) MotionEvent.ACTION_DOWN
                    else MotionEvent.ACTION_POINTER_DOWN or (ptrIndex shl MotionEvent.ACTION_POINTER_INDEX_SHIFT)
                }
                2 -> {
                    if (pts.size == 1) MotionEvent.ACTION_UP
                    else MotionEvent.ACTION_POINTER_UP or (ptrIndex shl MotionEvent.ACTION_POINTER_INDEX_SHIFT)
                }
                else -> MotionEvent.ACTION_MOVE
            }

            val ev = MotionEvent.obtain(touchDownTime, now, ma, pts.size, propsPool, coordsPool, 0, 0, 1f, 1f, 0, 0, InputDevice.SOURCE_TOUCHSCREEN, 0)
            setDisplayIdMethod?.invoke(ev, displayId)
            injectInputEventMethod!!.invoke(inputManager, ev, 0)
            ev.recycle()

            if (action == 2) {
                activePointers.remove(ptr)
            }
        } catch (_: Exception) {}
    }

    private fun execShell(cmd: String) = ShellExec.execShell(shellProvider(), cmd)

    companion object { private const val MAX_POINTERS = 10 /* matches protocol TouchEvent encoder */ }
}
