package app.aaps.plugins.aps.tsunami

import kotlin.math.min

/**
 * Scaling of the Tsunami and Wave SMB cap with the profile percentage ("Enable SMB cap scaling").
 *
 * Kept in its own file on purpose: it is a local fix on top of the upstream fork, and a separate file
 * keeps it easy to revert and free of merge conflicts.
 */
object TsunamiSmbCapScaling {

    /** The SMB cap grows with the profile percentage only up to this. */
    private const val MAX_PERCENT = 130.0

    /**
     * SMB cap scaled by the profile percentage the user set (for example 120 % gives 1.2 x the cap).
     *
     * Pass the original percentage of the profile switch. The effective profile always reports 100 %,
     * because the percentage is already applied to its basal, ISF and IC, so scaling by it changes
     * nothing. The cap grows only up to [MAX_PERCENT], but shrinks without a lower limit.
     */
    fun scaledSmbCap(smbCap: Double, profilePercentage: Int): Double =
        smbCap * min(profilePercentage.toDouble(), MAX_PERCENT) / 100.0
}
