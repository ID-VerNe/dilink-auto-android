package com.dilinkauto.vdserver

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Locks the sentinel policy added for S-M10.
 *
 * A session killed by SIGKILL or `Runtime.halt(1)` (the watchdog's own escape
 * hatch) leaves `settings put system screen_off_timeout 2147483647` behind. The next
 * session then snapshots that value as "the user's setting" and restores it — so the
 * phone never sleeps again, and every session after that inherits it. The restore
 * path must recognise our own sentinel and treat it as "no valid snapshot".
 */
class SessionScreenTimeoutTest {

    @Test
    fun sentinelIsRecognisedRegardlessOfWhitespace() {
        assertTrue(SessionScreenTimeout.isSentinel("${SessionScreenTimeout.SESSION_TIMEOUT_SENTINEL}"))
        assertTrue(SessionScreenTimeout.isSentinel("  2147483647  "))
        assertTrue(SessionScreenTimeout.isSentinel("2147483647"))
    }

    @Test
    fun aRealUserValueIsNotMistakenForTheSentinel() {
        assertFalse(SessionScreenTimeout.isSentinel("60000"))
        assertFalse(SessionScreenTimeout.isSentinel("1200000"))
        // 2147483646 is one ms short of the sentinel — a user could plausibly set it.
        assertFalse(SessionScreenTimeout.isSentinel("2147483646"))
        assertFalse(SessionScreenTimeout.isSentinel(null))
        assertFalse(SessionScreenTimeout.isSentinel("null"))
        assertFalse(SessionScreenTimeout.isSentinel(""))
    }

    @Test
    fun sentinelSnapshotRestoresTheDocumentedDefaultInsteadOfTheSentinel() {
        // The whole point: never write the sentinel back.
        assertEquals(
            SessionScreenTimeout.DEFAULT_TIMEOUT_MS.toString(),
            SessionScreenTimeout.valueToRestore("${SessionScreenTimeout.SESSION_TIMEOUT_SENTINEL}")
        )
        assertEquals("60000", SessionScreenTimeout.valueToRestore("2147483647"))
    }

    @Test
    fun theDefaultIsTheStandardSixtySeconds() {
        assertEquals(60_000L, SessionScreenTimeout.DEFAULT_TIMEOUT_MS)
    }

    @Test
    fun aHealthySnapshotIsPassedThroughVerbatim() {
        assertEquals("60000", SessionScreenTimeout.valueToRestore("60000"))
        assertEquals("1200000", SessionScreenTimeout.valueToRestore(" 1200000 "))
        assertEquals("30000", SessionScreenTimeout.valueToRestore("30000"))
    }

    @Test
    fun anUnusableSnapshotYieldsNullSoTheCallerLeavesTheSettingAlone() {
        // Reading failure markers must stay "do nothing" — writing the default over a
        // settings backend that is merely unavailable would be worse.
        assertNull(SessionScreenTimeout.valueToRestore(null))
        assertNull(SessionScreenTimeout.valueToRestore(""))
        assertNull(SessionScreenTimeout.valueToRestore("null"))
        assertNull(SessionScreenTimeout.valueToRestore("undefined"))
        assertNull(SessionScreenTimeout.valueToRestore("0"))
        assertNull(SessionScreenTimeout.valueToRestore("-1"))
    }

    @Test
    fun restoreNeverEmitsTheSentinelForAnyInput() {
        val inputs = listOf(
            null, "", "null", "undefined", "0", "-1", "60000", "2147483646",
            "${SessionScreenTimeout.SESSION_TIMEOUT_SENTINEL}", "  2147483647  "
        )
        for (input in inputs) {
            val restored = SessionScreenTimeout.valueToRestore(input)
            assertFalse(
                "restore of '$input' must never hand back the sentinel",
                SessionScreenTimeout.isSentinel(restored)
            )
        }
    }
}
