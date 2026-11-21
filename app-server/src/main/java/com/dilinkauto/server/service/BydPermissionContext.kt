package com.dilinkauto.server.service

import android.content.Context
import android.content.ContextWrapper
import android.content.pm.PackageManager

/**
 * 绕过 BYDAUTO 客户端权限检查的 ContextWrapper（借自 `BYD-dev/TripLogger` 验证过的做法）。
 *
 * 只影响**客户端**的 `checkPermission` / `enforce*`；服务端 binder 侧的校验不受影响
 * （#188 探针就是靠这个差异区分校验位置的）。
 *
 * 四个入口都要覆盖（`checkPermission` / `checkCallingOrSelfPermission` / `enforce*`），
 * 只拦一个的话 SDK 换个调用点就失效。注意 `checkSelfPermission` 没有 override：
 * SDK 实际用的是 `enforceCallingOrSelfPermission`，`checkSelfPermission` 直通 base，
 * 所以探针日志里 `permSelf` 与 `permReal` 可能相同，真正的差异在 `permSelf` 行里的
 * `checkCallingOrSelf`。
 *
 * 参数声明为不可空：SDK stub 上这些参数标了 `@NonNull`，Kotlin 不接受可空覆盖签名
 * （`'checkPermission' overrides nothing`）。放行判定本身在 [shouldGrantBydPermission]
 * 里对 null 做了保护，供非反射调用方复用。
 *
 * 2026-11-21（issue #278-PA）：本类从临时探针文件 `probe/BydVehicleProbe.kt` 原样搬出
 * （行为零改动，探针改为 import 本文件）。原因：#278 把空调**写链路**建立在它上面
 * （`AcSettingsProvider`），而 #188 的清理条件是「结论落定后探针连同调用点一起删除」——
 * 不搬走的话探针删除日会把 AC 功能的地基一起删掉。
 */
internal class BydPermissionContext(base: Context) : ContextWrapper(base) {
    override fun checkPermission(permission: String, pid: Int, uid: Int): Int =
        if (shouldGrantBydPermission(permission)) PackageManager.PERMISSION_GRANTED
        else super.checkPermission(permission, pid, uid)

    override fun checkCallingOrSelfPermission(permission: String): Int =
        if (shouldGrantBydPermission(permission)) PackageManager.PERMISSION_GRANTED
        else super.checkCallingOrSelfPermission(permission)

    override fun enforcePermission(permission: String, pid: Int, uid: Int, message: String?) {
        if (!shouldGrantBydPermission(permission)) {
            super.enforcePermission(permission, pid, uid, message)
        }
    }

    override fun enforceCallingOrSelfPermission(permission: String, message: String?) {
        if (!shouldGrantBydPermission(permission)) {
            super.enforceCallingOrSelfPermission(permission, message)
        }
    }
}

/**
 * 是否应该对这条权限放行（只放 BYDAUTO_ 前缀的客户端检查）。
 *
 * 抽成顶层纯函数的唯一目的：`BYDAUTO_PREFIX` 写错一个字母，所有 getter 会抛
 * `SecurityException`，而实车上看起来和「服务端拒绝」一模一样 —— 必须有单测钉住
 * （见 `BydVehicleProbeTest`）。
 */
internal fun shouldGrantBydPermission(permission: String?): Boolean =
    permission != null && permission.startsWith(BYDAUTO_PERMISSION_PREFIX)

private const val BYDAUTO_PERMISSION_PREFIX = "android.permission.BYDAUTO_"
