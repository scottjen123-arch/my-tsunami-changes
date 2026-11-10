package app.aaps.plugins.aps.tsunami

import app.aaps.core.keys.PreferenceType
import app.aaps.core.keys.UnitType
import app.aaps.core.keys.interfaces.BooleanPreferenceKey
import app.aaps.core.keys.interfaces.DoublePreferenceKey
import app.aaps.core.keys.interfaces.IntNonPreferenceKey
import app.aaps.core.keys.interfaces.IntPreferenceKey
import app.aaps.core.keys.interfaces.LongNonPreferenceKey
import app.aaps.core.keys.interfaces.TextRef
import app.aaps.plugins.aps.ApsStrings

/**
 * Settings that are a local change on top of the upstream fork.
 *
 * Kept here and not in `BooleanKey` / `DoubleKey`, so that upstream changes in those files can never conflict.
 * `TsunamiPlugin` registers them through `ownPreferences`, which also includes them in settings export and import.
 */
enum class TsunamiLocalBooleanKey(
    override val key: String,
    override val defaultValue: Boolean,
    override val title: TextRef,
    override val summary: TextRef? = null,
    override val defaultedBySM: Boolean = false
) : BooleanPreferenceKey {

    HighBgExtraMaxIob(
        key = "key_tsunami_high_bg_extra_max_iob",
        defaultValue = false,
        title = ApsStrings.tsunami_high_bg_extra_max_iob_title,
        summary = ApsStrings.tsunami_high_bg_extra_max_iob_summary,
        defaultedBySM = true
    ),

    /**
     * Sessions the meal detector starts use their own max SMB size, activity target and insulin required.
     * Off: they use the normal Tsunami settings. See `TsunamiMealDetectorRunner.OwnSettings`.
     */
    MealDetectorOwnSettings(
        key = "key_tsunami_meal_detector_own_settings",
        defaultValue = false,
        title = ApsStrings.tsunami_meal_detector_own_settings_title,
        summary = ApsStrings.tsunami_meal_detector_own_settings_summary,
        defaultedBySM = true
    )
}

/** See [TsunamiLocalBooleanKey]. */
enum class TsunamiLocalDoubleKey(
    override val key: String,
    override val defaultValue: Double,
    override val min: Double,
    override val max: Double,
    override val title: TextRef,
    override val summary: TextRef? = null,
    override val defaultedBySM: Boolean = false,
    override val calculatedBySM: Boolean = false,
    override val dependency: BooleanPreferenceKey? = null,
    override val unitType: UnitType = UnitType.NONE
) : DoublePreferenceKey {

    HighBgExtraMaxIobAmount(
        key = "key_tsunami_high_bg_extra_max_iob_amount",
        defaultValue = 1.0,
        min = 0.5,
        max = 4.0,
        title = ApsStrings.tsunami_high_bg_extra_max_iob_amount_title,
        summary = ApsStrings.tsunami_high_bg_extra_max_iob_amount_summary,
        defaultedBySM = true,
        dependency = TsunamiLocalBooleanKey.HighBgExtraMaxIob,
        unitType = UnitType.INSULIN
    ),

    // Meal detector, see TsunamiMealDetector
    MealDetectorStartLevel(
        key = "key_tsunami_meal_detector_start_level",
        defaultValue = 3.5,
        min = 2.0,
        max = 10.0,
        title = ApsStrings.tsunami_meal_detector_start_level_title,
        summary = ApsStrings.tsunami_meal_detector_start_level_summary,
        defaultedBySM = true
    ),

    /**
     * A start also needs BG itself to rise this much, mg/dL per 5 min. 0 turns the check off.
     * The gross rise adds an estimate of what the insulin takes away, and with a lot of insulin on board that
     * estimate alone can reach the start level while BG is flat.
     */
    MealDetectorMinBgRise(
        key = "key_tsunami_meal_detector_min_bg_rise",
        defaultValue = 3.0,
        min = 0.0,
        max = 6.0,
        title = ApsStrings.tsunami_meal_detector_min_bg_rise_title,
        summary = ApsStrings.tsunami_meal_detector_min_bg_rise_summary,
        defaultedBySM = true
    ),
    MealDetectorStepUpLevel(
        key = "key_tsunami_meal_detector_step_up_level",
        defaultValue = 5.0,
        min = 3.0,
        max = 15.0,
        title = ApsStrings.tsunami_meal_detector_step_up_level_title,
        summary = ApsStrings.tsunami_meal_detector_step_up_level_summary,
        defaultedBySM = true
    ),

    /** Max SMB size of a detected session. The same limits and default as `DoubleKey.TsuSMBCap`. */
    MealDetectorSmbCap(
        key = "key_tsunami_meal_detector_smb_cap",
        defaultValue = 1.0,
        min = 0.1,
        max = 3.0,
        title = ApsStrings.tsunami_meal_detector_smb_cap_title,
        summary = ApsStrings.tsunami_meal_detector_smb_cap_summary,
        defaultedBySM = true,
        dependency = TsunamiLocalBooleanKey.MealDetectorOwnSettings,
        unitType = UnitType.INSULIN
    )
}

