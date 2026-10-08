package com.dilinkauto.server.decoder

import android.os.SystemClock

/**
 * Detects a persistently black video stream.
 *
 * Extracted from [VideoDecoder] (docs/audit-srp-dry.md SRP-3) so the policy can
 * be unit-tested without MediaCodec, EGL or a Surface. Nothing here touches the
 * decoder; it only consumes (isKeyFrame, payloadSize, nowMs) and reports events.
 *
 * How it works: the encoder emits very small I-frames for a near-solid image, so
 * a run of consecutive tiny keyframes means the stream is black — the VirtualDisplay
 * produced nothing, or the app failed to render.
 *
 * Two distinct reactions, and the asymmetry is deliberate:
 *  - **Alert** after [alertStreak] consecutive tiny keyframes. Logging only.
 *  - **Escalate** (invoke [onSustainedBlackScreen]) only after the black frames
 *    have persisted for [BLACK_SCREEN_SUSTAIN_MS]. A short burst during app or
 *    VD warm-up is normal, and escalating there causes a reconnect storm —
 *    the same failure mode that leaked VirtualDisplays in the first place.
 *
 * Escalation fires at most once until [reset].
 */
internal class BlackScreenDetector(
    /** I-frames below this size count as "tiny". */
    private val keyframeMaxBytes: Int = 2 * 1024,
    /** Consecutive tiny keyframes before the alert fires. */
    private val alertStreak: Int = 3
) {

    /**
     * Called once per session when the stream is *persistently* black — not on
     * the third tiny keyframe, but only after [BLACK_SCREEN_SUSTAIN_MS] of
     * continuous tiny keyframes.
     */
    var onSustainedBlackScreen: (() -> Unit)? = null

    private var tinyKeyframeStreak = 0
    private var alerted = false

    /**
     * When the current black streak began, or null when not in a streak.
     *
     * Null rather than a `0L` sentinel: the original inline code guarded with
     * `since > 0`, which silently swallowed a streak that started at clock 0 —
     * a real defect, since it delays escalation by a whole window whenever the
     * monotonic clock happens to be low.
     */
    private var blackSinceMs: Long? = null
    private var recoveryFired = false

    /** How long the stream must stay black before [onSustainedBlackScreen] fires. */
    var sustainMs: Long = BLACK_SCREEN_SUSTAIN_MS

    /**
     * Feeds one received frame.
     *
     * @param isKeyFrame whether this frame is an I-frame (NAL type 5).
     * @param size payload size in bytes.
     * @param nowMs monotonic clock, injectable for tests.
     */
    fun onFrame(isKeyFrame: Boolean, size: Int, nowMs: Long = SystemClock.elapsedRealtime()) {
        if (!isKeyFrame) return

        if (size >= keyframeMaxBytes) {
            // A normally-sized I-frame clears the streak and re-arms escalation.
            alerted = false
            tinyKeyframeStreak = 0
            blackSinceMs = 0L
            recoveryFired = false
            return
        }

        tinyKeyframeStreak++
        if (blackSinceMs == null) blackSinceMs = nowMs

        if (tinyKeyframeStreak >= alertStreak && !alerted) {
            alerted = true
        }

        if (recoveryFired) return
        val since = blackSinceMs ?: return
        if (nowMs - since >= sustainMs) {
            recoveryFired = true
            onSustainedBlackScreen?.invoke()
        }
    }

    /**
     * Arms [onSustainedBlackScreen] for [sustainMs].
     *
     * The caller must call this whenever the session's configured window
     * changes; keeping [sustainMs] a plain mutable field means a bare assignment
     * would silently leave an already-constructed detector on its default.
     */
    fun setSustainWindow(ms: Long) {
        sustainMs = ms
    }

    /** True once [alertStreak] tiny keyframes have been seen. */
    fun isAlerted(): Boolean = alerted

    /** True once escalation has fired (until [reset]). */
    fun hasFiredRecovery(): Boolean = recoveryFired

    /** Current consecutive-tiny-keyframe count. */
    fun streak(): Int = tinyKeyframeStreak

    /**
     * Re-arm for a fresh session: a new stream gets a new chance, and the
     * escalate-once latch must not survive into it.
     */
    fun reset() {
        tinyKeyframeStreak = 0
        alerted = false
        blackSinceMs = null
        recoveryFired = false
    }

    companion object {
        const val BLACK_SCREEN_SUSTAIN_MS = 3000L
    }
}