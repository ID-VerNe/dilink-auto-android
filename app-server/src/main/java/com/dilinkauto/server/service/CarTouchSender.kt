package com.dilinkauto.server.service

import com.dilinkauto.protocol.Connection
import com.dilinkauto.protocol.InputMsg
import com.dilinkauto.protocol.TouchEvent
import com.dilinkauto.protocol.TouchMoveBatch
import java.util.concurrent.Executors

/**
 * Encodes and sends touch events from the car to the phone's VD server on the
 * input connection (port 9639).
 *
 * A dedicated single-thread executor keeps touch encoding off the UI thread
 * and serializes sends so the input channel's frame ordering stays stable.
 * Drop/send counters gate log spam: only the first 3 and every 100th event is
 * logged, which keeps the phone log readable during a touch storm.
 *
 * Extracted from [CarConnectionService].
 */
internal class CarTouchSender(
    private val inputConnectionProvider: () -> Connection?,
    private val stateProvider: () -> CarConnectionService.State,
    private val log: (String, String) -> Unit
) {
    private val executor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "touch-sender").apply { isDaemon = true }
    }
    private var dropCount = 0L
    private var sendCount = 0L

    fun sendTouchEvent(event: TouchEvent) {
        val conn = checkConn("Touch") ?: return
        val payload = event.encode()
        executor.execute {
            try {
                conn.sendInput(event.action, payload)
                sendCount++
                if (sendCount <= 5 || sendCount % 100 == 0L) {
                    log("Touch #$sendCount action=${event.action} ptr=${event.pointerId} x=${"%.2f".format(event.x)} y=${"%.2f".format(event.y)}", "I")
                }
            } catch (e: Exception) { log("Touch send failed: ${e.message}", "W") }
        }
    }

    fun sendTouchBatch(pointers: List<TouchEvent>) {
        val conn = checkConn("Touch batch") ?: return
        val payload = TouchMoveBatch(pointers).encode()
        executor.execute {
            try {
                conn.sendInput(InputMsg.TOUCH_MOVE_BATCH, payload)
                sendCount++
                if (sendCount <= 5 || sendCount % 100 == 0L) {
                    log("Touch batch #$sendCount (${pointers.size} pointers)", "I")
                }
            } catch (e: Exception) { log("Touch batch send failed: ${e.message}", "W") }
        }
    }

    /**
     * Shared null-conn + not-connected check with the `<= 3 || % 100` drop-gate.
     * Returns the connection if usable, or null after bumping dropCount and
     * logging the reason. Both public send methods route through this so the
     * gating predicate lives in one place.
     */
    private fun checkConn(label: String): Connection? {
        val conn = inputConnectionProvider()
        if (conn == null) {
            dropCount++
            if (dropCount <= 3 || dropCount % 100 == 0L) {
                log("$label DROP #$dropCount: inputConnection=null state=${stateProvider()}", "I")
            }
            return null
        }
        if (!conn.isConnected) {
            dropCount++
            if (dropCount <= 3 || dropCount % 100 == 0L) {
                log("$label DROP #$dropCount: inputConnection not connected", "I")
            }
            return null
        }
        return conn
    }

    /** Reset counters on disconnect so the next session's log starts fresh. */
    fun resetCounters() {
        sendCount = 0
        dropCount = 0
    }

    fun shutdown() { executor.shutdownNow() }
}
