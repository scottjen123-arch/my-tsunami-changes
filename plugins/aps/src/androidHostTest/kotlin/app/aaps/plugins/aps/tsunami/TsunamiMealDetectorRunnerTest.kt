package app.aaps.plugins.aps.tsunami

import app.aaps.core.data.iob.InMemoryGlucoseValue
import app.aaps.core.data.model.RM
import app.aaps.core.data.model.TSU
import app.aaps.core.data.model.TT
import app.aaps.core.interfaces.aps.AutosensDataStore
import app.aaps.core.interfaces.aps.GlucoseStatus
import app.aaps.core.interfaces.aps.GlucoseStatusSMB
import app.aaps.core.interfaces.aps.IobTotal
import app.aaps.core.interfaces.db.PersistenceLayer
import app.aaps.core.interfaces.notifications.NotificationId
import app.aaps.core.interfaces.profile.EffectiveProfile
import app.aaps.core.keys.BooleanKey
import app.aaps.plugins.aps.ApsStringsValues
import app.aaps.shared.tests.TestBaseWithProfile
import app.aaps.shared.tests.generatedTextResolver
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.Mock
import org.mockito.kotlin.any
import org.mockito.kotlin.anyOrNull
import org.mockito.kotlin.doAnswer
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever

/**
 * [TsunamiMealDetectorRunner] with a fake session store: what it writes, which SMB size it gives,
 * and that it never touches a session the user started. The rules themselves are in `TsunamiMealDetectorTest`.
 */
class TsunamiMealDetectorRunnerTest : TestBaseWithProfile() {

    @Mock lateinit var persistenceLayer: PersistenceLayer

    private lateinit var sut: TsunamiMealDetectorRunner
    private val profile: EffectiveProfile = mock()
    private val ads: AutosensDataStore = mock()
    private var mode = RM.Mode.CLOSED_LOOP
    private var bgTable = mutableListOf<InMemoryGlucoseValue>()

    private val minute = 60_000L
    private var clock = 0L
    private val noon = 12 * 60

    /** The session store: the running session, or null. */
    private var session: TSU? = null
    private val longs = mutableMapOf<String, Long>()
    private var level = 0

    /** The temp target store. A cancel shortens the duration, as in the real database. */
    private val targets = mutableListOf<TT>()

