package app.aaps.pump.omnipod.common.bledriver.pod.state

import app.aaps.core.keys.interfaces.Preferences
import app.aaps.pump.omnipod.common.bledriver.pod.definition.ActivationProgress
import app.aaps.pump.omnipod.common.keys.DashStringNonPreferenceKey
import app.aaps.shared.tests.TestBase
import com.google.common.truth.Truth.assertThat
import com.google.gson.Gson
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.Mock
import org.mockito.Mockito.`when`

/**
 * Tests for [OmnipodDashPodStateManagerImpl.reloadFromStore].
 *
 * A settings import writes a new pod state to preferences while the app keeps running. The manager
 * must then read it again, or it keeps using (and later stores) the old state.
 */
class ReloadPodStateTest : TestBase() {

    @Mock lateinit var preferences: Preferences

    private lateinit var sut: OmnipodDashPodStateManagerImpl

    @BeforeEach fun setUp() {
        sut = OmnipodDashPodStateManagerImpl(aapsLogger, rxBus, preferences)
    }

    private fun storePodState(podState: OmnipodDashPodStateManagerImpl.PodState) {
        val json = Gson().toJson(podState)
        `when`(preferences.getIfExists(DashStringNonPreferenceKey.PodState)).thenReturn(json)
        `when`(preferences.get(DashStringNonPreferenceKey.PodState)).thenReturn(json)
    }

    @Test fun `without a reload the state in memory is kept`() {
        storePodState(OmnipodDashPodStateManagerImpl.PodState())
        assertThat(sut.uniqueId).isNull()

        storePodState(OmnipodDashPodStateManagerImpl.PodState(activationProgress = ActivationProgress.COMPLETED, uniqueId = 1234L))

        assertThat(sut.uniqueId).isNull()
    }

    @Test fun `after a reload the stored state is used`() {
        storePodState(OmnipodDashPodStateManagerImpl.PodState())
        assertThat(sut.uniqueId).isNull()

        storePodState(OmnipodDashPodStateManagerImpl.PodState(activationProgress = ActivationProgress.COMPLETED, uniqueId = 1234L))
        sut.reloadFromStore()

        assertThat(sut.uniqueId).isEqualTo(1234L)
        assertThat(sut.isActivationCompleted).isTrue()
    }
}
