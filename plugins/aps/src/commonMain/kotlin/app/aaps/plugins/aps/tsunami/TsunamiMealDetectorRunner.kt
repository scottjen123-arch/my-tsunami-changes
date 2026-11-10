package app.aaps.plugins.aps.tsunami

import app.aaps.core.data.model.RM
import app.aaps.core.data.model.TSU
import app.aaps.core.data.model.TT
import app.aaps.core.data.time.T
import app.aaps.core.data.ue.Action
import app.aaps.core.data.ue.Sources
import app.aaps.core.interfaces.aps.GlucoseStatus
import app.aaps.core.interfaces.db.PersistenceLayer
import app.aaps.core.interfaces.iob.IobCobCalculator
import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.core.interfaces.logging.LTag
import app.aaps.core.interfaces.notifications.NotificationAction
import app.aaps.core.interfaces.notifications.NotificationId
import app.aaps.core.interfaces.notifications.NotificationManager
import app.aaps.core.interfaces.profile.EffectiveProfile
import app.aaps.core.interfaces.resources.TextResolver
import app.aaps.core.interfaces.utils.DateUtil
import app.aaps.core.keys.BooleanKey
import app.aaps.core.keys.interfaces.Preferences
import app.aaps.plugins.aps.ApsStrings
import app.aaps.plugins.aps.tsunami.TsunamiMealDetector.AutoSession
import app.aaps.plugins.aps.tsunami.TsunamiMealDetector.Decision
import app.aaps.plugins.aps.tsunami.TsunamiMealDetector.Level
import app.aaps.plugins.aps.tsunami.TsunamiMealDetector.Reading
import dev.zacsweers.metro.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlin.math.round

/**
 * Local change: runs [TsunamiMealDetector] once per loop run and carries out what it decides.
 *
 * `TsunamiPlugin.invoke` calls [run] before it reads the running Tsunami session, so a session
 * started here is used in the same loop run. The detector only acts on sessions it started itself;
 * a session the user starts is never changed.
 *
 * The gross rise of each reading is worked out again from the BG history and the insulin activity,
 * so nothing is lost when the app restarts. Which session the detector started, and at which level,
 * is kept in preferences for the same reason.
 */
