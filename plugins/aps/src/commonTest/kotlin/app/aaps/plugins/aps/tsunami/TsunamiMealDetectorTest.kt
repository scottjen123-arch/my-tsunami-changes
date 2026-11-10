package app.aaps.plugins.aps.tsunami

import app.aaps.plugins.aps.tsunami.TsunamiMealDetector.AutoSession
import app.aaps.plugins.aps.tsunami.TsunamiMealDetector.Decision
import app.aaps.plugins.aps.tsunami.TsunamiMealDetector.Input
import app.aaps.plugins.aps.tsunami.TsunamiMealDetector.Level
import app.aaps.plugins.aps.tsunami.TsunamiMealDetector.Reading
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull

/** The rules of [TsunamiMealDetector]. In commonTest, so it also runs on the iOS simulator. */
class TsunamiMealDetectorTest {

    private val now = 1_790_000_000_000L
    private val minute = 60_000L

    /**
     * Readings 5 min apart, newest first, with the given gross rises.
     * [net] is the change of BG itself for every reading. Null: no insulin on board, so it is the gross rise.
     */
    private fun readings(vararg rises: Double, bg: Double = 130.0, age: Long = 0, net: Double? = null) =
        rises.mapIndexed { i, r -> Reading(now - age * minute - i * 5 * minute, bg, r, net ?: r) }

    private fun input(
        readings: List<Reading>,
        minuteOfDay: Int = 12 * 60,
        lowest: Double? = 110.0,
        sessionRunning: Boolean = false,
        auto: AutoSession? = null,
        lastUserStop: Long = 0,
        stopWaitMinutes: Int = 60,
        lastLogOnlyAlert: Long = 0,
        raisedTargetEnd: Long = 0,
        hypoTargetRunning: Boolean = false,
        smbAllowed: Boolean = true,
        sceneLimitsSmb: Boolean = false,
        closedLoop: Boolean = true,
        bgGap: Boolean = false,
        minBgRise: Double = 3.0
    ) = Input(
        now = now,
        minuteOfDay = minuteOfDay,
        windowStart = 7 * 60,
        windowEnd = 24 * 60 - 1,
        readings = readings,
        lowestRecentBg = lowest,
        sessionRunning = sessionRunning,
        autoSession = auto,
        lastUserStop = lastUserStop,
        stopWaitMinutes = stopWaitMinutes,
        lastLogOnlyAlert = lastLogOnlyAlert,
        raisedTargetEnd = raisedTargetEnd,
        hypoTargetRunning = hypoTargetRunning,
        smbAllowed = smbAllowed,
        sceneLimitsSmb = sceneLimitsSmb,
        closedLoop = closedLoop,
        bgGap = bgGap,
        startLevel = 3.5,
        minBgRise = minBgRise,
        stepUpLevel = 5.0
    )

    @Test
    fun fastRiseStarts() {
        val decision = TsunamiMealDetector.decide(input(readings(4.0, 3.6, 2.2)))
        assertIs<Decision.Start>(decision)
        assertEquals(3.8, decision.grossRise, 1e-9)
    }

    @Test
    fun veryFastRiseStillStartsAtMedium() {
        // Start has no level any more: every start is medium, the runner gives the medium SMB size
        assertIs<Decision.Start>(TsunamiMealDetector.decide(input(readings(6.0, 5.5, 5.0))))
    }

    @Test
    fun slowRiseDoesNotStart() {
        assertIs<Decision.None>(TsunamiMealDetector.decide(input(readings(3.0, 3.2, 3.0))))
    }

    @Test
    fun oneNoisyReadingDoesNotStart() {
        // Average 4.5, but the older reading is far below the level
        assertIs<Decision.None>(TsunamiMealDetector.decide(input(readings(8.0, 1.0))))
    }