    @BeforeEach
    fun setup() = runTest {
        clock = now
        whenever(preferences.get(TsunamiLocalIntKey.MealDetectorMode)).thenReturn(MEAL_DETECTOR_AUTO)
        whenever(preferences.get(TsunamiLocalIntKey.MealDetectorStart)).thenReturn(7 * 60)
        whenever(preferences.get(TsunamiLocalIntKey.MealDetectorEnd)).thenReturn(24 * 60 - 1)
        whenever(preferences.get(TsunamiLocalIntKey.MealDetectorMediumSmbPercent)).thenReturn(50)
        whenever(preferences.get(TsunamiLocalDoubleKey.MealDetectorStartLevel)).thenReturn(3.5)
        whenever(preferences.get(TsunamiLocalDoubleKey.MealDetectorStepUpLevel)).thenReturn(5.0)
        whenever(preferences.get(TsunamiLocalDoubleKey.MealDetectorMinBgRise)).thenReturn(3.0)
        whenever(preferences.get(BooleanKey.ApsUseSmb)).thenReturn(true)
        whenever(preferences.get(TsunamiLocalIntKey.MealDetectorSessionMinutes)).thenReturn(150)
        whenever(preferences.get(TsunamiLocalIntKey.MealDetectorStopWaitMinutes)).thenReturn(60)
        whenever(preferences.get(TsunamiLocalBooleanKey.MealDetectorOwnSettings)).thenReturn(false)
        whenever(preferences.get(TsunamiLocalDoubleKey.MealDetectorSmbCap)).thenReturn(1.5)
        whenever(preferences.get(TsunamiLocalIntKey.MealDetectorActivityTarget)).thenReturn(80)
        whenever(preferences.get(TsunamiLocalIntKey.MealDetectorInsReqPercent)).thenReturn(70)
        for (key in TsunamiLocalLongNonKey.entries) {
            whenever(preferences.get(key)).thenAnswer { longs[key.key] ?: 0L }
            doAnswer { longs[key.key] = it.getArgument(1); null }.whenever(preferences).put(eq(key), any<Long>())
        }
        whenever(preferences.get(TsunamiLocalIntNonKey.MealDetectorAutoSessionLevel)).thenAnswer { level }
        doAnswer { level = it.getArgument(1); null }.whenever(preferences).put(eq(TsunamiLocalIntNonKey.MealDetectorAutoSessionLevel), any<Int>())

        whenever(persistenceLayer.getTsunamiActiveAt(any())).thenAnswer { session?.takeIf { s -> s.end > clock } }
        whenever(persistenceLayer.insertOrUpdateTsunami(any(), any(), any(), anyOrNull(), any())).thenAnswer {
            session = it.getArgument(0); PersistenceLayer.TransactionResult<TSU>()
        }
        whenever(persistenceLayer.cancelCurrentTsunamiModeIfAny(any(), any(), any(), anyOrNull(), any())).thenAnswer {
            session = null; PersistenceLayer.TransactionResult<TSU>()
        }
        whenever(persistenceLayer.getTemporaryTargetActiveAt(any())).thenAnswer { a ->
            val t: Long = a.getArgument(0)
            targets.filter { it.timestamp <= t && t < it.end }.maxByOrNull { it.timestamp }
        }
        whenever(persistenceLayer.getTemporaryTargetsActiveAt(any())).thenAnswer { a ->
            val t: Long = a.getArgument(0)
            targets.filter { it.timestamp <= t && t < it.end }.sortedBy { it.timestamp }
        }
        whenever(persistenceLayer.getTemporaryTargetDataFromTime(any(), any())).thenAnswer { a ->
            val t: Long = a.getArgument(0)
            targets.filter { it.timestamp >= t }.sortedBy { it.timestamp }
        }
        whenever(profile.getIsfMgdl(any())).thenReturn(40.0)
        whenever(persistenceLayer.getRunningModeActiveAt(any())).thenAnswer { RM(timestamp = 0, mode = mode, duration = 0) }
        whenever(iobCobCalculator.ads).thenReturn(ads)
        whenever(ads.getBucketedDataTableCopy()).thenAnswer { bgTable.toMutableList() }
        // No insulin activity: the gross rise is the BG delta
        whenever(iobCobCalculator.calculateFromTreatmentsAndTemps(any(), any())).thenAnswer { IobTotal(time = it.getArgument(0), activity = 0.0) }

        sut = TsunamiMealDetectorRunner(
            preferences, persistenceLayer, iobCobCalculator, notificationManager, dateUtil,
            generatedTextResolver("aps" to ApsStringsValues::textOf), aapsLogger
        )
    }

    /** BG history at the clock, newest first, with the given deltas. */
    private fun history(vararg deltas: Double): List<GlucoseStatus> =
        deltas.mapIndexed { i, d -> GlucoseStatusSMB(glucose = 140.0 - i * d, delta = d, date = clock - i * 5 * minute) }

    /** SMB size of the running scene, 100 if no scene sets one. */
    private var sceneSmbPercent = 100

    private suspend fun run(vararg deltas: Double) = sut.run(clock, noon, profile, history(*deltas), sceneSmbPercent)

    @Test
    fun `off - nothing happens`() = runTest {
        whenever(preferences.get(TsunamiLocalIntKey.MealDetectorMode)).thenReturn(MEAL_DETECTOR_OFF)

        val result = run(6.0, 6.0, 6.0)

        assertThat(result.smbPercent).isEqualTo(100)
        assertThat(result.console).isNull()
        assertThat(session).isNull()
    }

    @Test
    fun `log only - tells but does not start`() = runTest {
        whenever(preferences.get(TsunamiLocalIntKey.MealDetectorMode)).thenReturn(MEAL_DETECTOR_LOG_ONLY)

        val result = run(4.0, 4.0, 2.5)

        assertThat(session).isNull()
        assertThat(result.smbPercent).isEqualTo(100)
        assertThat(result.reason).contains("log only")
        verify(notificationManager).post(eq(NotificationId.TSUNAMI_MEAL_DETECTOR), any<String>(), any(), any<Int>(), anyOrNull(), any(), anyOrNull())
    }

    @Test
    fun `auto - starts at medium with smaller SMBs, then steps up`() = runTest {
        val start = run(4.0, 4.0, 2.5)

        assertThat(session).isNotNull()
        assertThat(session!!.tsunamiMode).isEqualTo(2)
        assertThat(session!!.duration).isEqualTo(150 * minute)
        assertThat(start.smbPercent).isEqualTo(50)
        assertThat(start.reason).contains("started Tsunami, MEDIUM")

        clock += 5 * minute
        assertThat(run(4.5, 4.0, 4.0).smbPercent).isEqualTo(50)

        clock += 5 * minute
        val up = run(6.0, 5.5, 5.0)
        assertThat(up.smbPercent).isEqualTo(100)
        assertThat(up.reason).contains("meal confirmed")

        clock += 5 * minute
        assertThat(run(0.0, 0.0, 0.0).smbPercent).isEqualTo(100)
        assertThat(session).isNotNull()
    }

