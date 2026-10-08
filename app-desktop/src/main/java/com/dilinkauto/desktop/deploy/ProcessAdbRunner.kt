package com.dilinkauto.desktop.deploy

import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit

/**
 * 真实实现：用 [ProcessBuilder] 起 `adb.exe`。
 *
 * `waitForExit = false` 的进程（VD server 的 launch）会被一直持有，直到
 * [killAll] —— 这不是资源泄漏，而是协议要求：本地 adb 进程一退出，设备侧的
 * shell 流就断，`exec app_process` 起来的引擎会被 adbd 回收。
 */
class ProcessAdbRunner(
    private val adbPath: String = defaultAdbPath(),
) : AdbRunner {

    private val streams = CopyOnWriteArrayList<Process>()

    override fun run(
        args: List<String>,
        waitForExit: Boolean,
        timeoutMs: Long,
        onOutput: (String) -> Unit,
    ): Boolean = try {
        val process = ProcessBuilder(listOf(adbPath) + args)
            .redirectErrorStream(true)
            .start()
        // 输出一律在独立线程里排空：同步命令也可能在超时前一直不产出，
        // 在读线程里等待会吃掉 waitFor 的超时语义。
        Thread({ drain(process, onOutput) }, "adb-output").apply {
            isDaemon = true
            start()
        }
        if (waitForExit) {
            val finished = process.waitFor(timeoutMs, TimeUnit.MILLISECONDS)
            if (!finished) process.destroyForcibly()
            finished && process.exitValue() == 0
        } else {
            streams += process
            true
        }
    } catch (_: Exception) {
        // 找不到 adb / 权限不足 / 进程启动失败，一律当作命令失败
        false
    }

    override fun killAll() {
        streams.forEach { runCatching { it.destroy() } }
        streams.clear()
    }

    private fun drain(process: Process, onOutput: (String) -> Unit) {
        val reader = process.inputStream.bufferedReader()
        while (true) {
            val line = runCatching { reader.readLine() }.getOrNull() ?: return
            onOutput(line)
        }
    }

    companion object {
        /** 允许用 `DILINK_ADB` 指定 adb 路径（SDK 没进 PATH 时最省事）。 */
        fun defaultAdbPath(): String =
            System.getenv("DILINK_ADB")?.takeIf { it.isNotBlank() } ?: "adb"
    }
}