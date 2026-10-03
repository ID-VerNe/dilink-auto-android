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
    /** Write `cmd\n` to the persistent shell's stdin and flush. */
    fun execShell(shellInput: OutputStream?, cmd: String) {
        try { shellInput?.let { it.write("$cmd\n".toByteArray()); it.flush() } } catch (_: Exception) {}
    }

    /** Run `sh -c cmd`, capture stdout, block until exit. Returns null on any failure. */
    fun execShellOutput(cmd: String): String? = try {
        val p = Runtime.getRuntime().exec(arrayOf("sh", "-c", cmd))
        val o = p.inputStream.bufferedReader().readText()
        p.waitFor()
        o
    } catch (_: Exception) { null }
}
