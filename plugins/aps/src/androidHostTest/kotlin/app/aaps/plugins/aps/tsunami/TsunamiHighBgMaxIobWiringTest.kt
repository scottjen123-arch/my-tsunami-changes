package app.aaps.plugins.aps.tsunami

import app.aaps.core.interfaces.aps.AutosensResult
import app.aaps.core.interfaces.aps.CurrentTemp
import app.aaps.core.interfaces.aps.GlucoseStatus
import app.aaps.core.interfaces.aps.GlucoseStatusSMB
import app.aaps.core.interfaces.aps.IobTotal
import app.aaps.core.interfaces.aps.MealData
import app.aaps.core.interfaces.aps.OapsProfileTsunami
import app.aaps.core.interfaces.aps.RT
import app.aaps.shared.tests.TestBaseWithProfile
import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.kotlin.whenever

/**
 * Runs the real dosing code, to prove that [TsunamiHighBgMaxIob] is still connected to `DetermineBasalTsunami`.
 *
 * The connection is a few lines inside an upstream file. If a merge with upstream loses one of them, the feature
 * would stop working with no visible sign. These tests fail in that case. The rule itself is tested in
 * `TsunamiHighBgMaxIobRuleTest`.
 */
class TsunamiHighBgMaxIobWiringTest : TestBaseWithProfile() {

    private lateinit var sut: DetermineBasalTsunami

    private val currentTime = 1_790_000_000_000L
    private val stepMs = 5 * 60_000L
    private val normalMaxIob = 7.0
    private val extra = 2.0

    @BeforeEach
    fun setup() {
        sut = DetermineBasalTsunami(profileUtil, fabricPrivacy, TsunamiHighBgMaxIob(preferences, hardLimits))
    }

    private fun switchOn() {
        whenever(preferences.get(TsunamiLocalBooleanKey.HighBgExtraMaxIob)).thenReturn(true)
        whenever(preferences.get(TsunamiLocalDoubleKey.HighBgExtraMaxIobAmount)).thenReturn(extra)
    }

    /** History (newest first) ending at [bg], changing by [delta] per reading, 19 readings (90 min). */
    private fun history(bg: Double, delta: Double): List<GlucoseStatus> =
        List(19) { i -> GlucoseStatusSMB(glucose = bg - i * delta, delta = delta, shortAvgDelta = delta, longAvgDelta = delta, date = currentTime - i * stepMs) }

    private fun iobArray(iob: Double) = Array(48) { i ->
        val time = currentTime + i * stepMs
        IobTotal(
            time = time,
            iob = iob,
            activity = 0.1,
            lastBolusTime = currentTime - 3_600_000L,
            iobWithZeroTemp = IobTotal(time = time, iob = iob, activity = 0.1)
        )
    }

    private fun profile(tsunamiModeId: Int) = OapsProfileTsunami(
        dia = 9.0,
        min_5m_carbimpact = 0.0,
        max_iob = normalMaxIob,
        max_daily_basal = 1.0,
        max_basal = 4.0,
        min_bg = 100.0,
        max_bg = 100.0,
        target_bg = 100.0,
        carb_ratio = 14.0,
        sens = 40.0,
        autosens_adjust_targets = false,
        max_daily_safety_multiplier = 3.0,
        current_basal_safety_multiplier = 4.0,
        high_temptarget_raises_sensitivity = false,
        low_temptarget_lowers_sensitivity = false,
        sensitivity_raises_target = false,
        resistance_lowers_target = false,
        adv_target_adjustments = false,
        exercise_mode = false,
        half_basal_exercise_target = 160,
        maxCOB = 120,
        skip_neutral_temps = false,
        remainingCarbsCap = 90,
        enableUAM = true,
        A52_risk_enable = false,
        SMBInterval = 3,
        enableSMB_with_COB = true,
        enableSMB_with_temptarget = true,
        allowSMB_with_high_temptarget = true,
        enableSMB_always = true,
        enableSMB_after_carbs = true,
        maxSMBBasalMinutes = 30,
        maxUAMSMBBasalMinutes = 30,
        bolus_increment = 0.1,
        carbsReqThreshold = 1,
        current_basal = 1.0,
        temptargetSet = false,
        autosens_max = 1.2,
        out_units = "mg/dl",
        lgsThreshold = null,
        variable_sens = 40.0,
        insulinDivisor = 75,
        TDD = 40.0,
        tsunamiModeID = tsunamiModeId,
        tsunamiModeActivationTime = null,
        peakTime = 75.0,
        PDmodel = false,
        insConc = 1.0,
        percentage = 100,
        enableWaveMode = true,
        waveActiveHours = true,
        waveUseSMBCap = true,
        SMBcap = 1.5,
        insulinReqPCT = 0.6,
        activityTarget = 1.0,
        deltaReductionPCT = 1.0,
        futureActivity = 0.1,
        activityPredTime = 75.0,
        sensorLagActivity = 0.1,
        historicActivity = 0.1,
        currentActivity = 0.1,
        lastBolus = 0.0
    )

    private fun run(bg: Double = 220.0, delta: Double = 8.0, iob: Double = 7.5, tsunamiModeId: Int = 2): RT {
        val history = history(bg, delta)
        return sut.determine_basal(
            glucose_status = history[0],
            currenttemp = CurrentTemp(0, 0.0, null),
            iob_data_array = iobArray(iob),
            profile = profile(tsunamiModeId),
            autosens_data = AutosensResult(ratio = 1.0),
            meal_data = MealData(lastBolusTime = currentTime - 3_600_000L),
            microBolusAllowed = true,
            currentTime = currentTime,
            flatBGsDetected = false,
            dynIsfMode = false,
            recentGlucoseHistory = history,
            rawGlucoseHistory = history
        )
    }

    private fun RT.console(): String = (consoleLog.orEmpty() + consoleError.orEmpty()).joinToString("\n")

    private fun RT.stoppedByMaxIob(): Boolean = reason.contains("> max_iob")

    private fun RT.headroomLine(): String? = consoleError.orEmpty().firstOrNull { it.startsWith("IOB headroom factor") }

    @Test
    fun `switch off - IOB above normal max IOB stops dosing`() {
        val rT = run()

        assertThat(rT.stoppedByMaxIob()).isTrue()
        assertThat(rT.console()).doesNotContain("High BG extra max IOB")
    }

    @Test
    fun `switch on - session, high and rising - the extra room is used`() {
        switchOn()

        val rT = run()

        assertThat(rT.stoppedByMaxIob()).isFalse()
        assertThat(rT.console()).contains("High BG extra max IOB: normal 7.0, now 9.0")
    }

    @Test
    fun `switch on - dose size keeps using the normal max IOB`() {
        val off = run().headroomLine()
        switchOn()

        val on = run().headroomLine()

        assertThat(off).isNotNull()
        assertThat(on).isEqualTo(off)
    }

    @Test
    fun `switch on - BG not above 180 - no extra room`() {
        switchOn()

        val rT = run(bg = 180.0)

        assertThat(rT.stoppedByMaxIob()).isTrue()
        assertThat(rT.console()).doesNotContain("High BG extra max IOB")
    }

    @Test
    fun `switch on - Wave - no extra room`() {
        switchOn()

        val rT = run(tsunamiModeId = 1)

        assertThat(rT.console()).doesNotContain("High BG extra max IOB")
    }

    @Test
    fun `switch on - BG falling - no extra room`() {
        switchOn()

        val rT = run(delta = -3.0)

        assertThat(rT.console()).doesNotContain("High BG extra max IOB")
    }
}
