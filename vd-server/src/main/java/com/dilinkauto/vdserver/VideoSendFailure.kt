package com.dilinkauto.vdserver

import java.io.EOFException
import java.nio.channels.ClosedChannelException

/**
 * `VideoSend` 写失败的分类与日志文案（issue #221）。
 *
 * 现场 `vd-server.log:3176` 只有一行
 * `VideoSend: write failed, session should reconnect: null`，而同一时刻车机端是
 * `User disconnect — will not auto-reconnect`：正常关闭被记成「应重连的故障」，
 * 且 `message` 为 null 时真实原因完全丢失。
 *
 * 这里把异常分两类：
 *  - [Description.expectedClose]：本端/对端关闭通道（[ClosedChannelException]、
 *    [EOFException]、Broken pipe / Connection reset 等），属于正常收尾；
 *  - 其余为真实写失败，保留 `session should reconnect` 语义。
 *
 * 文案始终带异常类名；`message` 为空时显式写 `(no message)` 并附最多
 * [MAX_CAUSE_DEPTH] 层 cause 链，日志里不再出现裸 `null`。
 */
internal object VideoSendFailure {

    data class Description(val expectedClose: Boolean, val text: String)

    /** 对端/本端关闭时的 message 片段（小写匹配）。 */
    private val CLOSE_MESSAGES = listOf(
        "broken pipe",
        "connection reset",
        "connection closed",
        "socket closed",
        "closed channel",
    )

    private const val MAX_CAUSE_DEPTH = 3

    fun describe(error: Throwable): Description = if (isExpectedClose(error)) {
        Description(expectedClose = true, text = "connection closed during send (${detail(error)})")
    } else {
        Description(expectedClose = false, text = "write failed, session should reconnect (${detail(error)})")
    }

    private fun isExpectedClose(error: Throwable): Boolean {
        var cur: Throwable? = error
        while (cur != null) {
            if (cur is ClosedChannelException || cur is EOFException) return true
            val msg = cur.message?.lowercase()
            if (msg != null && CLOSE_MESSAGES.any { msg.contains(it) }) return true
            cur = cur.cause
        }
        return false
    }

    /** 异常类名 + message（空则显式标注）+ cause 链（最多 [MAX_CAUSE_DEPTH] 层）。 */
    internal fun detail(error: Throwable): String {
        val head = error.message?.takeIf { it.isNotBlank() }
        val sb = StringBuilder(error.javaClass.simpleName)
        sb.append(if (head != null) ": $head" else " (no message)")
        val causes = mutableListOf<String>()
        var cur = error.cause
        while (cur != null && causes.size < MAX_CAUSE_DEPTH) {
            val msg = cur.message?.takeIf { it.isNotBlank() }
            causes += cur.javaClass.simpleName + if (msg != null) ": $msg" else ""
            cur = cur.cause
        }
        if (causes.isNotEmpty()) sb.append(" <- ").append(causes.joinToString(" <- "))
        return sb.toString()
    }
}
