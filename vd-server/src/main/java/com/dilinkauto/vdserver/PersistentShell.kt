package com.dilinkauto.vdserver

import java.io.OutputStream

/**
 * The long-lived `sh` process every VD-server shell command goes through
 * (audit R3-SRP-10).
 *
 * Started in [PipelineServer.run] before the encoder is set up, torn down last
 * in `cleanup()` — the teardown ordering there ("restore screen BEFORE killing
 * shell") depends on this process staying alive through the panel restore.
 *
 * A null [input] means the shell never started; [exec] then becomes a silent
 * no-op, which is the behaviour every caller already tolerates.
 */
internal class PersistentShell {

    private var process: Process? = null

    /** stdin of the persistent shell; null until [start] succeeds. */
    var input: OutputStream? = null
        private set

    fun start() {
        try {
            val p = Runtime.getRuntime().exec(arrayOf("sh"))
            process = p
            input = p.outputStream
            // 给持久 sh 挂 stdout/stderr 排水线程：防止管道无人读被写满后 sh 卡死（后续命令全部静默失效）
            ShellExec.startOutputDrain(p)
            PipeLog.log("Shell: persistent sh started")
        } catch (e: Exception) {
            PipeLog.err("Shell: ${e.message}")
        }
    }

    /** Write `cmd\n` to the shell's stdin. Never throws. */
    fun exec(cmd: String) = ShellExec.execShell(input, cmd)

    /** One-shot `sh -c`, captures output. Never throws. */
    fun execOutput(cmd: String): String? = ShellExec.execShellOutput(cmd)

    /** Close stdin first, then destroy the process. Never throws. */
    fun close() {
        process?.let {
            try { input?.close() } catch (_: Exception) {}
            it.destroy()
        }
    }
}
