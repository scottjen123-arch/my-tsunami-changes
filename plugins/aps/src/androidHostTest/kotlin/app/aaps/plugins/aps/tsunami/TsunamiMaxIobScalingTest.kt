package app.aaps.plugins.aps.tsunami

import app.aaps.core.data.model.TSU
import app.aaps.core.interfaces.constraints.Constraint
import app.aaps.core.interfaces.db.PersistenceLayer
import app.aaps.core.keys.BooleanKey
import app.aaps.core.objects.constraints.ConstraintObject
import app.aaps.core.objects.profile.ProfileSealed
import app.aaps.plugins.aps.ApsStringsValues
import app.aaps.shared.tests.TestBaseWithProfile
import app.aaps.shared.tests.generatedTextResolver
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.Mock
import org.mockito.kotlin.whenever

/**
 * Tests for [TsunamiMaxIobScaling].
 *
 * Max IOB used to stay fixed whatever the profile percentage, so in times of high insulin need it stopped
 * Tsunami in the middle of a meal. It now follows the percentage: up only during a Tsunami session and only
 * to 130 %, down at any time.
 */
class TsunamiMaxIobScalingTest : TestBaseWithProfile() {

    @Mock lateinit var persistenceLayer: PersistenceLayer

    private val text = generatedTextResolver("aps" to ApsStringsValues::textOf)

    @BeforeEach fun setUp() = runTest {
        whenever(preferences.get(BooleanKey.TsuSMBscaling)).thenReturn(true)
        setPercentage(100)
        setSession(false)
    }

    private suspend fun setPercentage(percentage: Int) {
        val profile = ProfileSealed.EPS(effectiveProfileSwitch.copy(originalPercentage = percentage), activePlugin)
        whenever(profileFunction.getProfile()).thenReturn(profile)
    }

    private suspend fun setSession(active: Boolean) {
        val session = TSU(timestamp = now - 10 * 60_000L, duration = 120 * 60_000L, tsunamiMode = 2)
        whenever(persistenceLayer.getTsunamiActiveAt(now)).thenReturn(if (active) session else null)
    }

    /** Runs the scaling the way the plugin does, then the plain preference value if nothing was scaled. */
    private suspend fun limit(maxIobPref: Double): Constraint<Double> {
        val maxIob: Constraint<Double> = ConstraintObject(Double.MAX_VALUE, aapsLogger)
        if (!TsunamiMaxIobScaling.applyScaled(maxIob, maxIobPref, preferences, profileFunction, persistenceLayer, dateUtil, text, this))
            maxIob.setIfSmaller(maxIobPref, "max value in preferences", this)
        return maxIob
    }

    @Test fun `100 percent keeps max IOB`() {
        assertThat(TsunamiMaxIobScaling.scaledMaxIob(7.0, 100, tsunamiSessionActive = true)).isWithin(1e-9).of(7.0)
    }

    @Test fun `a higher percentage during a session makes max IOB bigger`() {
        assertThat(TsunamiMaxIobScaling.scaledMaxIob(7.0, 115, tsunamiSessionActive = true)).isWithin(1e-9).of(8.05)
    }

    @Test fun `max IOB grows only up to 130 percent`() {
        assertThat(TsunamiMaxIobScaling.scaledMaxIob(7.0, 130, tsunamiSessionActive = true)).isWithin(1e-9).of(9.1)
        assertThat(TsunamiMaxIobScaling.scaledMaxIob(7.0, 160, tsunamiSessionActive = true)).isWithin(1e-9).of(9.1)
    }

    @Test fun `a higher percentage without a session keeps max IOB`() {
        assertThat(TsunamiMaxIobScaling.scaledMaxIob(7.0, 160, tsunamiSessionActive = false)).isWithin(1e-9).of(7.0)
    }

    @Test fun `a lower percentage makes max IOB smaller with or without a session`() {
        assertThat(TsunamiMaxIobScaling.scaledMaxIob(7.0, 90, tsunamiSessionActive = false)).isWithin(1e-9).of(6.3)
        assertThat(TsunamiMaxIobScaling.scaledMaxIob(7.0, 90, tsunamiSessionActive = true)).isWithin(1e-9).of(6.3)
    }

    @Test fun `during a session the limit is scaled and the reason says so`() = runTest {
        setPercentage(160)
        setSession(true)

        val maxIob = limit(7.0)

        assertThat(maxIob.value()).isWithin(1e-9).of(9.1)
        assertThat(maxIob.getReasons()).contains("130% profile during Tsunami")
    }

    @Test fun `without a session the value from preferences is used`() = runTest {
        setPercentage(160)

        val maxIob = limit(7.0)

        assertThat(maxIob.value()).isWithin(1e-9).of(7.0)
        assertThat(maxIob.getReasons()).doesNotContain("during Tsunami")
    }

    @Test fun `a lower percentage is applied without a session`() = runTest {
        setPercentage(90)

        assertThat(limit(7.0).value()).isWithin(1e-9).of(6.3)
    }

    @Test fun `nothing is scaled when SMB cap scaling is off`() = runTest {
        whenever(preferences.get(BooleanKey.TsuSMBscaling)).thenReturn(false)
        setPercentage(160)
        setSession(true)

        assertThat(limit(7.0).value()).isWithin(1e-9).of(7.0)
    }

    @Test fun `a max IOB of zero stays zero`() = runTest {
        setPercentage(130)
        setSession(true)

        assertThat(limit(0.0).value()).isWithin(1e-9).of(0.0)
    }
}
