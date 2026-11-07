package dev.xshirae.neoplayersync

import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * The timings that keep a player on one server at a time. They only work together, so they live here and [init]
 * refuses a combination that could hand a playing character to a second server.
 */
internal object LeaseTiming {
    /** How long a claim lasts after each renewal or save, on the database's clock. */
    val LEASE: Duration = 15.seconds

    /** How often a running server renews every claim it holds. */
    val HEARTBEAT: Duration = 5.seconds

    /**
     * How long a joining server watches a holder's lease stay unchanged before taking an expired claim. Two heartbeats
     * plus room for a slow reconnect, so a running server renews within it even after an outage expired every lease,
     * and its waiting save lands before another server takes over.
     */
    val STALE: Duration = 12.seconds

    /** How long a joining player waits for the holder to let go before they are refused. */
    val WAIT: Duration = 20.seconds

    init {
        requireSafe(LEASE, HEARTBEAT, STALE, WAIT)
    }

    fun requireSafe(lease: Duration, heartbeat: Duration, stale: Duration, wait: Duration) {
        // A live server must renew at least twice within the watch, or a single late heartbeat hands its player away.
        require(heartbeat * 2 < stale) { "HEARTBEAT ($heartbeat) must fit twice into STALE ($stale)" }
        // The watch starts with the first attempt, so a crashed holder is replaced after max(LEASE, STALE) at most.
        require(wait > maxOf(lease, stale)) { "WAIT ($wait) must outlast LEASE ($lease) and STALE ($stale)" }
    }
}