@Inject
class TsunamiMealDetectorRunner(
    private val preferences: Preferences,
    private val persistenceLayer: PersistenceLayer,
    private val iobCobCalculator: IobCobCalculator,
    private val notificationManager: NotificationManager,
    private val dateUtil: DateUtil,
    private val rh: TextResolver,
    private val aapsLogger: AAPSLogger
) {

    /**
     * @param smbPercent SMB size for this loop run: smaller while a session the detector started is at medium confidence.
     * @param console one line for the loop console, null when the detector is off.
     * @param reason one line for the loop reason, only when the detector did something or runs a session.
     * @param ownSettings Tsunami settings for this loop run, when a session the detector started runs and
     *   [TsunamiLocalBooleanKey.MealDetectorOwnSettings] is on. Null: the normal Tsunami settings.
     */
    data class Result(val smbPercent: Int, val console: String?, val reason: String?, val ownSettings: OwnSettings? = null)

    /**
     * Tsunami settings of a detected session, in place of `DoubleKey.TsuSMBCap`, `IntKey.TsuActivityTarget`
     * and `IntKey.TsuInsReqPCT`. [smbCap] is in U, before the profile percentage scaling.
     * [activityTarget] and [insulinReqPercent] are in percent.
     */
    data class OwnSettings(val smbCap: Double, val activityTarget: Int, val insulinReqPercent: Int)

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /** @param sceneSmbPercent SMB size the running scene sets, [TsunamiSceneSmb.FULL] if none. Below full size, the detector does not start. */
    suspend fun run(now: Long, minuteOfDay: Int, profile: EffectiveProfile, history: List<GlucoseStatus>, sceneSmbPercent: Int): Result {
        val mode = preferences.get(TsunamiLocalIntKey.MealDetectorMode)
        if (mode == MEAL_DETECTOR_OFF) {
            clearAutoSession()
            return Result(TsunamiSceneSmb.FULL, null, null)
        }
        val active = persistenceLayer.getTsunamiActiveAt(now)
        val autoSession = autoSession(active, now)
        val readings = readings(now, profile, history)
        val input = TsunamiMealDetector.Input(
            now = now,
            minuteOfDay = minuteOfDay,
            windowStart = preferences.get(TsunamiLocalIntKey.MealDetectorStart),
            windowEnd = preferences.get(TsunamiLocalIntKey.MealDetectorEnd),
            readings = readings,
            lowestRecentBg = history.filter { it.date >= now - T.mins(60).msecs() }.minOfOrNull { it.glucose },
            sessionRunning = active != null,
            autoSession = autoSession,
            lastUserStop = preferences.get(TsunamiLocalLongNonKey.MealDetectorLastUserStop),
            stopWaitMinutes = preferences.get(TsunamiLocalIntKey.MealDetectorStopWaitMinutes),
            // Only in log only mode: an alert from before a switch to auto start must not block a start
            lastLogOnlyAlert = if (mode == MEAL_DETECTOR_LOG_ONLY) preferences.get(TsunamiLocalLongNonKey.MealDetectorLastLogOnlyAlert) else 0L,
            raisedTargetEnd = raisedTargetEnd(now),
            hypoTargetRunning = persistenceLayer.getTemporaryTargetActiveAt(now)?.reason == TT.Reason.HYPOGLYCEMIA,
            smbAllowed = preferences.get(BooleanKey.ApsUseSmb),
            sceneLimitsSmb = sceneSmbPercent < TsunamiSceneSmb.FULL,
            // The loop also calls Tsunami when the pump is disconnected or suspended. A rise then is often only missing basal.
            closedLoop = persistenceLayer.getRunningModeActiveAt(now).mode == RM.Mode.CLOSED_LOOP,
            bgGap = bgGap(now),
            startLevel = preferences.get(TsunamiLocalDoubleKey.MealDetectorStartLevel),
            minBgRise = preferences.get(TsunamiLocalDoubleKey.MealDetectorMinBgRise),
            stepUpLevel = preferences.get(TsunamiLocalDoubleKey.MealDetectorStepUpLevel)
        )
        val decision = TsunamiMealDetector.decide(input)
        val mediumPercent = preferences.get(TsunamiLocalIntKey.MealDetectorMediumSmbPercent)
        val rise = readings.firstOrNull()?.grossRise
        // Does a session the detector started run after this loop run?
        val detectedSessionRuns = when (decision) {
            is Decision.Start  -> mode == MEAL_DETECTOR_AUTO
            is Decision.StepUp -> true
            is Decision.End    -> false
            is Decision.EndForHypoTarget -> false
            is Decision.None   -> autoSession != null
        }
        val ownSettings = if (detectedSessionRuns && preferences.get(TsunamiLocalBooleanKey.MealDetectorOwnSettings)) ownSettings() else null
        val result = when (decision) {
            is Decision.Start -> start(decision, mode, now, mediumPercent)
            is Decision.StepUp -> {
                preferences.put(TsunamiLocalIntNonKey.MealDetectorAutoSessionLevel, Level.HIGH.ordinal)
                notify(rh.gs(ApsStrings.tsunami_meal_detector_stepped_up), stop = true)
                val line = "Meal detector: meal confirmed (rise ${format(decision.grossRise)}), full size SMBs"
                Result(TsunamiSceneSmb.FULL, line, line)
            }

            is Decision.End -> {
                persistenceLayer.cancelCurrentTsunamiModeIfAny(now, Action.CANCEL_TSUNAMI, Sources.Loop, MEAL_DETECTOR_NOTE, emptyList())
                clearAutoSession()
                notify(rh.gs(ApsStrings.tsunami_meal_detector_ended), stop = false)
                val line = "Meal detector: rise stopped (${format(decision.grossRise)}), Tsunami ended"
                Result(TsunamiSceneSmb.FULL, line, line)
            }

            is Decision.EndForHypoTarget -> {
                persistenceLayer.cancelCurrentTsunamiModeIfAny(now, Action.CANCEL_TSUNAMI, Sources.Loop, MEAL_DETECTOR_NOTE, emptyList())
                // Not a stop by the user: the temp target rule blocks a new start anyway
                clearAutoSession()
                notify(rh.gs(ApsStrings.tsunami_meal_detector_ended_hypo_target), stop = false)
                val line = "Meal detector: hypo temp target set, Tsunami ended"
                Result(TsunamiSceneSmb.FULL, line, line)
            }

            is Decision.None -> when (autoSession?.level) {
                Level.MEDIUM -> {
                    val line = "Meal detector: medium confidence, SMB size $mediumPercent% (rise ${format(rise)})"
                    Result(mediumPercent, line, line)
                }

                Level.HIGH   -> Result(TsunamiSceneSmb.FULL, "Meal detector: high confidence (rise ${format(rise)})", null)
                null         -> Result(TsunamiSceneSmb.FULL, "Meal detector: no start, ${decision.why} (rise ${format(rise)})", null)
            }
        }
        return if (ownSettings == null) result
        else result.copy(
            console = "${result.console}; own settings: max SMB ${ownSettings.smbCap} U, activity target ${ownSettings.activityTarget}%, insulin required ${ownSettings.insulinReqPercent}%",
            ownSettings = ownSettings
        )
    }

    private fun ownSettings() = OwnSettings(
        smbCap = preferences.get(TsunamiLocalDoubleKey.MealDetectorSmbCap),
        activityTarget = preferences.get(TsunamiLocalIntKey.MealDetectorActivityTarget),
        insulinReqPercent = preferences.get(TsunamiLocalIntKey.MealDetectorInsReqPercent)
    )

    private suspend fun start(decision: Decision.Start, mode: Int, now: Long, mediumPercent: Int): Result {
        if (mode != MEAL_DETECTOR_AUTO) {
            preferences.put(TsunamiLocalLongNonKey.MealDetectorLastLogOnlyAlert, now)
            notify(rh.gs(ApsStrings.tsunami_meal_detector_log_only, decision.grossRise), stop = false)
            val line = "Meal detector (log only): would start Tsunami, MEDIUM (rise ${format(decision.grossRise)})"
            return Result(TsunamiSceneSmb.FULL, line, line)
        }
        val duration = T.mins(preferences.get(TsunamiLocalIntKey.MealDetectorSessionMinutes).toLong()).msecs()
        val tsu = TSU(timestamp = now, duration = duration, tsunamiMode = TSUNAMI_SESSION)
        persistenceLayer.insertOrUpdateTsunami(tsu, Action.TSUNAMI, Sources.Loop, MEAL_DETECTOR_NOTE, emptyList())
        preferences.put(TsunamiLocalLongNonKey.MealDetectorAutoSessionStart, now)
        preferences.put(TsunamiLocalLongNonKey.MealDetectorAutoSessionEnd, now + duration)
        preferences.put(TsunamiLocalIntNonKey.MealDetectorAutoSessionLevel, Level.MEDIUM.ordinal)
        aapsLogger.debug(LTag.APS, "Meal detector started Tsunami: MEDIUM, rise ${decision.grossRise}")
        notify(rh.gs(ApsStrings.tsunami_meal_detector_started_medium, decision.grossRise, mediumPercent), stop = true)
        val line = "Meal detector: started Tsunami, MEDIUM, SMB size $mediumPercent% (rise ${format(decision.grossRise)})"
        return Result(mediumPercent, line, line)
    }

    /**
     * The running session, if the detector started it. Forgets a session that has ended or was replaced.
     *
     * A session that is gone before its planned end was stopped by the user (on the phone or the watch), so the
     * stop wait starts. A session the user replaced with their own is not a stop: their session blocks a start
     * while it runs. The detector's own end clears the state first, so it is not seen as a stop either.
     */
    private fun autoSession(active: TSU?, now: Long): AutoSession? {
        val start = preferences.get(TsunamiLocalLongNonKey.MealDetectorAutoSessionStart)
        if (start == 0L) return null
        if (active == null || active.timestamp != start) {
            if (active == null && now < preferences.get(TsunamiLocalLongNonKey.MealDetectorAutoSessionEnd))
                preferences.put(TsunamiLocalLongNonKey.MealDetectorLastUserStop, now)
            clearAutoSession()
            return null
        }
        val level = Level.entries.getOrNull(preferences.get(TsunamiLocalIntNonKey.MealDetectorAutoSessionLevel)) ?: Level.MEDIUM
        return AutoSession(start, level)
    }

    private fun clearAutoSession() {
        if (preferences.get(TsunamiLocalLongNonKey.MealDetectorAutoSessionStart) != 0L)
            preferences.put(TsunamiLocalLongNonKey.MealDetectorAutoSessionStart, 0L)
    }

    /** Newest readings of the last 25 min with their gross rise, newest first. */
    private suspend fun readings(now: Long, profile: EffectiveProfile, history: List<GlucoseStatus>): List<Reading> {
        val sens = profile.getIsfMgdl("TsunamiMealDetector")
        return history
            .filter { it.date >= now - T.mins(READING_WINDOW_MINUTES).msecs() }
            .take(READINGS)
            .map { Reading(it.date, it.glucose, grossRise(it.delta, activityAt(it.date, profile), sens), it.delta) }
    }

    /**
     * End of the newest hypo or activity temp target that ran in the last [TsunamiMealDetector.RAISED_TARGET_WAIT_MINUTES],
     * [now] if one runs, 0 if none. A target stops counting when a newer target replaces it, even if its own
     * duration is longer, because only the newest target is used by the loop.
     */
    private suspend fun raisedTargetEnd(now: Long): Long {
        val from = now - T.mins(TsunamiMealDetector.RAISED_TARGET_WAIT_MINUTES.toLong()).msecs()
        val targets = (persistenceLayer.getTemporaryTargetsActiveAt(from) + persistenceLayer.getTemporaryTargetDataFromTime(from, true))
            .filter { it.isValid && it.timestamp <= now }
            .distinct()
            .sortedBy { it.timestamp }
        return targets.withIndex()
            .filter { (_, tt) -> tt.reason == TT.Reason.HYPOGLYCEMIA || tt.reason == TT.Reason.ACTIVITY }
            .maxOfOrNull { (i, tt) -> minOf(tt.end, targets.getOrNull(i + 1)?.timestamp ?: Long.MAX_VALUE, now) }
            ?.takeIf { it >= from }
            ?: 0L
    }

    /**
     * True when the BG table has a filled gap in the time the readings cover, including the older value each
     * delta is taken from. [GlucoseStatus] does not carry the flag, so it is read from the table itself.
     */
    private fun bgGap(now: Long): Boolean {
        val from = now - T.mins(READING_WINDOW_MINUTES + 5).msecs()
        return iobCobCalculator.ads.getBucketedDataTableCopy().orEmpty().any { it.timestamp >= from && it.filledGap }
    }

    /** Insulin activity per 5 min seen in a reading at [time]. The same sensor lag of 10 min as the Tsunami loop. */
    private suspend fun activityAt(time: Long, profile: EffectiveProfile): Double {
        var activity = 0.0
        for (i in -4..0) activity += iobCobCalculator.calculateFromTreatmentsAndTemps(time + T.mins(SENSOR_LAG_MINUTES - i).msecs(), profile).activity
        return activity
    }

    private fun notify(text: String, stop: Boolean) {
        val actions = if (stop) listOf(NotificationAction(ApsStrings.tsunami_meal_detector_stop) { stopSession() }) else emptyList()
        notificationManager.post(NotificationId.TSUNAMI_MEAL_DETECTOR, text, validMinutes = NOTIFICATION_MINUTES, actions = actions)
    }

    /** The Stop button of the notification. Ends the running session only if the detector started it. */
    private fun stopSession() {
        scope.launch {
            val now = dateUtil.now()
            if (autoSession(persistenceLayer.getTsunamiActiveAt(now), now) != null) {
                persistenceLayer.cancelCurrentTsunamiModeIfAny(now, Action.CANCEL_TSUNAMI, Sources.Aaps, MEAL_DETECTOR_NOTE, emptyList())
                preferences.put(TsunamiLocalLongNonKey.MealDetectorLastUserStop, now)
                clearAutoSession()
            }
            notificationManager.dismiss(NotificationId.TSUNAMI_MEAL_DETECTOR)
        }
    }

    private fun format(value: Double?): String = value?.let { (round(it * 10) / 10).toString() } ?: "-"

    companion object {

        /** Tsunami mode of a Tsunami session. */
        private const val TSUNAMI_SESSION = 2
        private const val SENSOR_LAG_MINUTES = -10L
        private const val READING_WINDOW_MINUTES = 25L
        private const val READINGS = 4
        private const val NOTIFICATION_MINUTES = 60
        private const val MEAL_DETECTOR_NOTE = "Meal detector"

        /** The same formula as `deltaGross` in `DetermineBasalTsunami`: net delta plus what the insulin takes away, 0 to 35. */
        fun grossRise(delta: Double, activity: Double, sens: Double): Double = (delta + activity * sens).coerceIn(0.0, 35.0)
    }
}
