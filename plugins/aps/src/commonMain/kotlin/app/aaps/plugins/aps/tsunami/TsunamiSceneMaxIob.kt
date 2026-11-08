package app.aaps.plugins.aps.tsunami

import app.aaps.core.data.model.ActiveSceneState
import app.aaps.core.data.model.SceneAction
import app.aaps.core.data.model.SceneLifecycle
import app.aaps.core.interfaces.constraints.Constraint
import app.aaps.core.interfaces.resources.TextResolver
import app.aaps.plugins.aps.ApsStrings

/**
 * Local change: the max IOB that a running scene asks for.
 *
 * A scene's max IOB action sets a percentage of the max IOB setting (for example 60 % after
 * training). While that scene is active, `TsunamiPlugin.applyMaxIOBConstraints` calls [apply],
 * which limits max IOB to that part of the setting. It can only lower max IOB: the limit is
 * applied with `setIfSmaller`, and the percentage is never above 100. While the limit applies, the
 * extra max IOB above 180 is off too, so the scene value is a real ceiling.
 *
 * It is read in every loop run, so it ends with the scene by itself.
 *
 * Kept in its own file, like the other local Tsunami changes, so it stays easy to find and to
 * keep through upstream merges.
 */
object TsunamiSceneMaxIob {

    /**
     * Max IOB percentage for the scene state at [now], or null when the scene does not limit max
     * IOB: no scene, the scene has ended (by its lifecycle or by the clock), or it has no max IOB
     * action. The value is clamped to 0-100, so it can never raise max IOB.
     */
    fun percent(state: ActiveSceneState?, now: Long): Int? {
        if (state == null || state.lifecycle != SceneLifecycle.ACTIVE || state.isExpired(now)) return null
        val action = state.scene.actions.filterIsInstance<SceneAction.MaxIob>().firstOrNull() ?: return null
        return action.percent.coerceIn(SceneAction.MaxIob.PERCENT_MIN, SceneAction.MaxIob.PERCENT_MAX)
    }

    /** Max IOB in units: [percent] of the max IOB setting [maxIobPref]. */
    fun limit(maxIobPref: Double, percent: Int): Double = maxIobPref * percent / 100.0

    /**
     * Limits [maxIob] to the scene value, with a reason that shows the percentage. Does nothing when
     * the scene does not limit max IOB. The caller applies the hard limit after this, so it still wins.
     */
    fun apply(maxIob: Constraint<Double>, maxIobPref: Double, state: ActiveSceneState?, now: Long, rh: TextResolver, from: Any) {
        val percent = percent(state, now) ?: return
        val sceneMaxIob = limit(maxIobPref, percent)
        maxIob.setIfSmaller(sceneMaxIob, rh.gs(ApsStrings.limiting_iob, sceneMaxIob, rh.gs(ApsStrings.max_iob_scene_tsunami, percent)), from)
    }
}
