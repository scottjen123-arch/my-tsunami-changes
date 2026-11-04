package app.aaps.plugins.aps.tsunami

import app.aaps.core.interfaces.utils.HardLimits
import app.aaps.core.keys.interfaces.Preferences
import dev.zacsweers.metro.Inject
import kotlin.math.max
import kotlin.math.min

/**
 * Optional extra room in max IOB while a Tsunami session is fighting a high, rising BG.
 *
 * Max IOB often stops Tsunami in the middle of a meal. Raising it for every BG would also add insulin at a lower
 * BG, where the risk of a later low is larger. So the extra room exists only when all of these are true:
 * - the user switched it on (off by default),
 * - a Tsunami session is running (not Wave, not plain oref1),
 * - BG is above [BG_THRESHOLD],
 * - BG is still rising.
 *
 * When one of these stops being true, max IOB returns to its normal value in the next loop run.
 * The hard limit always wins. The extra is not scaled by the profile percentage.
 *
 * Only the IOB limit changes. `TsunamiSafety` keeps the normal max IOB, so the size of each dose does not change.
 *
 * Kept in its own file on purpose: it is a local change on top of the upstream fork. `DetermineBasalTsunami` calls
 * [maxIob] in one line.
 */
@Inject
class TsunamiHighBgMaxIob(
    private val preferences: Preferences,
    private val hardLimits: HardLimits
) {

    /** Max IOB to use now. When the extra room applies, adds one line to [log], so the use of it can be measured later. */
    fun maxIob(normalMaxIob: Double, bg: Double, delta: Double, tsunamiModeId: Int, log: MutableList<String>): Double {
        if (!preferences.get(TsunamiLocalBooleanKey.HighBgExtraMaxIob)) return normalMaxIob
        val result = calculate(
            normalMaxIob = normalMaxIob,
            extra = preferences.get(TsunamiLocalDoubleKey.HighBgExtraMaxIobAmount),
            hardLimit = hardLimits.maxIobSMB(),
            bg = bg,
            delta = delta,
            tsunamiModeId = tsunamiModeId
        )
        if (result != normalMaxIob) log.add("High BG extra max IOB: normal $normalMaxIob, now $result")
        return result
    }

    companion object {

        /** mg/dL. Extra room only above this. */
        const val BG_THRESHOLD = 180.0

        /** Tsunami mode id of a Tsunami session. */
        private const val TSUNAMI_SESSION = 2

        fun calculate(normalMaxIob: Double, extra: Double, hardLimit: Double, bg: Double, delta: Double, tsunamiModeId: Int): Double {
            val applies = tsunamiModeId == TSUNAMI_SESSION && bg > BG_THRESHOLD && delta > 0.0
            if (!applies) return normalMaxIob
            return max(normalMaxIob, min(normalMaxIob + extra, hardLimit))
        }
    }
}
