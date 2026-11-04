package app.aaps.plugins.aps.tsunami

import app.aaps.core.keys.UnitType
import app.aaps.core.keys.interfaces.BooleanPreferenceKey
import app.aaps.core.keys.interfaces.DoublePreferenceKey
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
    )
}
