package ai.graine.inapp

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Back in the foreground, the page is told to warm — when it is ready to hear
 * it, and never during a call. The same rule as the React Native launcher
 * (sdk/inapp/test/render/foreground-warm.test.mjs).
 */
class WarmTest {
    @Test fun anIdleReadyPageIsWarmed() {
        assertEquals(true, Warm.onForeground(ready = true, startedCall = false, connected = false, connecting = false))
    }

    @Test fun aPageNotYetReadyIsNotToldAnything() {
        assertEquals(false, Warm.onForeground(ready = false, startedCall = false, connected = false, connecting = false))
    }

    @Test fun aCallStartingOrUnderwayIsNeverRewarmed() {
        assertEquals(false, Warm.onForeground(ready = true, startedCall = true, connected = false, connecting = false))
        assertEquals(false, Warm.onForeground(ready = true, startedCall = false, connected = false, connecting = true))
        assertEquals(false, Warm.onForeground(ready = true, startedCall = false, connected = true, connecting = false))
    }
}
