package net.guacamaya.mesh

/**
 * Timestamp-age gate for inbound frames — the store-and-forward half of the reject
 * cascade. The signed `tsUnix` can never be refreshed (re-signing is impossible:
 * node_id is bound to the origin key), so a frame's age only grows as it is held and
 * re-broadcast by intermediate nodes.
 *
 * Two regimes, keyed by [Payload.isHelpRequest]:
 *  - **Help requests** (SOS / aid): accepted up to [HELP_MAX_AGE_SECONDS] in the past,
 *    so a victim's last-known signed location keeps propagating long after their phone
 *    dies — a relay can hand it to a node that only just came into range.
 *  - **Presence / heartbeat**: kept fresh-only ([PRESENCE_MAX_AGE_SECONDS]); a stale
 *    "I'm here" beacon is meaningless and must not be forwarded.
 *
 * A tight future bound ([FUTURE_SKEW_SECONDS]) is always applied to limit clock-skew
 * abuse. Tune the constants to trade store-and-forward reach against staleness.
 */
object AgePolicy {

    /** Max clock skew into the future tolerated for any frame. */
    const val FUTURE_SKEW_SECONDS = 300L

    /** Fresh-only window for presence/heartbeat frames (the old global replay window). */
    const val PRESENCE_MAX_AGE_SECONDS = 300L

    /** Store-and-forward window for help requests — disaster timescale. */
    const val HELP_MAX_AGE_SECONDS = 24L * 60 * 60 // 24 h

    /**
     * @param tsUnix       signed origin timestamp (unix seconds)
     * @param nowSeconds   current time (unix seconds)
     * @param isHelpRequest whether the frame is an aid request (vs presence)
     */
    fun accept(tsUnix: Long, nowSeconds: Long, isHelpRequest: Boolean): Boolean {
        val age = nowSeconds - tsUnix // positive = in the past
        if (age < -FUTURE_SKEW_SECONDS) return false // too far in the future
        val maxAge = if (isHelpRequest) HELP_MAX_AGE_SECONDS else PRESENCE_MAX_AGE_SECONDS
        return age <= maxAge
    }
}