    @Test
    fun theReadingBeforeTheLastTwoMustAlsoShowARise() {
        // Average of the newest 2 is 4.0, but 10 min ago there was no rise yet
        assertIs<Decision.None>(TsunamiMealDetector.decide(input(readings(4.0, 4.0, 1.0))))
        // 60% of the start level is enough for it
        assertIs<Decision.Start>(TsunamiMealDetector.decide(input(readings(4.0, 4.0, 2.1))))
        // Only 2 readings
        assertIs<Decision.None>(TsunamiMealDetector.decide(input(readings(6.0, 6.0))))
    }

    @Test
    fun noStartWhenOnlyTheInsulinMakesTheGrossRise() {
        // A lot of insulin on board: gross rise 4 to 6, but BG itself is flat. This started a session while BG was flat.
        assertIs<Decision.None>(TsunamiMealDetector.decide(input(readings(4.1, 5.5, 4.4, net = 0.0))))
        // BG rises 2 per 5 min: still below the 3 that is needed
        assertIs<Decision.None>(TsunamiMealDetector.decide(input(readings(5.5, 5.5, 5.5, net = 2.0))))
        assertIs<Decision.Start>(TsunamiMealDetector.decide(input(readings(5.5, 5.5, 5.5, net = 3.0))))
        // 0 turns the check off
        assertIs<Decision.Start>(TsunamiMealDetector.decide(input(readings(4.1, 5.5, 4.4, net = 0.0), minBgRise = 0.0)))
    }

    @Test
    fun theBgRiseIsTheAverageOfTheNewestTwoReadings() {
        val mixed = listOf(Reading(now, 130.0, 6.0, 5.0), Reading(now - 5 * minute, 130.0, 6.0, 1.0), Reading(now - 10 * minute, 130.0, 6.0, 0.0))
        assertIs<Decision.Start>(TsunamiMealDetector.decide(input(mixed)))
        assertIs<Decision.None>(TsunamiMealDetector.decide(input(mixed, minBgRise = 3.5)))
    }

    @Test
    fun aRunningDetectedSessionDoesNotNeedTheBgRise() {
        // Step up and end only look at the gross rise: insulin given in the session hides the rise of BG itself
        val auto = AutoSession(start = now - 10 * minute, level = Level.MEDIUM)
        assertIs<Decision.StepUp>(TsunamiMealDetector.decide(input(readings(5.5, 5.0, 5.0, net = 0.0), sessionRunning = true, auto = auto)))
    }

    @Test
    fun noStartAtNightOrAfterALowOrWhenBgIsLow() {
        val fast = readings(5.0, 5.0, 5.0)
        assertIs<Decision.None>(TsunamiMealDetector.decide(input(fast, minuteOfDay = 3 * 60)))
        assertIs<Decision.None>(TsunamiMealDetector.decide(input(fast, lowest = 72.0)))
        assertIs<Decision.None>(TsunamiMealDetector.decide(input(readings(5.0, 5.0, 5.0, bg = 85.0))))
    }

    @Test
    fun noStartWithOldBgDataOrAGap() {
        assertIs<Decision.None>(TsunamiMealDetector.decide(input(readings(5.0, 5.0, 5.0, age = 10))))
        val gap = listOf(Reading(now, 130.0, 5.0, 5.0), Reading(now - 5 * minute, 130.0, 5.0, 5.0), Reading(now - 25 * minute, 130.0, 5.0, 5.0))
        assertIs<Decision.None>(TsunamiMealDetector.decide(input(gap)))
    }

    @Test
    fun noStartWhenASessionRunsOrSmbIsOff() {
        val fast = readings(5.0, 5.0, 5.0)
        assertIs<Decision.None>(TsunamiMealDetector.decide(input(fast, sessionRunning = true)))
        assertIs<Decision.None>(TsunamiMealDetector.decide(input(fast, smbAllowed = false)))
    }

