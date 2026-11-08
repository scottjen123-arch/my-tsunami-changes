package app.aaps.plugins.aps.tsunami

import app.aaps.core.data.model.ActiveSceneState
import app.aaps.core.data.model.Scene
import app.aaps.core.data.model.SceneAction
import app.aaps.core.data.model.SceneLifecycle
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** The rules of [TsunamiSceneMaxIob]. In commonTest, so it also runs on the iOS simulator. */
class TsunamiSceneMaxIobTest {

    private val start = 1_790_000_000_000L
    private val hour = 3_600_000L

    private fun state(
        vararg actions: SceneAction,
        durationMs: Long = 3 * hour,
        lifecycle: SceneLifecycle = SceneLifecycle.ACTIVE
    ) = ActiveSceneState(
        scene = Scene(id = "r", name = "Recovery", actions = actions.toList()),
        activatedAt = start,
        durationMs = durationMs,
        lifecycle = lifecycle
    )

    private val sixty = SceneAction.MaxIob(percent = 60)

    @Test
    fun noSceneMeansNoLimit() {
        assertNull(TsunamiSceneMaxIob.percent(null, start))
    }

    @Test
    fun activeSceneGivesItsPercent() {
        assertEquals(60, TsunamiSceneMaxIob.percent(state(sixty), start + hour))
    }

    @Test
    fun sceneWithoutDurationKeepsItsPercent() {
        assertEquals(60, TsunamiSceneMaxIob.percent(state(sixty, durationMs = 0), start + 30 * hour))
    }

    @Test
    fun endedSceneMeansNoLimit() {
        // By the clock, before the master has marked it expired
        assertNull(TsunamiSceneMaxIob.percent(state(sixty), start + 3 * hour))
        // Marked expired
        assertNull(TsunamiSceneMaxIob.percent(state(sixty, lifecycle = SceneLifecycle.EXPIRED), start + hour))
    }

    @Test
    fun sceneWithoutMaxIobActionMeansNoLimit() {
        assertNull(TsunamiSceneMaxIob.percent(state(SceneAction.SmbToggle(enabled = true, smbPercent = 50)), start + hour))
    }

    @Test
    fun percentIsClampedAndNeverAbove100() {
        assertEquals(100, TsunamiSceneMaxIob.percent(state(SceneAction.MaxIob(percent = 250)), start + hour))
        assertEquals(0, TsunamiSceneMaxIob.percent(state(SceneAction.MaxIob(percent = -20)), start + hour))
    }

    @Test
    fun limitIsThePartOfTheSetting() {
        assertEquals(4.2, TsunamiSceneMaxIob.limit(7.0, 60), 1e-9)
        assertEquals(7.0, TsunamiSceneMaxIob.limit(7.0, 100), 1e-9)
        assertEquals(0.0, TsunamiSceneMaxIob.limit(7.0, 0), 1e-9)
    }
}
