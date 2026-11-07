package app.aaps.wear.interaction.actions

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

/** Local change: the rules of the Tsunami screen on the watch. */
internal class TsunamiInputRulesTest {

    @Test
    fun `the insulin steps are the phone's increments`() {
        assertThat(TsunamiInputRules.insulinSteps(0.5, 1.0, 2.0)).containsExactly(0.5, 1.0, 2.0).inOrder()
    }

    @Test
    fun `an increment of zero cannot make a step that does nothing`() {
        assertThat(TsunamiInputRules.insulinSteps(0.0, 1.0, 2.0)[0]).isEqualTo(0.1)
    }

    @Test
    fun `the duration starts at the phone's default`() {
        assertThat(TsunamiInputRules.startDuration(205)).isEqualTo(205)
    }

    @Test
    fun `a phone default of zero still gives a duration, because zero would not start a session`() {
        assertThat(TsunamiInputRules.startDuration(0)).isGreaterThan(0)
    }

    @Test
    fun `the duration stays inside the allowed range`() {
        assertThat(TsunamiInputRules.startDuration(999)).isEqualTo(300)
        assertThat(TsunamiInputRules.startDuration(1)).isEqualTo(5)
    }

    @Test
    fun `time left is rounded up, and is zero once the session has ended`() {
        assertThat(TsunamiInputRules.minutesLeft(activeUntil = 90_001L, now = 0L)).isEqualTo(2)
        assertThat(TsunamiInputRules.minutesLeft(activeUntil = 1_000L, now = 1_000L)).isEqualTo(0)
        assertThat(TsunamiInputRules.minutesLeft(activeUntil = 0L, now = 5_000L)).isEqualTo(0)
    }
}
