package app.aaps.plugins.aps.tsunami

import app.aaps.core.data.model.ActiveSceneState
import app.aaps.core.data.model.Scene
import app.aaps.core.data.model.SceneAction
import app.aaps.core.data.model.SceneLifecycle
import kotlin.test.Test
import kotlin.test.assertEquals

/** The rules of [TsunamiSceneSmb]. In commonTest, so it also runs on the iOS simulator. */
class TsunamiSceneSmbTest {

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

    private val half = SceneAction.SmbToggle(enabled = true, smbPercent = 50)

    @Test
    fun noSceneMeansFullSize() {
        assertEquals(100, TsunamiSceneSmb.smbPercent(null, start))
    }

    @Test
    fun activeSceneGivesItsSize() {
        assertEquals(50, TsunamiSceneSmb.smbPercent(state(half), start + hour))
    }

    @Test
    fun sceneWithoutDurationKeepsItsSize() {
        assertEquals(50, TsunamiSceneSmb.smbPercent(state(half, durationMs = 0), start + 30 * hour))
    }

    @Test
    fun endedSceneMeansFullSize() {
        // By the clock, before the master has marked it expired
        assertEquals(100, TsunamiSceneSmb.smbPercent(state(half), start + 3 * hour))
        // Marked expired
        assertEquals(100, TsunamiSceneSmb.smbPercent(state(half, lifecycle = SceneLifecycle.EXPIRED), start + hour))
    }

    @Test
    fun sceneWithSmbOffOrWithoutSmbActionMeansFullSize() {
        assertEquals(100, TsunamiSceneSmb.smbPercent(state(SceneAction.SmbToggle(enabled = false, smbPercent = 50)), start + hour))
        assertEquals(100, TsunamiSceneSmb.smbPercent(state(SceneAction.ProfileSwitch(profileName = "", percentage = 90)), start + hour))
    }

    @Test
    fun sizeIsClampedAndNeverBigger() {
        assertEquals(100, TsunamiSceneSmb.smbPercent(state(SceneAction.SmbToggle(enabled = true, smbPercent = 250)), start + hour))
        assertEquals(10, TsunamiSceneSmb.smbPercent(state(SceneAction.SmbToggle(enabled = true, smbPercent = 0)), start + hour))
    }

    @Test
    fun scaledSmbRoundsDownToThePumpStep() {
        // Tonight's SMBs at 50 % with the Dash step of 0.05 U
        assertEquals(0.65, TsunamiSceneSmb.scaledSmb(1.35, 50, 0.05), 1e-9)
        assertEquals(0.35, TsunamiSceneSmb.scaledSmb(0.75, 50, 0.05), 1e-9)
        // Exact halves keep their whole step
        assertEquals(0.65, TsunamiSceneSmb.scaledSmb(1.3, 50, 0.05), 1e-9)
        // Too small for one step: no SMB
        assertEquals(0.0, TsunamiSceneSmb.scaledSmb(0.05, 50, 0.05), 1e-9)
    }

    @Test
    fun fullSizeLeavesTheSmbAlone() {
        assertEquals(1.35, TsunamiSceneSmb.scaledSmb(1.35, 100, 0.05), 1e-9)
        assertEquals(0.0, TsunamiSceneSmb.scaledSmb(0.0, 50, 0.05), 1e-9)
    }
}
