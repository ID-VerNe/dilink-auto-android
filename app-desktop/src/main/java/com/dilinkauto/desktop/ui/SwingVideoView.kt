package com.dilinkauto.desktop.ui

import java.awt.Color
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.RenderingHints
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.awt.event.MouseMotionAdapter
import java.awt.image.BufferedImage
import javax.swing.JComponent

/**
 * 视频画面组件：等比缩放（letterbox）绘制"最近一帧"解码图像，并把鼠标手势换算成
 * 归一化触摸坐标回调给上层。
 *
 * 职责边界（SRP）：只做"绘制 + 坐标换算"，不碰解码、网络与输入协议；
 * 帧由解码线程通过 [setFrame] 投递，手势通过构造参数的三个回调上报归一化坐标。
 * 放在 Compose `SwingPanel` 里使用——不每帧重建 Compose ImageBitmap（计划 6.3）。
 */
class SwingVideoView(
    private val onTouchDown: (Float, Float) -> Unit = { _, _ -> },
    private val onTouchMove: (Float, Float) -> Unit = { _, _ -> },
    private val onTouchUp: (Float, Float) -> Unit = { _, _ -> },
) : JComponent() {

    /** 最近一帧。volatile 引用交换，绘制只在 EDT 上发生。 */
    @Volatile
    private var frame: BufferedImage? = null

    /** 手势是否已开始（左键在内容区内按下）。 */
    private var dragging = false

    init {
        isOpaque = true
        background = Color.BLACK
        addMouseListener(object : MouseAdapter() {
            override fun mousePressed(e: MouseEvent) {
                if (e.button != MouseEvent.BUTTON1) return
                val n = normalizedOrNull(e.x, e.y) ?: return
                dragging = true
                onTouchDown(n.x, n.y)
            }

            override fun mouseReleased(e: MouseEvent) {
                if (e.button != MouseEvent.BUTTON1 || !dragging) return
                dragging = false
                // 抬起时即使指针已在黑边上也继续上报（clamp），避免手机侧手指卡住。
                val n = normalizedClamped(e.x, e.y)
                onTouchUp(n.x, n.y)
            }
        })
        addMouseMotionListener(object : MouseMotionAdapter() {
            override fun mouseDragged(e: MouseEvent) {
                if (!dragging) return
                val n = normalizedClamped(e.x, e.y)
                onTouchMove(n.x, n.y)
            }
        })
    }

    /** 投递最新一帧并请求重绘（可在任意线程调用，`repaint` 线程安全且会合并刷新）。 */
    fun setFrame(image: BufferedImage?) {
        frame = image
        repaint()
    }

    override fun paintComponent(g: Graphics) {
        super.paintComponent(g)
        val image = frame ?: return
        val g2 = g.create() as Graphics2D
        try {
            g2.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR)
            val f = Letterbox.fit(width, height, image.width, image.height)
            g2.drawImage(image, f.x, f.y, f.width, f.height, null)
        } finally {
            g2.dispose()
        }
    }

    /** 内容区内的归一化坐标；点黑边或尚无画面时返回 null。 */
    private fun normalizedOrNull(px: Int, py: Int): Letterbox.Normalized? {
        val image = frame ?: return null
        return Letterbox.toNormalized(px, py, Letterbox.fit(width, height, image.width, image.height))
    }

    /** 归一化坐标（越界收敛到边界）；尚无画面时回落到 (0,0)。 */
    private fun normalizedClamped(px: Int, py: Int): Letterbox.Normalized {
        val image = frame ?: return Letterbox.Normalized(0f, 0f)
        return Letterbox.toNormalizedClamped(px, py, Letterbox.fit(width, height, image.width, image.height))
    }
}
