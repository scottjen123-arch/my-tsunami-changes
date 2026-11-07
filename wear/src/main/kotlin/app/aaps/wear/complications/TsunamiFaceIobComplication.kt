package app.aaps.wear.complications

import android.app.PendingIntent
import androidx.wear.watchface.complications.data.ComplicationData
import androidx.wear.watchface.complications.data.ComplicationType
import androidx.wear.watchface.complications.data.PlainComplicationText
import androidx.wear.watchface.complications.data.ShortTextComplicationData
import app.aaps.core.interfaces.logging.LTag
import app.aaps.wear.R
import app.aaps.wear.data.ComplicationData as ComplicationStore

/**
 * Local change: insulin on board for the watch face.
 *
 * Unlike [IobIconComplication], this one shows no number once the watch has lost contact with the
 * phone. IOB changes all the time, and an old number here looks like a current one.
 */
class TsunamiFaceIobComplication : ModernBaseComplicationProviderService() {

    override fun buildComplicationData(
        type: ComplicationType,
        data: ComplicationStore,
        complicationPendingIntent: PendingIntent
    ): ComplicationData? = build(type, data.statusData.iobSum + getString(R.string.insulin_unit_short), complicationPendingIntent)

    override fun buildNoSyncComplicationData(
        type: ComplicationType,
        data: ComplicationStore,
        complicationPendingIntent: PendingIntent
    ): ComplicationData? = build(type, getString(R.string.tsunami_no_value), complicationPendingIntent)

    private fun build(type: ComplicationType, value: String, tap: PendingIntent): ComplicationData? {
        if (type != ComplicationType.SHORT_TEXT) {
            aapsLogger.warn(LTag.WEAR, "Unexpected complication type $type")
            return null
        }
        return ShortTextComplicationData.Builder(
            text = PlainComplicationText.Builder(text = value).build(),
            contentDescription = PlainComplicationText.Builder(text = getString(R.string.label_tsunami_face_iob)).build()
        )
            .setTitle(PlainComplicationText.Builder(text = getString(R.string.tsunami_iob_title)).build())
            .setTapAction(tap)
            .build()
    }

    override fun getComplicationAction(): ComplicationAction = ComplicationAction.LOOP_STATUS

    override fun getProviderCanonicalName(): String = TsunamiFaceIobComplication::class.java.canonicalName!!
}
