package com.dilinkauto.server.ui.screen

import android.graphics.SurfaceTexture
import android.view.MotionEvent
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.View
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.viewinterop.AndroidView
import com.dilinkauto.protocol.InputMsg
import com.dilinkauto.protocol.TouchEvent
import com.dilinkauto.server.service.CarConnectionService

/**
 * Mirror content — SurfaceView for video + touch forwarding.
 *
 * SurfaceView (not TextureView) so the decoder's output goes through a hardware
 * overlay instead of an extra per-frame GL composite pass. On the Adreno 505 the
 * TextureView composite cost (~2-5ms/frame at 1280x800) is a meaningful slice of
 * the 42ms budget at 24fps; SurfaceView bypasses it entirely.
 *
 * Tradeoff: SurfaceView destroys its surface when the view goes INVISIBLE, where
 * TextureView kept it alive. Navigation HOME<->APP toggles `visible`, so
 * surfaceCreated/surfaceDestroyed fire on each switch. The decoder is NOT stopped
 * on surfaceDestroyed — it stays running, and [VideoDecoder.invalidateSurface]
 * gates the render flag off so frames aren't dropped to a destroyed surface.
 * surfaceCreated calls switchSurface (setOutputSurface) to re-attach, restoring
 * rendering with zero keyframe loss. handleDisconnect/shutdown owns the final
 * decoder stop.
 *
 * Both callbacks are deliberately thin (audit S-M3): they forward to
 * `CarConnectionService.onMirrorSurfaceCreated/Destroyed`, which hand the work
 * to a service-side single-thread executor — MediaCodec create/configure must
 * not run on the main thread that delivers these callbacks.
 *
 * The persistent nav bar is a sibling (not overlapping) this composable in both
 * landscape and portrait layouts (MainActivity CarShell), so SurfaceView's
 * default z-order — surface below the window hierarchy — renders the nav bar
 * above the video correctly.
 */
@Composable
fun MirrorContent(service: CarConnectionService, visible: Boolean = true) {
    AndroidView(
        factory = { context ->
            SurfaceView(context).apply {
                holder.addCallback(object : SurfaceHolder.Callback {
                    override fun surfaceCreated(holder: SurfaceHolder) {
                        // Deliberately thin (audit S-M3): the callback fires on the
                        // main thread and MediaCodec create/configure is expensive
                        // enough to be visible as jank on the 8x A53 head unit.
                        // The service hands the Surface to its single decoder
                        // thread, which owns start/switchSurface.
                        service.onMirrorSurfaceCreated(holder.surface)
                    }

                    override fun surfaceChanged(
                        holder: SurfaceHolder,
                        format: Int,
                        width: Int,
                        height: Int
                    ) {
                        // No-op — the decoder renders at fixed vdWidth x vdHeight;
                        // the SurfaceView scales the buffer to the view size.
                    }

                    override fun surfaceDestroyed(holder: SurfaceHolder) {
                        // Surface is gone (view went INVISIBLE or activity teardown).
                        // Do NOT stop the decoder — navigation between HOME and APP
                        // toggles visibility, and stopping/restarting the codec would
                        // drop the keyframe cache and require a fresh IDR. Just gate
                        // the render flag off; surfaceCreated will re-attach a new
                        // surface via setOutputSurface.
                        service.onMirrorSurfaceDestroyed()
                    }
                })

                setOnTouchListener { view, event ->
                    when (event.actionMasked) {
                        MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> {
                            val idx = event.actionIndex
                            service.sendTouchEvent(TouchEvent(
                                action = InputMsg.TOUCH_DOWN,
                                pointerId = event.getPointerId(idx),
                                x = event.getX(idx) / view.width,
                                y = event.getY(idx) / view.height,
                                pressure = event.getPressure(idx),
                                timestamp = event.eventTime
                            ))
                        }
                        MotionEvent.ACTION_MOVE -> {
                            // BatchALL active pointers into one message (reduces syscalls for multi-touch)
                            val pointers = (0 until event.pointerCount).map { i ->
                                TouchEvent(
                                    action = InputMsg.TOUCH_MOVE,
                                    pointerId = event.getPointerId(i),
                                    x = event.getX(i) / view.width,
                                    y = event.getY(i) / view.height,
                                    pressure = event.getPressure(i),
                                    timestamp = event.eventTime
                                )
                            }
                            service.sendTouchBatch(pointers)
                        }
                        MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP -> {
                            val idx = event.actionIndex
                            service.sendTouchEvent(TouchEvent(
                                action = InputMsg.TOUCH_UP,
                                pointerId = event.getPointerId(idx),
                                x = event.getX(idx) / view.width,
                                y = event.getY(idx) / view.height,
                                pressure = event.getPressure(idx),
                                timestamp = event.eventTime
                            ))
                        }
                        MotionEvent.ACTION_CANCEL -> {
                            // Gesture cancelled — release ALL pointers to prevent ghost fingers
                            for (i in 0 until event.pointerCount) {
                                service.sendTouchEvent(TouchEvent(
                                    action = InputMsg.TOUCH_UP,
                                    pointerId = event.getPointerId(i),
                                    x = event.getX(i) / view.width,
                                    y = event.getY(i) / view.height,
                                    pressure = 0f,
                                    timestamp = event.eventTime
                                ))
                            }
                        }
                        else -> return@setOnTouchListener false
                    }
                    true
                }
            }
        },
        update = { view ->
            view.visibility = if (visible) View.VISIBLE else View.INVISIBLE
        },
        modifier = Modifier
            .fillMaxSize()
    )
}
