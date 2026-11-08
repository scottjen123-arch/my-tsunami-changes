package app.aaps.plugins.aps.tsunami

import app.aaps.core.data.model.ActiveSceneState
import app.aaps.core.data.model.Scene
import app.aaps.core.data.model.SceneAction
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
 * [TsunamiSceneMaxIob.apply] together with the other max IOB steps, in the same order as
 * `TsunamiPlugin.applyMaxIOBConstraints`: the profile percentage scaling, the value in preferences,
 * then the scene. The scene can only lower the result.
 */
class TsunamiSceneMaxIobConstraintTest : TestBaseWithProfile() {

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

    private fun scene(percent: Int) = ActiveSceneState(
        scene = Scene(id = "r", name = "Recovery", actions = listOf(SceneAction.MaxIob(percent = percent))),
        activatedAt = now - 60_000L,
        durationMs = 3 * 3_600_000L
    )

    private suspend fun limit(maxIobPref: Double, state: ActiveSceneState?): Constraint<Double> {
        val maxIob: Constraint<Double> = ConstraintObject(Double.MAX_VALUE, aapsLogger)
        if (!TsunamiMaxIobScaling.applyScaled(maxIob, maxIobPref, preferences, profileFunction, persistenceLayer, dateUtil, text, this))
            maxIob.setIfSmaller(maxIobPref, "max value in preferences", this)
        TsunamiSceneMaxIob.apply(maxIob, maxIobPref, state, now, text, this)
        return maxIob
    }

    @Test fun `no scene keeps the value from preferences`() = runTest {
        assertThat(limit(7.0, null).value()).isWithin(1e-9).of(7.0)
    }

    @Test fun `scene lowers max IOB and the reason says so`() = runTest {
        val maxIob = limit(7.0, scene(60))

        assertThat(maxIob.value()).isWithin(1e-9).of(4.2)
        assertThat(maxIob.getReasons()).contains("scene max IOB 60%")
    }

    @Test fun `scene at 0 percent gives zero`() = runTest {
        assertThat(limit(7.0, scene(0)).value()).isWithin(1e-9).of(0.0)
    }

    @Test fun `scene never raises max IOB above a lower profile percentage`() = runTest {
        setPercentage(90)

        // 90 % profile gives 6.3, the scene at 100 % would be 7.0
        assertThat(limit(7.0, scene(100)).value()).isWithin(1e-9).of(6.3)
    }

    @Test fun `scene wins over a higher limit from a session`() = runTest {
        setPercentage(130)
        setSession(true)

        // 130 % during a session gives 9.1, the scene is 60 % of the setting
        assertThat(limit(7.0, scene(60)).value()).isWithin(1e-9).of(4.2)
    }
}
