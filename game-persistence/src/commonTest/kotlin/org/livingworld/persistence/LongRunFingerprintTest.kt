package org.livingworld.persistence

import kotlinx.coroutines.test.runTest
import org.livingworld.core.simulation.SimulationEngine
import org.livingworld.core.time.WorldDuration
import org.livingworld.core.world.DefaultVillageFactory
import kotlin.test.*

/**
 * G8 over a long run: the 90-day default village must still produce the fingerprint the project documents. A change
 * here means the simulation moved - which is only allowed as a declared golden change, with the goldens re-recorded
 * and `lw_mirror` ported.
 */
class LongRunFingerprintTest {

    @Test
    fun the_ninety_day_default_run_still_has_the_known_fingerprint() = runTest {
        val after = SimulationEngine().advance(DefaultVillageFactory.marketVillage(), WorldDuration.days(90))
        assertEquals(
            "7ae29cd5c158da35",
            SaveManager.computeDeterministicFingerprint(after),
            "the 90-day run no longer matches the documented fingerprint"
        )
    }
}