    @Test
    fun `auto - a medium session ends when the rise stops`() = runTest {
        run(4.0, 4.0, 2.5)
        clock += 35 * minute

        val result = run(1.0, 0.5, 1.0)

        assertThat(session).isNull()
        assertThat(result.reason).contains("Tsunami ended")
        assertThat(result.smbPercent).isEqualTo(100)
    }

    @Test
    fun `auto - a session the user started is never changed`() = runTest {
        session = TSU(timestamp = clock - 10 * minute, duration = 120 * minute, tsunamiMode = 2)

        val result = run(6.0, 6.0, 6.0)

        assertThat(result.smbPercent).isEqualTo(100)
        verify(persistenceLayer, never()).insertOrUpdateTsunami(any(), any(), any(), anyOrNull(), any())
        verify(persistenceLayer, never()).cancelCurrentTsunamiModeIfAny(any(), any(), any(), anyOrNull(), any())
    }

    @Test
    fun `auto - after the user stopped one, no new start until the wait is over`() = runTest {
        run(4.0, 4.0, 2.5)
        session = null // stopped by the user, on the phone or the watch
        clock += 10 * minute

        val result = run(6.0, 6.0, 6.0)

        assertThat(session).isNull()
        assertThat(result.smbPercent).isEqualTo(100)
        assertThat(result.console).contains("you stopped a detected session")

        clock += 60 * minute
        run(6.0, 6.0, 6.0)
        assertThat(session!!.timestamp).isEqualTo(clock)
    }

    @Test
    fun `auto - no wait after the detector ended a session itself`() = runTest {
        run(4.0, 4.0, 2.5)
        clock += 35 * minute
        run(1.0, 0.5, 1.0)
        assertThat(session).isNull()

        clock += 10 * minute
        run(5.0, 5.0, 3.0)

        assertThat(session!!.timestamp).isEqualTo(clock)
    }

    @Test
    fun `auto - no wait after a session ran its full length`() = runTest {
        run(4.0, 4.0, 2.5)
        clock += 150 * minute

        run(5.0, 5.0, 3.0)

        assertThat(session!!.timestamp).isEqualTo(clock)
    }

    @Test
    fun `auto - a session the user starts over a detected one is not a stop`() = runTest {
        run(4.0, 4.0, 2.5)
        session = TSU(timestamp = clock + minute, duration = 60 * minute, tsunamiMode = 2)
        clock += 5 * minute
        assertThat(run(4.0, 4.0, 4.0).console).contains("a session runs")

        clock += 60 * minute
        run(5.0, 5.0, 3.0)

        assertThat(session!!.timestamp).isEqualTo(clock)
    }

    @Test
    fun `BG rise - no start when BG is flat and only the insulin makes the gross rise`() = runTest {
        // Insulin activity of 0.125 U per 5 min at an ISF of 40 takes away 5 mg/dL per 5 min
        whenever(iobCobCalculator.calculateFromTreatmentsAndTemps(any(), any())).thenAnswer { IobTotal(time = it.getArgument(0), activity = 0.025) }

        val flat = run(0.0, 0.0, 0.0)
        assertThat(session).isNull()
        assertThat(flat.console).contains("BG itself rises only 0.0, needs 3.0")

        // The same insulin, and now BG itself rises too
        clock += 5 * minute
        run(3.0, 3.0, 0.0)
        assertThat(session!!.timestamp).isEqualTo(clock)
    }

    @Test
    fun `BG rise - 0 turns the check off`() = runTest {
        whenever(preferences.get(TsunamiLocalDoubleKey.MealDetectorMinBgRise)).thenReturn(0.0)
        whenever(iobCobCalculator.calculateFromTreatmentsAndTemps(any(), any())).thenAnswer { IobTotal(time = it.getArgument(0), activity = 0.025) }

        run(0.0, 0.0, 0.0)

        assertThat(session!!.timestamp).isEqualTo(clock)
    }

    @Test
    fun `scene - no start while a scene sets a smaller SMB size, a start again after it`() = runTest {
        // The coffee scene: SMB size 80 percent
        sceneSmbPercent = 80
        val result = run(6.0, 6.0, 6.0)
        assertThat(session).isNull()
        assertThat(result.smbPercent).isEqualTo(100)
        assertThat(result.console).contains("a scene limits the SMB size")
        verify(notificationManager, never()).post(eq(NotificationId.TSUNAMI_MEAL_DETECTOR), any<String>(), any(), any<Int>(), anyOrNull(), any(), anyOrNull())

        // The scene has ended
        sceneSmbPercent = 100
        clock += 5 * minute
        run(6.0, 6.0, 6.0)
        assertThat(session!!.timestamp).isEqualTo(clock)
    }

