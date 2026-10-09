package com.dilinkauto.desktop.apps

import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.util.concurrent.ConcurrentHashMap
import javax.imageio.ImageIO

/**
 * 应用图标缓存（会话内内存缓存，包名 → 已解码位图）。
 *
 * ── 为什么可以用内存缓存 ──
 * 手机侧 `AppListBuilder` 用 `lastSentIconHash` 按 `packageName.lastUpdateTime`
 * 抑制重复图标，且 `ConnectionService.cleanupSession()` 会调 `resetIconHashes()`
 * ——也就是说**每次重连都会重发全部图标**。因此桌面端不需要磁盘缓存，
 * 只要在会话存续期间记住已解码的位图即可；跨会话丢失也无所谓。
 *
 * ── 空图标语义 ──
 * `AppInfo.iconPng` 为空表示"手机侧认为你已经有这个图标了"（hash 未变，被抑制）。
 * 这时必须沿用上一次的位图，而不是把已有图标清掉，否则列表刷新一次图标就全没了。
 *
 * 线程模型：由数据帧分发协程（IO）单侧写入、UI 线程读取，故用 [ConcurrentHashMap]。
 */
class AppIconStore(
    private val decode: (ByteArray) -> BufferedImage? = ::decodePng,
) {
    private val icons = ConcurrentHashMap<String, BufferedImage>()

    /**
     * 存入（或沿用）某个包的图标，返回该包当前生效的位图。
     *
     * @param png 手机下发的 PNG 字节；为空或解码失败时沿用已有缓存。
     * @return 当前生效的位图，从未有过则为 null。
     */
    fun put(packageName: String, png: ByteArray): BufferedImage? {
        val existing = icons[packageName]
        if (png.isEmpty()) return existing
        val decoded = decode(png) ?: return existing
        icons[packageName] = decoded
        return decoded
    }

    fun get(packageName: String): BufferedImage? = icons[packageName]

    fun remove(packageName: String) {
        icons.remove(packageName)
    }

    // 没有 clear()：跨会话清缓存靠新建 store 实例（每代 AppCatalog 新的一个，
    // 见 AppCatalog 的 KDoc）。此前那个只被测试调用的 clear() 已删（audit WIN-10）。

    /** 当前缓存的图标数（供日志/测试断言用）。 */
    val size: Int get() = icons.size

    companion object {
        /** 默认解码器：把 PNG 字节解成 [BufferedImage]。失败返回 null，由调用方决定回退策略。 */
        fun decodePng(png: ByteArray): BufferedImage? =
            runCatching { ImageIO.read(ByteArrayInputStream(png)) }.getOrNull()
    }
}