    @Test
    fun waitOnlyAfterTheUserStoppedASession() {
        val fast = readings(5.0, 5.0, 5.0)
        assertIs<Decision.None>(TsunamiMealDetector.decide(input(fast, lastUserStop = now - 30 * minute)))
        assertIs<Decision.Start>(TsunamiMealDetector.decide(input(fast, lastUserStop = now - 60 * minute)))
        // 0 means no wait
        assertIs<Decision.Start>(TsunamiMealDetector.decide(input(fast, lastUserStop = now - 1 * minute, stopWaitMinutes = 0)))
        // Without a stop by the user there is no wait at all
        assertIs<Decision.Start>(TsunamiMealDetector.decide(input(fast)))
    }

    @Test
    fun logOnlyGivesOneAlertPerRise() {
        val fast = readings(5.0, 5.0, 5.0)
        assertIs<Decision.None>(TsunamiMealDetector.decide(input(fast, lastLogOnlyAlert = now - 60 * minute)))
        assertIs<Decision.Start>(TsunamiMealDetector.decide(input(fast, lastLogOnlyAlert = now - 95 * minute)))
    }

    @Test
    fun noStartDuringOrUpTo60MinAfterAHypoOrActivityTarget() {
        val fast = readings(5.0, 5.0, 5.0)
        // Runs now
        assertIs<Decision.None>(TsunamiMealDetector.decide(input(fast, raisedTargetEnd = now)))
        // Cancelled 30 min ago, when BG started to rise
        assertIs<Decision.None>(TsunamiMealDetector.decide(input(fast, raisedTargetEnd = now - 30 * minute)))
        // Ended 60 min ago or longer
        assertIs<Decision.Start>(TsunamiMealDetector.decide(input(fast, raisedTargetEnd = now - 60 * minute)))
        assertIs<Decision.Start>(TsunamiMealDetector.decide(input(fast, raisedTargetEnd = now - 70 * minute)))
    }

    @Test
    fun hypoTargetEndsADetectedSessionAtAnyLevel() {
        for (level in Level.entries) {
            val auto = AutoSession(start = now - 10 * minute, level = level)
            // Even while BG still rises fast and with a gap in the BG data
            assertIs<Decision.EndForHypoTarget>(TsunamiMealDetector.decide(input(readings(6.0, 6.0, 6.0), sessionRunning = true, auto = auto, hypoTargetRunning = true)))
            assertIs<Decision.EndForHypoTarget>(TsunamiMealDetector.decide(input(readings(6.0, 6.0, 6.0), sessionRunning = true, auto = auto, hypoTargetRunning = true, bgGap = true)))
        }
    }

    @Test
    fun hypoTargetDoesNotEndASessionOfTheUser() {
        assertIs<Decision.None>(TsunamiMealDetector.decide(input(readings(6.0, 6.0), sessionRunning = true, hypoTargetRunning = true)))
    }

    @Test
    fun noStartWhileASceneLimitsTheSmbSize() {
        assertIs<Decision.None>(TsunamiMealDetector.decide(input(readings(6.0, 6.0, 6.0), sceneLimitsSmb = true)))
    }

    @Test
    fun aSceneWithSmallerSmbsDoesNotChangeARunningDetectedSession() {
        // The session keeps its level. The scene SMB size already applies to its SMBs.
        val auto = AutoSession(start = now - 10 * minute, level = Level.MEDIUM)
        assertIs<Decision.StepUp>(TsunamiMealDetector.decide(input(readings(5.5, 5.0, 5.0), sessionRunning = true, auto = auto, sceneLimitsSmb = true)))
    }

    @Test
    fun noStartOutsideClosedLoopOrWithFilledBgGap() {
        val fast = readings(5.0, 5.0, 5.0)
        assertIs<Decision.None>(TsunamiMealDetector.decide(input(fast, closedLoop = false)))
        assertIs<Decision.None>(TsunamiMealDetector.decide(input(fast, bgGap = true)))
    }

    @Test
    fun filledBgGapNeitherConfirmsNorEndsASession() {
        val auto = AutoSession(start = now - 40 * minute, level = Level.MEDIUM)
        assertIs<Decision.None>(TsunamiMealDetector.decide(input(readings(6.0, 6.0, 6.0), sessionRunning = true, auto = auto, bgGap = true)))
        assertIs<Decision.None>(TsunamiMealDetector.decide(input(readings(0.0, 0.0, 0.0), sessionRunning = true, auto = auto, bgGap = true)))
    }

