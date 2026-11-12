package com.dilinkauto.server.decoder

/**
 * 车机端音频播放的纯判定/格式化（issue #73 分节 PB / Refs #13）。
 *
 * 与 `service/AudioPolicy.kt` 同一范式：`app-server` 没有 Robolectric
 * （`build.gradle.kts` 的 `unitTests.isReturnDefaultValues = true` 让 `AudioTrack` /
 * `MediaCodec` 这些框架类拿不到真值），凡是不碰 Android API 的判定与字符串组装都
 * 挤到 top-level `internal fun` 里，由 `AudioPlayerPolicyTest` 用字面量钉住。
 * （连接门判定 [carShouldConnectAudio] 与它同源，但落 `service/AudioPolicy.kt`
 * —— 那是「车机策略」的既定归口。）
 */

/**
 * 音频播放的周期/终局日志行（契约格式，S6 直接 `grep -oE` / `grep -F`，Refs #73, #79, #84）：
 *
 * ```
 * [AudioPlayer] decoded=<累计解码帧> starved=<累计欠载补静音> underrun=<AudioTrack underrunCount> maxRecoverMs=<最长恢复耗时> queue=<队列深度> silence=<累计静音帧>
 * ```
 *
 * 与 `VideoDecoder` 的 `Fed ... drops=...` 汇总行同风格：字段名 `=` 分隔、空格相连，
 * 便于 `grep -F` 精确命中与人工阅读。前缀 `[AudioPlayer]` 与 `VideoDecoder` 的
 * `[VideoDecoder]` 对齐 —— 都经 `carLogSend`（tag `CarLog`）中继进手机侧 `client.log`。
 *
 * 组装成纯函数是为了：格式一旦漂移，`AudioPlayerPolicyTest` 会红；而行本身在真机
 * 上的可达性由 S6 场景断言（`scripts/lib/asserts.sh` 的 `countF`）。
 */
internal fun audioPlayerStatLine(
    decoded: Long,
    starved: Long,
    underrun: Int,
    maxRecoverMs: Long,
    queue: Int,
    silence: Long = 0L,
): String =
    "[AudioPlayer] decoded=$decoded starved=$starved underrun=$underrun " +
        "maxRecoverMs=$maxRecoverMs queue=$queue silence=$silence"

/**
 * 判定 PCM 16-bit 缓冲是否纯静音（issue #84 / Refs #79）。
 *
 * AAC-LC 解码出来的 PCM 每样本 2 字节（小端 16-bit signed）。
 * 当且仅当字节流全部为 0 时判定为静音（与 vd-server 端 silenceFrame(spec) 全 0 数组对称）。
 * 若缓冲为空，返回 true。
 */
internal fun isPcmSilent(pcm: ByteArray): Boolean {
    for (b in pcm) {
        if (b.toInt() != 0) return false
    }
    return true
}