/** See [TsunamiLocalBooleanKey]. */
enum class TsunamiLocalIntKey(
    override val key: String,
    override val defaultValue: Int,
    override val min: Int,
    override val max: Int,
    override val title: TextRef,
    override val summary: TextRef? = null,
    override val preferenceType: PreferenceType = PreferenceType.TEXT_FIELD,
    private val entriesRefs: Map<Int, TextRef> = emptyMap(),
    override val defaultedBySM: Boolean = false,
    override val dependency: BooleanPreferenceKey? = null,
    override val unitType: UnitType = UnitType.NONE
) : IntPreferenceKey {

    /** [MEAL_DETECTOR_OFF], [MEAL_DETECTOR_LOG_ONLY] or [MEAL_DETECTOR_AUTO]. */
    MealDetectorMode(
        key = "key_tsunami_meal_detector_mode",
        defaultValue = MEAL_DETECTOR_OFF,
        min = MEAL_DETECTOR_OFF,
        max = MEAL_DETECTOR_AUTO,
        title = ApsStrings.tsunami_meal_detector_mode_title,
        summary = ApsStrings.tsunami_meal_detector_mode_summary,
        preferenceType = PreferenceType.LIST,
        entriesRefs = mapOf(
            MEAL_DETECTOR_OFF to ApsStrings.tsunami_meal_detector_mode_off,
            MEAL_DETECTOR_LOG_ONLY to ApsStrings.tsunami_meal_detector_mode_log_only,
            MEAL_DETECTOR_AUTO to ApsStrings.tsunami_meal_detector_mode_auto
        ),
        defaultedBySM = true
    ),
    MealDetectorStart(
        key = "key_tsunami_meal_detector_start",
        defaultValue = 7 * 60,
        min = 0,
        max = 24 * 60 - 1,
        title = ApsStrings.tsunami_meal_detector_start_title,
        preferenceType = PreferenceType.TIME,
        defaultedBySM = true
    ),
    MealDetectorEnd(
        key = "key_tsunami_meal_detector_end",
        defaultValue = 24 * 60 - 1,
        min = 0,
        max = 24 * 60 - 1,
        title = ApsStrings.tsunami_meal_detector_end_title,
        preferenceType = PreferenceType.TIME,
        defaultedBySM = true
    ),
    MealDetectorMediumSmbPercent(
        key = "key_tsunami_meal_detector_medium_smb_percent",
        defaultValue = 50,
        min = 10,
        max = 100,
        title = ApsStrings.tsunami_meal_detector_medium_smb_title,
        summary = ApsStrings.tsunami_meal_detector_medium_smb_summary,
        defaultedBySM = true
    ),

    /**
     * Length of a session the detector starts. Its own setting and not the Tsunami default duration: the detector
     * starts about 25 min later in the meal than a session the user starts before eating, so the same length would
     * run much longer after the meal.
     */
    MealDetectorSessionMinutes(
        key = "key_tsunami_meal_detector_session_minutes",
        defaultValue = 150,
        min = 30,
        max = 300,
        title = ApsStrings.tsunami_meal_detector_session_title,
        summary = ApsStrings.tsunami_meal_detector_session_summary,
        defaultedBySM = true,
        unitType = UnitType.MIN
    ),

    /** No new start for this long after the user stopped a session the detector started. 0 means no wait. */
    MealDetectorStopWaitMinutes(
        key = "key_tsunami_meal_detector_stop_wait_minutes",
        defaultValue = 60,
        min = 0,
        max = 180,
        title = ApsStrings.tsunami_meal_detector_stop_wait_title,
        summary = ApsStrings.tsunami_meal_detector_stop_wait_summary,
        defaultedBySM = true,
        unitType = UnitType.MIN
    ),

    /** Activity target of a detected session. The same limits and default as `IntKey.TsuActivityTarget`. */
    MealDetectorActivityTarget(
        key = "key_tsunami_meal_detector_activity_target",
        defaultValue = 75,
        min = 50,
        max = 100,
        title = ApsStrings.tsunami_meal_detector_activity_target_title,
        defaultedBySM = true,
        dependency = TsunamiLocalBooleanKey.MealDetectorOwnSettings,
        unitType = UnitType.PERCENT
    ),

    /** Insulin required percent of a detected session. The same limits and default as `IntKey.TsuInsReqPCT`. */
    MealDetectorInsReqPercent(
        key = "key_tsunami_meal_detector_ins_req_percent",
        defaultValue = 65,
        min = 50,
        max = 100,
        title = ApsStrings.tsunami_meal_detector_ins_req_title,
        summary = ApsStrings.tsunami_meal_detector_ins_req_summary,
        defaultedBySM = true,
        dependency = TsunamiLocalBooleanKey.MealDetectorOwnSettings,
        unitType = UnitType.PERCENT
    );

    override val entries: Map<Int, TextRef> = entriesRefs
}

const val MEAL_DETECTOR_OFF = 0
const val MEAL_DETECTOR_LOG_ONLY = 1
const val MEAL_DETECTOR_AUTO = 2

/** State of the meal detector. Not exported: it only makes sense on this phone, now. */
enum class TsunamiLocalLongNonKey(
    override val key: String,
    override val defaultValue: Long,
    override val exportable: Boolean = false
) : LongNonPreferenceKey {

    /** Start time of the Tsunami session the detector started, 0 if none. */
    MealDetectorAutoSessionStart("tsunami_meal_detector_auto_session_start", 0L),

    /** Planned end of the session the detector started. Ending before it means the user stopped the session. */
    MealDetectorAutoSessionEnd("tsunami_meal_detector_auto_session_end", 0L),

    /** Time the user last stopped a session the detector started, 0 if never. */
    MealDetectorLastUserStop("tsunami_meal_detector_last_user_stop", 0L),

    /** Time of the last alert in log only mode, 0 if none. */
    MealDetectorLastLogOnlyAlert("tsunami_meal_detector_last_log_only_alert", 0L)
}

/** See [TsunamiLocalLongNonKey]. */
enum class TsunamiLocalIntNonKey(
    override val key: String,
    override val defaultValue: Int,
    override val exportable: Boolean = false
) : IntNonPreferenceKey {

    /** Ordinal of `TsunamiMealDetector.Level` of the session the detector started. */
    MealDetectorAutoSessionLevel("tsunami_meal_detector_auto_session_level", 0)
}
