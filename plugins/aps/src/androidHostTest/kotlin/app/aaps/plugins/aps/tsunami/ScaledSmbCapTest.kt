package app.aaps.plugins.aps.tsunami

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

/**
 * Tests for [TsunamiSmbCapScaling.scaledSmbCap].
 *
 * The scaling used to multiply by the percentage of the effective profile, which is always 100 %, so
 * "Enable SMB cap scaling" did nothing. These tests pin the behaviour for the percentage the user set.
 */
class ScaledSmbCapTest {

    @Test fun `100 percent keeps the cap`() {
        assertThat(TsunamiSmbCapScaling.scaledSmbCap(1.5, 100)).isWithin(1e-9).of(1.5)
    }

    @Test fun `a higher percentage makes the cap bigger`() {
        assertThat(TsunamiSmbCapScaling.scaledSmbCap(1.5, 120)).isWithin(1e-9).of(1.8)
    }

    @Test fun `a lower percentage makes the cap smaller`() {
        assertThat(TsunamiSmbCapScaling.scaledSmbCap(1.5, 80)).isWithin(1e-9).of(1.2)
    }

    @Test fun `the cap grows only up to 130 percent`() {
        assertThat(TsunamiSmbCapScaling.scaledSmbCap(1.5, 130)).isWithin(1e-9).of(1.95)
        assertThat(TsunamiSmbCapScaling.scaledSmbCap(1.5, 200)).isWithin(1e-9).of(1.95)
    }

    @Test fun `the cap shrinks without a lower limit`() {
        assertThat(TsunamiSmbCapScaling.scaledSmbCap(1.5, 50)).isWithin(1e-9).of(0.75)
    }
}
