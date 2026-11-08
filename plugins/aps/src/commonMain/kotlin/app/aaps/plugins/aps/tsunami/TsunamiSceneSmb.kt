package app.aaps.plugins.aps.tsunami

import app.aaps.core.data.model.ActiveSceneState
import app.aaps.core.data.model.SceneAction
import app.aaps.core.data.model.SceneLifecycle
import kotlin.math.floor

/**
 * Local change: the SMB size that a running scene asks for.
 *
 * A scene's SMB action can set a size in percent (for example 50 % after training). While that
 * scene is active, each SMB is that part of the SMB the loop calculated. The missing part is not
 * given later by the temp basal: the caller takes the full SMB off the insulin requirement.
 *
 * Kept in its own file, like the other local Tsunami changes, so it stays easy to find and to
 * keep through upstream merges.
 */
object TsunamiSceneSmb {

    /** Full size: no scene, or a scene that does not change the SMB size. */
    const val FULL = SceneAction.SmbToggle.SMB_PERCENT_MAX

    /**
     * SMB size in percent for the scene state at [now]. Full size when no scene runs, when the
     * scene has ended (by its lifecycle or by the clock), when it has no SMB action, or when its
     * SMB action turns SMB off. The value is clamped, so it can never make an SMB bigger.
     */
    fun smbPercent(state: ActiveSceneState?, now: Long): Int {
        if (state == null || state.lifecycle != SceneLifecycle.ACTIVE || state.isExpired(now)) return FULL
        val action = state.scene.actions.filterIsInstance<SceneAction.SmbToggle>().firstOrNull() ?: return FULL
        if (!action.enabled) return FULL
        return action.smbPercent.coerceIn(SceneAction.SmbToggle.SMB_PERCENT_MIN, FULL)
    }

    /**
     * The SMB to give: [fullSmb] times [percent], rounded down to the pump step [bolusIncrement].
     * The small tolerance stops a value like 12.9999999 steps from losing a whole step.
     */
    fun scaledSmb(fullSmb: Double, percent: Int, bolusIncrement: Double): Double {
        if (percent >= FULL || fullSmb <= 0.0 || bolusIncrement <= 0.0) return fullSmb
        // Same rounding as the SMB itself in determine_basal: whole steps divided by steps per unit
        val stepsPerUnit = 1.0 / bolusIncrement
        val steps = floor(fullSmb * percent / 100.0 * stepsPerUnit + 1e-9)
        return steps / stepsPerUnit
    }
}
