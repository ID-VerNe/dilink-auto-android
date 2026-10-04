package com.dilinkauto.vdserver

import java.io.OutputStream

/**
 * Logging and shell helpers shared by the VD server's pipeline collaborators
 * ([PipelineServer], [GlPipeline], [TouchInjector], [DisplayPowerController]).
 *
 * The VD server runs as shell via `app_process` and has no android.util.Log —
 * everything goes to stdout/stderr, which app_process redirects to the
 * vd-server.log file the car reads. Centralizing the format here keeps the
 * `[Pipeline]` prefix consistent and removes the per-class `log`/`err`
 * duplicates that were copy-pasted across the four files.
 */
internal object PipeLog {
    fun log(msg: String) { println("[Pipeline] $msg"); System.out.flush() }
    fun err(msg: String) { System.err.println("[Pipeline] $msg"); System.err.flush() }
}

/**
 * Shell command execution against the VD server's persistent `sh` process.
 *
 * The persistent shell is kept alive for the whole server lifetime so we don't
 * fork a new `sh` per `input`/`settings`/`am` call. [execShell] writes a line
 * to the shell's stdin; [execShellOutput] spawns a one-shot `sh -c` when we
 * need to capture stdout. Both swallow exceptions — the VD server must not die
 * because a settings put failed.
 */
internal object ShellExec {
    /** 输出摘要的截断长度：避免 dumpsys 等大输出把 vd-server.log 刷爆 */
    private const val OUTPUT_SUMMARY_CHARS = 400

    /** Write `cmd\n` to the persistent shell's stdin and flush. */
    fun execShell(shellInput: OutputStream?, cmd: String) {
        PipeLog.log("sh> $cmd")
        try { shellInput?.let { it.write("$cmd\n".toByteArray()); it.flush() } }
        catch (e: Exception) { PipeLog.err("sh> write failed: ${e.message} (cmd=$cmd)") }
    }

    /**
     * Run `sh -c cmd`, capture stdout + stderr, block until exit. Returns null on any failure.
     *
     * 用 ProcessBuilder + redirectErrorStream 合并 stderr：am start 等命令的报错
     * （"Error: Activity ... does not exist"）不再丢失，同时避免 stderr 管道无人
     * 读取被写满后卡死调用线程。每次执行都记录命令、退出码与输出摘要。
     */
    fun execShellOutput(cmd: String): String? = try {
        val p = ProcessBuilder("sh", "-c", cmd).redirectErrorStream(true).start()
        val o = p.inputStream.bufferedReader().readText()
        p.waitFor()
        val summary = o.trim().replace(Regex("\\s+"), " ")
        PipeLog.log("sh# $cmd -> exit=${p.exitValue()} out='${summary.take(OUTPUT_SUMMARY_CHARS)}'")
        o
    } catch (e: Exception) {
        PipeLog.err("sh# $cmd -> failed: ${e.message}")
        null
    }

    /**
     * 给持久 sh 进程挂 stdout/stderr 排水线程。
     *
     * 持久 sh 的 stdout/stderr 是管道：无人读取时缓冲写满（约 64KB）会让 sh 永久
     * 阻塞在 write 上，之后所有命令（am/input/settings）静默失效。排水线程把每行
     * 输出写进 vd-server.log，既防阻塞，又能看到命令的真实回显与报错。
     */
    fun startOutputDrain(p: Process) {
        for ((name, stream) in listOf("out" to p.inputStream, "err" to p.errorStream)) {
            Thread({
                try { stream.bufferedReader().forEachLine { line -> PipeLog.log("sh<$name| $line") } }
                catch (_: Exception) {}
            }, "ShellDrain-$name").also { it.isDaemon = true; it.start() }
        }
    }
}