    @Test
    fun `scene - no alert in log only mode while a scene sets a smaller SMB size`() = runTest {
        whenever(preferences.get(TsunamiLocalIntKey.MealDetectorMode)).thenReturn(MEAL_DETECTOR_LOG_ONLY)
        sceneSmbPercent = 80

        val result = run(6.0, 6.0, 6.0)

        assertThat(result.reason).isNull()
        verify(notificationManager, never()).post(eq(NotificationId.TSUNAMI_MEAL_DETECTOR), any<String>(), any(), any<Int>(), anyOrNull(), any(), anyOrNull())
    }

    private fun target(reason: TT.Reason, startedMinutesAgo: Long, minutes: Long) =
        TT(timestamp = clock - startedMinutesAgo * minute, reason = reason, highTarget = 120.0, lowTarget = 120.0, duration = minutes * minute)

    @Test
    fun `temp target - no start while a hypo target runs`() = runTest {
        targets += target(TT.Reason.HYPOGLYCEMIA, startedMinutesAgo = 20, minutes = 60)

        val result = run(6.0, 6.0, 6.0)

        assertThat(session).isNull()
        assertThat(result.console).contains("hypo or activity temp target")
    }

    @Test
    fun `temp target - no start for 60 min after a hypo target was cancelled`() = runTest {
        // Set 50 min ago for 60 min, cancelled after 20 min when BG started to rise
        targets += target(TT.Reason.HYPOGLYCEMIA, startedMinutesAgo = 50, minutes = 20)
        run(6.0, 6.0, 6.0)
        assertThat(session).isNull()

        // 60 min after the cancel
        clock += 30 * minute
        run(6.0, 6.0, 6.0)
        assertThat(session!!.timestamp).isEqualTo(clock)
    }

    @Test
    fun `temp target - an activity target blocks a start, also when a scene set it`() = runTest {
        targets += target(TT.Reason.ACTIVITY, startedMinutesAgo = 100, minutes = 120)
        run(6.0, 6.0, 6.0)
        assertThat(session).isNull()

        // 20 min after it ended
        clock += 40 * minute
        run(6.0, 6.0, 6.0)
        assertThat(session).isNull()
    }

    @Test
    fun `temp target - an eating soon target does not block a start`() = runTest {
        targets += target(TT.Reason.EATING_SOON, startedMinutesAgo = 20, minutes = 45)

        run(6.0, 6.0, 6.0)

        assertThat(session!!.timestamp).isEqualTo(clock)
    }

    @Test
    fun `temp target - a hypo target stops counting when a newer target replaces it`() = runTest {
        // Planned for 2 h, but an eating soon target replaced it 65 min ago
        targets += target(TT.Reason.HYPOGLYCEMIA, startedMinutesAgo = 90, minutes = 120)
        targets += target(TT.Reason.EATING_SOON, startedMinutesAgo = 65, minutes = 120)

        run(6.0, 6.0, 6.0)

        assertThat(session!!.timestamp).isEqualTo(clock)
    }

    @Test
    fun `temp target - a hypo target ends a detected session, and that is not a stop`() = runTest {
        run(4.0, 4.0, 2.5)
        clock += 10 * minute
        run(6.0, 5.5, 5.0)
        assertThat(level).isEqualTo(TsunamiMealDetector.Level.HIGH.ordinal)

        clock += 5 * minute
        targets += target(TT.Reason.HYPOGLYCEMIA, startedMinutesAgo = 1, minutes = 60)
        val result = run(6.0, 6.0, 6.0)

        assertThat(session).isNull()
        assertThat(result.reason).contains("hypo temp target set, Tsunami ended")
        assertThat(result.smbPercent).isEqualTo(100)
        assertThat(longs[TsunamiLocalLongNonKey.MealDetectorLastUserStop.key] ?: 0L).isEqualTo(0L)
        assertThat(longs[TsunamiLocalLongNonKey.MealDetectorAutoSessionStart.key]).isEqualTo(0L)
    }

    @Test
    fun `temp target - a hypo target does not end a session the user started`() = runTest {
        session = TSU(timestamp = clock - 10 * minute, duration = 120 * minute, tsunamiMode = 2)
        targets += target(TT.Reason.HYPOGLYCEMIA, startedMinutesAgo = 1, minutes = 60)

        run(6.0, 6.0, 6.0)

        assertThat(session).isNotNull()
        verify(persistenceLayer, never()).cancelCurrentTsunamiModeIfAny(any(), any(), any(), anyOrNull(), any())
    }

