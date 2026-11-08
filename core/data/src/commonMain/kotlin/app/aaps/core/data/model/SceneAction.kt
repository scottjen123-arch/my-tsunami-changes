package app.aaps.core.data.model

/**
 * Sealed class representing individual actions that can be part of a Scene.
 * Each action maps to a specific system operation (TT, profile switch, etc.).
 */
sealed class SceneAction {

    /**
     * Set a temporary target.
     * @param reason TT reason category
     * @param targetMgdl Target value in mg/dL (converted at display time)
     */
    data class TempTarget(
        val reason: TT.Reason,
        val targetMgdl: Double
    ) : SceneAction()

    /**
     * Switch to a named profile with optional percentage and time shift.
     * @param profileName Name of the profile to switch to
     * @param percentage Basal percentage (100 = no change)
     * @param timeShiftHours Time shift in hours (0 = no shift)
     */
    data class ProfileSwitch(
        val profileName: String,
        val percentage: Int = 100,
        val timeShiftHours: Int = 0
    ) : SceneAction()

    /**
     * Toggle SMB on or off.
     * @param enabled Whether SMB should be enabled
     * @param smbPercent Local change: size of each SMB while the scene is active, in percent of
     *   what the loop calculates. 100 = no change. Only used when [enabled] is true, and only the
     *   Tsunami APS reads it.
     */
    data class SmbToggle(
        val enabled: Boolean,
        val smbPercent: Int = SMB_PERCENT_MAX
    ) : SceneAction() {

        companion object {

            const val SMB_PERCENT_MIN = 10
            const val SMB_PERCENT_MAX = 100
            const val SMB_PERCENT_STEP = 10
        }
    }

    /**
     * Local change: lower max IOB while the scene is active.
     * @param percent Max IOB in percent of the max IOB setting. 100 = no change, 0 = no IOB above
     *   zero. It can only lower max IOB, never raise it. Only the Tsunami APS reads it, every loop
     *   run, so it ends with the scene and needs no revert.
     */
    data class MaxIob(
        val percent: Int
    ) : SceneAction() {

        companion object {

            const val PERCENT_MIN = 0
            const val PERCENT_MAX = 100
            const val PERCENT_STEP = 10
            const val PERCENT_DEFAULT = 70
        }
    }

    /**
     * Change the loop running mode.
     * @param mode Target running mode
     */
    data class LoopModeChange(
        val mode: RM.Mode
    ) : SceneAction()

    /**
     * Create a CarePortal therapy event entry.
     * @param type Therapy event type (Exercise, Sickness, etc.)
     * @param note Optional note text
     */
    data class CarePortalEvent(
        val type: TE.Type,
        val note: String = ""
    ) : SceneAction()
}
