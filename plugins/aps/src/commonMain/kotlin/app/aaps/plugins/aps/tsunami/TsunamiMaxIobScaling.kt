package app.aaps.plugins.aps.tsunami

import app.aaps.core.interfaces.constraints.Constraint
import app.aaps.core.interfaces.db.PersistenceLayer
import app.aaps.core.interfaces.profile.ProfileFunction
import app.aaps.core.interfaces.resources.TextResolver
import app.aaps.core.interfaces.utils.DateUtil
import app.aaps.core.keys.BooleanKey
import app.aaps.core.keys.interfaces.Preferences
import app.aaps.core.objects.profile.ProfileSealed
import app.aaps.plugins.aps.ApsStrings
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Scaling of max IOB with the profile percentage, used while "Enable SMB cap scaling" (Tsunami) is on.
 *
 * Profile percentage already scales basal, ISF and the SMB cap. Without this, max IOB stays fixed, and in
 * times of high insulin need it stops Tsunami in the middle of a meal.
 *
 * Kept in its own file on purpose: it is a local change on top of the upstream fork, and a separate file
 * keeps it easy to revert and free of merge conflicts. `TsunamiPlugin.applyMaxIOBConstraints` calls
 * [applyScaled] in one line.
 */
object TsunamiMaxIobScaling {

    /** Max IOB grows only up to this percentage. The same limit as the SMB cap scaling. */
    private const val MAX_PERCENT = 130

    /** Tsunami mode as stored in a Tsunami session record. */
    private const val TSUNAMI_MODE = 2

    /**
     * Max IOB scaled by the profile percentage the user set.
     *
     * - Below 100 % it always shrinks (for example 90 % gives 0.9 x), because a lower limit is always safe.
     * - Above 100 % it grows only while a Tsunami session is running, and only up to [MAX_PERCENT].
     *   Outside a session (Wave, oref1, overnight) it stays at the value in preferences.
     *
     * Pass the original percentage of the profile switch, not the one of the effective profile, which is
     * always 100 %.
     */
    fun scaledMaxIob(maxIob: Double, profilePercentage: Int, tsunamiSessionActive: Boolean): Double {
        val percent = when {
            profilePercentage < 100 -> profilePercentage
            tsunamiSessionActive    -> min(profilePercentage, MAX_PERCENT)
            else                    -> 100
        }
        return maxIob * percent / 100.0
    }

    /**
     * Limits [maxIob] to the scaled value from preferences, with a reason that shows the percentage used.
     *
     * Returns false when nothing was scaled (scaling switched off, no profile, 100 %, or above 100 % without
     * a Tsunami session). The caller then applies the plain value from preferences, as before. The hard limit
     * is applied by the caller after this, so it still wins.
     */
    suspend fun applyScaled(
        maxIob: Constraint<Double>,
        maxIobPref: Double,
        preferences: Preferences,
        profileFunction: ProfileFunction,
        persistenceLayer: PersistenceLayer,
        dateUtil: DateUtil,
        rh: TextResolver,
        from: Any
    ): Boolean {
        if (!preferences.get(BooleanKey.TsuSMBscaling)) return false
        val percentage = (profileFunction.getProfile() as? ProfileSealed.EPS)?.value?.originalPercentage ?: return false
        if (percentage == 100) return false
        val sessionActive = persistenceLayer.getTsunamiActiveAt(dateUtil.now())?.tsunamiMode == TSUNAMI_MODE
        val scaled = scaledMaxIob(maxIobPref, percentage, sessionActive)
        if (scaled == maxIobPref) return false
        val usedPercent = (scaled / maxIobPref * 100).roundToInt()
        maxIob.setIfSmaller(scaled, rh.gs(ApsStrings.limiting_iob, scaled, rh.gs(ApsStrings.max_iob_scaled_tsunami, usedPercent)), from)
        return true
    }
}
