package ai.graine.inapp

import org.junit.Assert.assertEquals
import org.junit.Test

class SmokeTest {
    @Test fun theBuildRuns() = assertEquals("0.11.0", GraineVersion.NAME)
}
