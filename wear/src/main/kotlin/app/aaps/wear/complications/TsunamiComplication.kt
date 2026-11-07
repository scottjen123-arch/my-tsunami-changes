package app.aaps.wear.complications

import android.app.PendingIntent
import androidx.wear.watchface.complications.data.ComplicationData
import androidx.wear.watchface.complications.data.ComplicationText
import androidx.wear.watchface.complications.data.ComplicationType
import androidx.wear.watchface.complications.data.CountDownTimeReference
import androidx.wear.watchface.complications.data.PlainComplicationText
import androidx.wear.watchface.complications.data.ShortTextComplicationData
import androidx.wear.watchface.complications.data.TimeDifferenceComplicationText
import androidx.wear.watchface.complications.data.TimeDifferenceStyle
import app.aaps.core.interfaces.logging.LTag
import app.aaps.wear.R
import java.time.Instant
import java.util.concurrent.TimeUnit
import app.aaps.wear.data.ComplicationData as ComplicationStore

/**
 * Local change: the Tsunami button of the watch face.
 *
 * Shows the time left in a running session, or a plain label when none runs. A tap opens the
 * Tsunami screen. The title is set only while a session runs, and the face colours the button by it.
 */
class TsunamiComplication : ModernBaseComplicationProviderService() {

    override fun buildComplicationData(
        type: ComplicationType,
        data: ComplicationStore,
        complicationPendingIntent: PendingIntent
    ): ComplicationData? {
        val activeUntil = data.tsunamiActiveUntil
        return if (activeUntil > System.currentTimeMillis())
            build(
                type,
                TimeDifferenceComplicationText.Builder(TimeDifferenceStyle.SHORT_SINGLE_UNIT, CountDownTimeReference(Instant.ofEpochMilli(activeUntil)))
                    .setMinimumTimeUnit(TimeUnit.MINUTES)
                    .build(),
                getString(R.string.tsunami_button_title),
                complicationPendingIntent
            )
        else build(type, plain(getString(R.string.tsunami_button_text)), null, complicationPendingIntent)
    }

    /** No contact with the phone: the session may have been started or stopped there, so do not show an old state as fact. */
    override fun buildNoSyncComplicationData(
        type: ComplicationType,
        data: ComplicationStore,
        complicationPendingIntent: PendingIntent
    ): ComplicationData? = build(type, plain(getString(R.string.tsunami_button_unknown)), null, complicationPendingIntent)

    private fun build(type: ComplicationType, text: ComplicationText, title: String?, tap: PendingIntent): ComplicationData? {
        if (type != ComplicationType.SHORT_TEXT) {
            aapsLogger.warn(LTag.WEAR, "Unexpected complication type $type")
            return null
        }
        val builder = ShortTextComplicationData.Builder(text = text, contentDescription = plain(getString(R.string.tsunami_title))).setTapAction(tap)
        title?.let { builder.setTitle(plain(it)) }
        return builder.build()
    }

    private fun plain(text: String) = PlainComplicationText.Builder(text = text).build()

    override fun getComplicationAction(): ComplicationAction = ComplicationAction.TSUNAMI

    override fun getProviderCanonicalName(): String = TsunamiComplication::class.java.canonicalName!!
}
