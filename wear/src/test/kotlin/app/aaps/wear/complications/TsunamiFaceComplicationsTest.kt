package app.aaps.wear.complications

import android.app.PendingIntent
import android.content.Intent
import androidx.wear.watchface.complications.data.ComplicationType
import androidx.wear.watchface.complications.data.ShortTextComplicationData
import app.aaps.wear.AAPSLoggerTest
import app.aaps.wear.R
import app.aaps.wear.data.ComplicationData
import com.google.common.truth.Truth.assertThat
import java.time.Instant
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/** Local change: the data slots of the Tsunami face. The face colours the button by the title, so that must be exact. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
internal class TsunamiFaceComplicationsTest {

    private val context get() = RuntimeEnvironment.getApplication()
    private fun tap(): PendingIntent = PendingIntent.getActivity(context, 0, Intent(), PendingIntent.FLAG_IMMUTABLE)

    private fun tsunami() = Robolectric.buildService(TsunamiComplication::class.java).get().also { it.aapsLogger = AAPSLoggerTest() }
    private fun iob() = Robolectric.buildService(TsunamiFaceIobComplication::class.java).get().also { it.aapsLogger = AAPSLoggerTest() }
    private fun bolus() = Robolectric.buildService(TsunamiBolusComplication::class.java).get().also { it.aapsLogger = AAPSLoggerTest() }

    private fun ShortTextComplicationData.textNow() = text.getTextAt(context.resources, Instant.now()).toString()

    @Test
    fun `with no session the button shows its label and no title`() {
        val data = tsunami().buildComplicationData(ComplicationType.SHORT_TEXT, ComplicationData(tsunamiActiveUntil = 0L), tap()) as ShortTextComplicationData

        assertThat(data.textNow()).isEqualTo(context.getString(R.string.tsunami_button_text))
        assertThat(data.title).isNull()
    }

    @Test
    fun `a session that has ended counts as no session`() {
        val ended = System.currentTimeMillis() - 1_000L
        val data = tsunami().buildComplicationData(ComplicationType.SHORT_TEXT, ComplicationData(tsunamiActiveUntil = ended), tap()) as ShortTextComplicationData

        assertThat(data.title).isNull()
    }

    @Test
    fun `a running session sets the title and shows the time left`() {
        val until = System.currentTimeMillis() + 45 * 60_000L
        val data = tsunami().buildComplicationData(ComplicationType.SHORT_TEXT, ComplicationData(tsunamiActiveUntil = until), tap()) as ShortTextComplicationData

        assertThat(data.title).isNotNull()
        assertThat(data.textNow()).contains("45")
    }

    @Test
    fun `without contact to the phone the button says the state is not known`() {
        val until = System.currentTimeMillis() + 45 * 60_000L
        val data = tsunami().buildNoSyncComplicationData(ComplicationType.SHORT_TEXT, ComplicationData(tsunamiActiveUntil = until), tap()) as ShortTextComplicationData

        assertThat(data.textNow()).isEqualTo(context.getString(R.string.tsunami_button_unknown))
        assertThat(data.title).isNull()
    }

    @Test
    fun `the buttons open their own screens`() {
        assertThat(tsunami().getComplicationAction()).isEqualTo(ComplicationAction.TSUNAMI)
        assertThat(bolus().getComplicationAction()).isEqualTo(ComplicationAction.INSULIN_ONLY)
    }

    @Test
    fun `IOB is shown with contact and hidden without it, because an old number looks current`() {
        val sut = iob()
        val store = sut.getPreviewComplicationData()

        val fresh = sut.buildComplicationData(ComplicationType.SHORT_TEXT, store, tap()) as ShortTextComplicationData
        assertThat(fresh.textNow()).startsWith(store.statusData.iobSum)

        val stale = sut.buildNoSyncComplicationData(ComplicationType.SHORT_TEXT, store, tap()) as ShortTextComplicationData
        assertThat(stale.textNow()).isEqualTo(context.getString(R.string.tsunami_no_value))
    }

    @Test
    fun `other complication types are not offered`() {
        assertThat(tsunami().getPreviewData(ComplicationType.SMALL_IMAGE)).isNull()
        assertThat(iob().getPreviewData(ComplicationType.SMALL_IMAGE)).isNull()
        assertThat(bolus().getPreviewData(ComplicationType.SMALL_IMAGE)).isNull()
    }
}
