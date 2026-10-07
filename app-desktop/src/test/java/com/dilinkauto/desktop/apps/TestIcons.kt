package com.dilinkauto.desktop.apps

import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import javax.imageio.ImageIO

/** 测试用的假 PNG 图标：生成一张 8x8 的纯色图并编码成 PNG 字节。 */
internal object TestIcons {

    fun pngBytes(argb: Int = 0xFF3366FF.toInt()): ByteArray {
        val image = BufferedImage(8, 8, BufferedImage.TYPE_INT_ARGB)
        for (x in 0 until 8) {
            for (y in 0 until 8) image.setRGB(x, y, argb)
        }
        val out = ByteArrayOutputStream()
        ImageIO.write(image, "png", out)
        return out.toByteArray()
    }
}
