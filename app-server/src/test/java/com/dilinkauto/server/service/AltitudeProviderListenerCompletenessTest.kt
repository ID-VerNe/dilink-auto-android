package com.dilinkauto.server.service

import android.location.Location
import android.location.LocationListener
import android.os.Bundle
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 钉死 issue #228：AltitudeProvider 的匿名 [LocationListener] 必须显式实现
 * API 28（车机实车 sdk=28）上仍是抽象方法的全部回调。
 *
 * 背景：`compileSdk = 34` 下 `onStatusChanged / onProviderEnabled / onProviderDisabled`
 * 是 default 方法，编译器不强制实现（编译期查不出）；但运行在 API < 30 的车机上
 * 它们仍是 abstract —— 缺实现时 GPS provider 状态一变化，系统回调直接
 * `AbstractMethodError` 杀死车机进程（`.tmp/logs-20261118/client.log:1141-1155`）。
 *
 * 单元测试跑在 compileSdk 34 的 android.jar 上，接口形态与编译期一致（default），
 * 直接调用缺实现的方法也不会抛错，测不出这个缺陷；因此本测试复刻 API 28
 * 运行时的约束：**反射断言 listener 类自己声明了全部四个方法** ——
 * 任一缺失即红，等价于 API 28 设备上回调触发时的 `AbstractMethodError`。
 */
class AltitudeProviderListenerCompletenessTest {

    /** API 28 的 [LocationListener] 全部抽象方法（方法名 → 参数类型）。
     *  名单与 android-28 的接口定义（issue #228 javap 证据）和
     *  [CityResolver] 的正确写法一致。 */
    private val api28AbstractCallbacks = listOf(
        "onLocationChanged" to arrayOf(Location::class.java),
        "onStatusChanged" to arrayOf(
            String::class.java, Int::class.javaPrimitiveType!!, Bundle::class.java
        ),
        "onProviderEnabled" to arrayOf(String::class.java),
        "onProviderDisabled" to arrayOf(String::class.java),
    )

    @Test
    fun `start 的匿名 listener 显式实现 API 28 全部抽象回调`() {
        // start() 内匿名 listener 的编译产物类名（issue #228 dexdump 证据：
        // `AltitudeProvider$start$listener$1`）。若后续改名/移动导致此处
        // ClassNotFoundException，属于预期防回归信号，需同步更新本测试。
        val clazz = Class.forName("com.dilinkauto.server.service.AltitudeProvider\$start\$listener\$1")
        assertTrue(
            "listener 必须实现 LocationListener",
            LocationListener::class.java.isAssignableFrom(clazz)
        )
        for ((name, params) in api28AbstractCallbacks) {
            val declared = try {
                clazz.getDeclaredMethod(name, *params)
                true
            } catch (_: NoSuchMethodException) {
                false
            }
            assertTrue(
                "listener 未显式实现 $name：compileSdk 34 上编译器不强制（default 方法），" +
                    "但车机 API 28 上仍是抽象方法，回调触发即 AbstractMethodError（issue #228）",
                declared
            )
        }
    }
}
