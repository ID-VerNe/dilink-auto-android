package com.dilinkauto.desktop.apps

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

/** [AppIconStore] 单测：空图标沿用、解码失败不清旧、以及包名维度的增删。 */
class AppIconStoreTest {

    @Test
    fun put_decodesPngAndCachesByPackageName() {
        val store = AppIconStore()
        val decoded = store.put("com.example.a", TestIcons.pngBytes())
        assertNotNull(decoded)
        assertEquals(8, decoded!!.width)
        assertSame(decoded, store.get("com.example.a"))
        assertEquals(1, store.size)
    }

    @Test
    fun put_withEmptyPng_keepsPreviousIcon() {
        // 手机侧用 hash 抑制重复图标 → 后续 APP_LIST 里该包的 iconPng 为空，
        // 此时必须沿用旧图标，否则列表刷新一次图标就全没了。
        val store = AppIconStore()
        val first = store.put("com.example.a", TestIcons.pngBytes())
        val again = store.put("com.example.a", ByteArray(0))
        assertSame(first, again)
        assertSame(first, store.get("com.example.a"))
    }

    @Test
    fun put_withUndecodablePng_keepsPreviousIcon() {
        val store = AppIconStore()
        val first = store.put("com.example.a", TestIcons.pngBytes())
        assertSame(first, store.put("com.example.a", byteArrayOf(1, 2, 3, 4)))
    }

    @Test
    fun put_withUndecodablePngAndNoPrevious_returnsNull() {
        val store = AppIconStore()
        assertNull(store.put("com.example.a", byteArrayOf(1, 2, 3, 4)))
        assertEquals(0, store.size)
    }

    @Test
    fun newPng_replacesPreviousDecodedImage() {
        val store = AppIconStore()
        val first = store.put("com.example.a", TestIcons.pngBytes(0xFF112233.toInt()))
        val second = store.put("com.example.a", TestIcons.pngBytes(0xFF445566.toInt()))
        assertNotNull(second)
        assertSame(second, store.get("com.example.a"))
        assertEquals(1, store.size)
        // 只做"换掉了"的判定：两者不是同一实例
        assertNotSame(first, second)
    }

    @Test
    fun removeAndClear_dropEntries() {
        val store = AppIconStore()
        store.put("com.example.a", TestIcons.pngBytes())
        store.put("com.example.b", TestIcons.pngBytes())
        assertEquals(2, store.size)

        store.remove("com.example.a")
        assertNull(store.get("com.example.a"))
        assertNotNull(store.get("com.example.b"))

        store.clear()
        assertEquals(0, store.size)
        assertNull(store.get("com.example.b"))
    }
}
