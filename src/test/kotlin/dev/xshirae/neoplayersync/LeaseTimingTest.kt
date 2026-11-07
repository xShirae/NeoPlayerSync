package dev.xshirae.neoplayersync

import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.time.Duration.Companion.seconds

class LeaseTimingTest {
    @Test
    fun `the shipped timings pass their own check`() {
        LeaseTiming.LEASE
    }

    @Test
    fun `a heartbeat too slow for the watch is refused`() {
        assertFailsWith<IllegalArgumentException> {
            LeaseTiming.requireSafe(lease = 15.seconds, heartbeat = 15.seconds, stale = 12.seconds, wait = 20.seconds)
        }
    }

    @Test
    fun `a wait shorter than the lease is refused`() {
        assertFailsWith<IllegalArgumentException> {
            LeaseTiming.requireSafe(lease = 15.seconds, heartbeat = 5.seconds, stale = 12.seconds, wait = 14.seconds)
        }
    }
}