    @Test
    fun mediumSessionStepsUpWhenTwoNewReadingsConfirmTheRise() {
        // Started 10 min ago: the readings now and 5 min ago are new
        val auto = AutoSession(start = now - 10 * minute, level = Level.MEDIUM)
        val decision = TsunamiMealDetector.decide(input(readings(5.5, 5.0, 5.0), sessionRunning = true, auto = auto))
        assertIs<Decision.StepUp>(decision)
        assertEquals(5.25, decision.grossRise, 1e-9)
    }

    @Test
    fun readingsFromBeforeTheStartDoNotConfirm() {
        // Started 5 min ago: only one reading is new, however fast the old ones were
        val auto = AutoSession(start = now - 5 * minute, level = Level.MEDIUM)
        assertIs<Decision.None>(TsunamiMealDetector.decide(input(readings(6.0, 9.0, 9.0), sessionRunning = true, auto = auto)))
        // The reading taken just before the start is not new either
        val justStarted = AutoSession(start = now - 4 * minute, level = Level.MEDIUM)
        assertIs<Decision.None>(TsunamiMealDetector.decide(input(readings(6.0, 9.0), sessionRunning = true, auto = justStarted)))
    }

    @Test
    fun oneJumpDoesNotConfirm() {
        // Average 5.5, but one of the new readings is below 60% of the high level
        val auto = AutoSession(start = now - 10 * minute, level = Level.MEDIUM)
        assertIs<Decision.None>(TsunamiMealDetector.decide(input(readings(9.0, 2.0), sessionRunning = true, auto = auto)))
    }

    @Test
    fun mediumSessionEndsOnlyAfter30MinWhenTheRiseStops() {
        val flat = readings(1.0, 0.5, 1.5)
        val early = AutoSession(start = now - 20 * minute, level = Level.MEDIUM)
        assertIs<Decision.None>(TsunamiMealDetector.decide(input(flat, sessionRunning = true, auto = early)))
        val late = AutoSession(start = now - 30 * minute, level = Level.MEDIUM)
        assertIs<Decision.End>(TsunamiMealDetector.decide(input(flat, sessionRunning = true, auto = late)))
    }

    @Test
    fun highSessionIsLeftAlone() {
        val auto = AutoSession(start = now - 40 * minute, level = Level.HIGH)
        assertIs<Decision.None>(TsunamiMealDetector.decide(input(readings(0.0, 0.0, 0.0), sessionRunning = true, auto = auto)))
    }

    @Test
    fun averageNeedsEnoughReadingsCloseTogether() {
        assertNull(TsunamiMealDetector.average(readings(5.0), 2))
        assertEquals(4.5, TsunamiMealDetector.average(readings(5.0, 4.0, 9.0), 2)!!, 1e-9)
    }

    @Test
    fun windowCanGoOverMidnight() {
        assertEquals(true, TsunamiMealDetector.inWindow(23 * 60, 22 * 60, 2 * 60))
        assertEquals(true, TsunamiMealDetector.inWindow(60, 22 * 60, 2 * 60))
        assertEquals(false, TsunamiMealDetector.inWindow(12 * 60, 22 * 60, 2 * 60))
    }

    @Test
    fun grossRiseAddsWhatInsulinTakesAway() {
        // Net delta 1, activity 0.05 U per 5 min, ISF 40: insulin took away 2
        assertEquals(3.0, TsunamiMealDetectorRunner.grossRise(1.0, 0.05, 40.0), 1e-9)
        assertEquals(0.0, TsunamiMealDetectorRunner.grossRise(-5.0, 0.0, 40.0), 1e-9)
        assertEquals(35.0, TsunamiMealDetectorRunner.grossRise(50.0, 0.0, 40.0), 1e-9)
    }
}