    @Test
    fun `log only - a second alert only after 90 min`() = runTest {
        whenever(preferences.get(TsunamiLocalIntKey.MealDetectorMode)).thenReturn(MEAL_DETECTOR_LOG_ONLY)
        run(4.0, 4.0, 2.5)

        clock += 30 * minute
        assertThat(run(5.0, 5.0, 3.0).console).contains("alert less than 90 min ago")
        clock += 65 * minute
        assertThat(run(5.0, 5.0, 3.0).console).contains("would start")
    }

    @Test
    fun `auto - a log only alert does not block a start after switching to auto`() = runTest {
        whenever(preferences.get(TsunamiLocalIntKey.MealDetectorMode)).thenReturn(MEAL_DETECTOR_LOG_ONLY)
        run(4.0, 4.0, 2.5)
        whenever(preferences.get(TsunamiLocalIntKey.MealDetectorMode)).thenReturn(MEAL_DETECTOR_AUTO)
        clock += 10 * minute

        run(5.0, 5.0, 3.0)

        assertThat(session!!.timestamp).isEqualTo(clock)
    }

    @Test
    fun `auto - no start when the pump is disconnected`() = runTest {
        mode = RM.Mode.DISCONNECTED_PUMP

        val result = run(6.0, 6.0, 6.0)

        assertThat(session).isNull()
        assertThat(result.console).contains("not in closed loop")
    }

    @Test
    fun `auto - no start on values that fill a CGM gap`() = runTest {
        bgTable = mutableListOf(
            InMemoryGlucoseValue(timestamp = clock, value = 160.0),
            InMemoryGlucoseValue(timestamp = clock - 5 * minute, value = 150.0, filledGap = true),
            InMemoryGlucoseValue(timestamp = clock - 10 * minute, value = 140.0, filledGap = true)
        )

        val result = run(6.0, 6.0, 6.0)

        assertThat(session).isNull()
        assertThat(result.console).contains("gap in BG data")
    }

    @Test
    fun `own settings - off, a detected session uses the normal Tsunami settings`() = runTest {
        run(4.0, 4.0, 2.5)

        assertThat(session).isNotNull()
        assertThat(run(4.5, 4.0, 4.0).ownSettings).isNull()
    }

    @Test
    fun `own settings - on, used for the whole detected session and not after it`() = runTest {
        whenever(preferences.get(TsunamiLocalBooleanKey.MealDetectorOwnSettings)).thenReturn(true)
        val own = TsunamiMealDetectorRunner.OwnSettings(smbCap = 1.5, activityTarget = 80, insulinReqPercent = 70)

        val start = run(4.0, 4.0, 2.5)
        assertThat(start.ownSettings).isEqualTo(own)
        assertThat(start.console).contains("own settings")

        clock += 5 * minute
        assertThat(run(4.5, 4.0, 4.0).ownSettings).isEqualTo(own)
        clock += 5 * minute
        assertThat(run(6.0, 5.5, 5.0).ownSettings).isEqualTo(own) // steps up
        clock += 5 * minute
        assertThat(run(6.0, 6.0, 6.0).ownSettings).isEqualTo(own) // high confidence

        clock += 150 * minute // the session is over
        assertThat(run(0.0, 0.0, 0.0).ownSettings).isNull()
    }

    @Test
    fun `own settings - not when the detector ends the session`() = runTest {
        whenever(preferences.get(TsunamiLocalBooleanKey.MealDetectorOwnSettings)).thenReturn(true)
        run(4.0, 4.0, 2.5)
        clock += 35 * minute

        val end = run(1.0, 0.5, 1.0)

        assertThat(session).isNull()
        assertThat(end.ownSettings).isNull()
    }

    @Test
    fun `own settings - never for a session the user started or in log only mode`() = runTest {
        whenever(preferences.get(TsunamiLocalBooleanKey.MealDetectorOwnSettings)).thenReturn(true)
        session = TSU(timestamp = clock - 10 * minute, duration = 120 * minute, tsunamiMode = 2)
        assertThat(run(6.0, 6.0, 6.0).ownSettings).isNull()

        session = null
        whenever(preferences.get(TsunamiLocalIntKey.MealDetectorMode)).thenReturn(MEAL_DETECTOR_LOG_ONLY)
        assertThat(run(4.0, 4.0, 2.5).ownSettings).isNull()
    }
}
