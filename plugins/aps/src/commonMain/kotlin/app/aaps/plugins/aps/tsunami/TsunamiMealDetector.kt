package app.aaps.plugins.aps.tsunami

import kotlin.math.round

/**
 * Local change: the rules of the meal detector. No Android, no database, so it can be tested on its own.
 *
 * It looks at the gross rise: the BG delta per 5 min plus what the active insulin takes away
 * (net delta + insulin activity * ISF). A meal pushes the gross rise up even when insulin keeps BG
 * flat. In 36 days of data, a daytime gross rise of 3.5 or more over 10 min started about half of
 * the meals within 15 min of the rise, with one start without a meal every 2 to 3 days. A gross rise
 * of 5 or more over 15 min was almost always a real meal.
 *
 * So it works in two steps:
 * 1. Medium confidence: [startLevel] over 2 readings starts a Tsunami session with smaller SMBs. It always
 *    starts here, however fast the rise is. The reading before those 2 must also show a rise, and BG itself
 *    must rise by [Input.minBgRise]. The gross rise adds an estimate of what the insulin takes away. With a
 *    lot of insulin on board that estimate alone reached the start level while BG was flat, and the detector
 *    started a new session the minute its last one ended. In the 36 days of data these two checks removed
 *    about half of the starts that no rise followed, and lost about 1 in 10 meals.
 * 2. High confidence: [stepUpLevel] over the first 2 readings taken after the start gives full size SMBs.
 *    Only new readings count, so the readings that started the session cannot also confirm it. Each of
 *    them must be at least [NOISE_FACTOR] of the level, so one jump cannot confirm it alone.
 * If the rise stops before step 2, the session ends after [END_AFTER_MINUTES] at the earliest.
 * A hypo temp target ends a detected session at any level, see [Input.hypoTargetRunning].
 *
 * `TsunamiMealDetectorRunner` reads the data and carries out the [Decision].
 */
object TsunamiMealDetector {

    /** BG must be at least this, mg/dL. */
    const val MIN_BG = 90.0

    /** No start if BG was below this in the last hour, mg/dL. Carbs for a low must not start Tsunami. */
    const val MIN_RECENT_BG = 80.0

    /** No start if the newest BG reading is older than this. */
    const val MAX_CGM_AGE_MINUTES = 7

    /**
     * In log only mode, at least this long between two alerts, so one rise gives one alert.
     * In auto mode there is no such wait: a running session blocks a new start, and when a session has ended,
     * a new start needs a new rise. Only a stop by the user gives a wait, see [Input.stopWaitMinutes].
     */
    const val LOG_ONLY_WAIT_MINUTES = 90

    /**
     * No start while a hypo or activity temp target runs, and for this long after it ended. Users often cancel a
     * hypo target as soon as BG rises, and the rise from the carbs for the low or after exercise looks like a meal.
     */
    const val RAISED_TARGET_WAIT_MINUTES = 60

    /** A medium session that was never confirmed can end after this long. */
    const val END_AFTER_MINUTES = 30

    /** A medium session ends when the gross rise over 3 readings is below this. */
    const val END_GROSS_RISE = 2.0

    /** Each reading must be at least this part of the level, so one noisy reading cannot start or confirm it alone. */
    const val NOISE_FACTOR = 0.6

    /** Readings that must show a rise for a start. The newest 2 give the average, the one before only has to pass [NOISE_FACTOR]. */
    const val START_READINGS = 3

    /** Readings after the start needed to step up. */
    const val STEP_UP_READINGS = 2

    /**
     * A reading counts as new only when it is this much younger than the start. The 5 min BG table can put the
     * newest reading up to 30 s after the real CGM time, so the reading that started the session must not count.
     */
    const val NEW_READING_MARGIN_MS = 2 * 60_000L

    enum class Level { MEDIUM, HIGH }

    /** One CGM reading. [grossRise] and [delta] are in mg/dL per 5 min. [delta] is the change of BG itself. */
    data class Reading(val time: Long, val bg: Double, val grossRise: Double, val delta: Double)

    /** A Tsunami session that the detector started itself. */
    data class AutoSession(val start: Long, val level: Level)

    data class Input(
        val now: Long,
        /** Minutes since midnight, local time. */
        val minuteOfDay: Int,
        /** Detector hours, minutes since midnight. Start after end means the window goes over midnight. */
        val windowStart: Int,
        val windowEnd: Int,
        /** Newest first. */
        val readings: List<Reading>,
        /** Lowest BG in the last hour. */
        val lowestRecentBg: Double?,
        /** Any Tsunami session runs now, started by the user or by the detector. */
        val sessionRunning: Boolean,
        /** The running session, if the detector started it. */
        val autoSession: AutoSession?,
        /** Time the user last stopped a session the detector started, 0 if never. */
        val lastUserStop: Long,
        /** No start for this long after [lastUserStop]. 0 means no wait. */
        val stopWaitMinutes: Int,
        /** Time of the last alert in log only mode, 0 if none. */
        val lastLogOnlyAlert: Long,
        /**
         * End of the newest hypo or activity temp target, [now] if one runs, 0 if none ended in the last
         * [RAISED_TARGET_WAIT_MINUTES]. Also set by a scene or the watch, they use the same reasons.
         */
        val raisedTargetEnd: Long,
        /** A hypo temp target runs now. It ends a session the detector started. */
        val hypoTargetRunning: Boolean,
        /** False when SMB is off, for example by a scene. */
        val smbAllowed: Boolean,
        /**
         * A running scene sets the SMB size below full size. The user asked for smaller SMBs, so the detector must
         * not start Tsunami by itself. This is also the way to eat a small snack without a start: a scene with a
         * smaller SMB size and no other action.
         */
        val sceneLimitsSmb: Boolean,
        /** True only in closed loop. Not when the pump is disconnected or suspended, in open loop or in LGS. */
        val closedLoop: Boolean,
        /**
         * True when one of the readings used is not a real CGM value: the 5 min BG table fills a CGM gap with
         * values on a straight line, and those can look like a steady fast rise.
         */
        val bgGap: Boolean,
        val startLevel: Double,
        /** A start also needs BG itself to rise this much on average over the newest 2 readings. 0 means no such check. */
        val minBgRise: Double,
        val stepUpLevel: Double
    )

