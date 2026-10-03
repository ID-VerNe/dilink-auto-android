package com.dilinkauto.client.service

/**
 * Semantic-version parsing and comparison for the update pipeline.
 *
 * Extracted from [UpdateManager] as pure functions so the car-side installer
 * and tests can compare versions without pulling in the update state machine.
 *
 * Scheme (matches the project's tag format):
 *  - "0.17.0"        → base "0.17.0", dev=false, devNum=0
 *  - "0.17.0-dev"    → base "0.17.0", dev=true,  devNum=0
 *  - "0.17.0-dev-02" → base "0.17.0", dev=true,  devNum=2
 *  - "0.17.0-SNAPSHOT" / "0.17.0-dev-abc" → not a recognized dev form; base=full string, dev=false
 *
 * Release > dev of the same base; dev builds order by devNum.
 */

// "0.17.0-dev-02" → ("0.17.0", true, 2)
// "0.17.0-dev"    → ("0.17.0", true, 0)
// "0.17.0"        → ("0.17.0", false, 0)
internal data class ParsedVersion(val base: String, val isDev: Boolean, val devNum: Int)

internal fun parseVersion(v: String): ParsedVersion {
    val m = Regex("^(.*)-dev(?:-(\\d+))?\$").find(v)
    return if (m != null) {
        ParsedVersion(m.groupValues[1], true, m.groupValues[2].toIntOrNull() ?: 0)
    } else {
        ParsedVersion(v, false, 0)
    }
}

/**
 * Compare two version strings. Returns positive if [a] is newer, negative if
 * [b] is newer, 0 if equal. Non-numeric components coerce to 0, so "1.2.beta"
 * compares as "1.2.0" — robust against malformed input from the wire.
 */
internal fun compareVersions(a: String, b: String): Int {
    val (baseA, devA, numA) = parseVersion(a)
    val (baseB, devB, numB) = parseVersion(b)
    val aParts = baseA.split(".").map { it.toIntOrNull() ?: 0 }
    val bParts = baseB.split(".").map { it.toIntOrNull() ?: 0 }
    val maxLen = maxOf(aParts.size, bParts.size)
    for (i in 0 until maxLen) {
        val aVal = aParts.getOrElse(i) { 0 }
        val bVal = bParts.getOrElse(i) { 0 }
        if (aVal != bVal) return aVal.compareTo(bVal)
    }
    if (!devA && devB) return 1
    if (devA && !devB) return -1
    return numA.compareTo(numB)
}
