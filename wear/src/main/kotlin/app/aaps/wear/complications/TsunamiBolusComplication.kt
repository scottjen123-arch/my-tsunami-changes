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
 * Local change: the bolus button of the watch face. It gives insulin only and does not touch a
 * running Tsunami session.
 */
class TsunamiBolusComplication : ModernBaseComplicationProviderService() {

    override fun buildComplicationData(
        type: ComplicationType,
        data: ComplicationStore,
        complicationPendingIntent: PendingIntent
    ): ComplicationData? {
        if (type != ComplicationType.SHORT_TEXT) {
            aapsLogger.warn(LTag.WEAR, "Unexpected complication type $type")
            return null
        }
        return ShortTextComplicationData.Builder(
            text = PlainComplicationText.Builder(text = getString(R.string.tsunami_bolus_button_text)).build(),
            contentDescription = PlainComplicationText.Builder(text = getString(R.string.label_tsunami_face_bolus)).build()
        )
            .setTapAction(complicationPendingIntent)
            .build()
    }

    override fun getComplicationAction(): ComplicationAction = ComplicationAction.INSULIN_ONLY

    override fun getProviderCanonicalName(): String = TsunamiBolusComplication::class.java.canonicalName!!
}