    sealed class Decision {

        /** Nothing to do. [why] says what stopped it, for the log. */
        data class None(val why: String) : Decision()

        /** Always at medium confidence. */
        data class Start(val grossRise: Double) : Decision()

        data class StepUp(val grossRise: Double) : Decision()

        data class End(val grossRise: Double) : Decision()

        /** The user set a hypo temp target, so the session the detector started must end. */
        data object EndForHypoTarget : Decision()
    }

    fun decide(input: Input): Decision {
        val auto = input.autoSession
        if (auto != null) return decideRunning(input, auto)
        if (input.sessionRunning) return Decision.None("a session runs")
        return decideStart(input)
    }

    private fun decideRunning(input: Input, auto: AutoSession): Decision {
        // Before everything else: the user expects a low, so strong SMBs are wrong at any level
        if (input.hypoTargetRunning) return Decision.EndForHypoTarget
        if (auto.level == Level.HIGH) return Decision.None("high confidence")
        // Made up values must not confirm or end a session. It keeps its level until real data is back.
        if (input.bgGap) return Decision.None("gap in BG data")
        val newReadings = input.readings.filter { it.time > auto.start + NEW_READING_MARGIN_MS }
        val riseNew = average(newReadings, STEP_UP_READINGS)
        if (riseNew != null && riseNew >= input.stepUpLevel && newReadings.take(STEP_UP_READINGS).all { it.grossRise >= input.stepUpLevel * NOISE_FACTOR })
            return Decision.StepUp(riseNew)
        val rise3 = average(input.readings, 3)
        val minutes = (input.now - auto.start) / MINUTE_MS
        if (minutes >= END_AFTER_MINUTES && rise3 != null && rise3 < END_GROSS_RISE) return Decision.End(rise3)
        return Decision.None("medium confidence")
    }

    private fun decideStart(input: Input): Decision {
        if (!inWindow(input.minuteOfDay, input.windowStart, input.windowEnd)) return Decision.None("outside detector hours")
        if (!input.smbAllowed) return Decision.None("SMB is off")
        if (input.sceneLimitsSmb) return Decision.None("a scene limits the SMB size")
        if (!input.closedLoop) return Decision.None("loop is not in closed loop")
        if (input.bgGap) return Decision.None("gap in BG data")
        val newest = input.readings.firstOrNull() ?: return Decision.None("no BG")
        if ((input.now - newest.time) / MINUTE_MS > MAX_CGM_AGE_MINUTES) return Decision.None("BG data too old")
        if (newest.bg < MIN_BG) return Decision.None("BG below ${MIN_BG.toInt()}")
        if (input.lowestRecentBg != null && input.lowestRecentBg < MIN_RECENT_BG) return Decision.None("BG was below ${MIN_RECENT_BG.toInt()} in the last hour")
        if (input.raisedTargetEnd > 0 && (input.now - input.raisedTargetEnd) / MINUTE_MS < RAISED_TARGET_WAIT_MINUTES)
            return Decision.None("hypo or activity temp target less than $RAISED_TARGET_WAIT_MINUTES min ago")
        if (input.lastUserStop > 0 && (input.now - input.lastUserStop) / MINUTE_MS < input.stopWaitMinutes)
            return Decision.None("you stopped a detected session less than ${input.stopWaitMinutes} min ago")
        if (input.lastLogOnlyAlert > 0 && (input.now - input.lastLogOnlyAlert) / MINUTE_MS < LOG_ONLY_WAIT_MINUTES)
            return Decision.None("alert less than $LOG_ONLY_WAIT_MINUTES min ago")
        val used = input.readings.take(START_READINGS)
        // Also checks that the readings are close together
        average(input.readings, START_READINGS) ?: return Decision.None("not enough BG readings")
        val rise2 = average(input.readings, 2) ?: return Decision.None("not enough BG readings")
        if (rise2 < input.startLevel || used.any { it.grossRise < input.startLevel * NOISE_FACTOR }) return Decision.None("no meal rise")
        val bgRise = used.take(2).sumOf { it.delta } / 2
        if (input.minBgRise > 0 && bgRise < input.minBgRise)
            return Decision.None("BG itself rises only ${round(bgRise * 10) / 10}, needs ${input.minBgRise}")
        return Decision.Start(rise2)
    }

    /** Average gross rise of the newest [count] readings, or null if there are fewer, or they are not about 5 min apart. */
    fun average(readings: List<Reading>, count: Int): Double? {
        val used = readings.take(count)
        if (used.size < count) return null
        // Readings must be close together: a gap would mix an old rise with a new one
        if ((used.first().time - used.last().time) / MINUTE_MS > (count - 1) * 5 + 3) return null
        return used.sumOf { it.grossRise } / count
    }

    fun inWindow(minute: Int, start: Int, end: Int): Boolean =
        if (start <= end) minute in start..end else minute >= start || minute <= end

    private const val MINUTE_MS = 60_000L
}
