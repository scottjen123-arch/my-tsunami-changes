package app.aaps.plugins.aps.tsunami

import kotlin.test.Test
import kotlin.test.assertEquals

/** The rule of [TsunamiHighBgMaxIob]. In commonTest, so it also runs on the iOS simulator. */
class TsunamiHighBgMaxIobRuleTest {

    private fun calculate(
        normalMaxIob: Double = 6.5,
        extra: Double = 1.5,
        hardLimit: Double = 22.0,
        bg: Double = 200.0,
        delta: Double = 5.0,
        tsunamiModeId: Int = 2
    ) = TsunamiHighBgMaxIob.calculate(normalMaxIob, extra, hardLimit, bg, delta, tsunamiModeId)

    @Test
    fun session_high_and_rising_adds_the_extra() = assertEquals(8.0, calculate())

    @Test
    fun exactly_180_gets_no_extra() = assertEquals(6.5, calculate(bg = 180.0))

    @Test
    fun just_above_180_gets_the_extra() = assertEquals(8.0, calculate(bg = 180.1))

    @Test
    fun flat_bg_gets_no_extra() = assertEquals(6.5, calculate(delta = 0.0))

    @Test
    fun falling_bg_gets_no_extra() = assertEquals(6.5, calculate(delta = -3.0))

    @Test
    fun wave_gets_no_extra() = assertEquals(6.5, calculate(tsunamiModeId = 1))

    @Test
    fun plain_oref_gets_no_extra() = assertEquals(6.5, calculate(tsunamiModeId = 0))

    @Test
    fun hard_limit_wins() = assertEquals(7.0, calculate(hardLimit = 7.0))

    @Test
    fun never_lower_than_normal_when_hard_limit_is_below_it() = assertEquals(6.5, calculate(hardLimit = 5.0))
}
