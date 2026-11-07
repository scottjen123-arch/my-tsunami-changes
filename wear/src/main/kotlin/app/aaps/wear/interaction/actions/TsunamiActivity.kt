package app.aaps.wear.interaction.actions

import android.content.Intent
import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableDoubleStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.wear.activity.ConfirmationActivity
import androidx.wear.compose.foundation.pager.HorizontalPager
import androidx.wear.compose.foundation.pager.rememberPagerState
import androidx.wear.compose.material3.Button
import androidx.wear.compose.material3.HorizontalPageIndicator
import androidx.wear.compose.material3.Icon
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.Text
import app.aaps.core.data.format.NumberFormat
import app.aaps.core.interfaces.rx.bus.RxBus
import app.aaps.core.interfaces.rx.events.EventWearToMobile
import app.aaps.core.interfaces.rx.weardata.EventData.ActionTsunamiPreCheck
import app.aaps.core.interfaces.rx.weardata.EventData.ActionTsunamiStopPreCheck
import app.aaps.core.interfaces.sharedPreferences.SP
import app.aaps.core.keys.DoubleKey
import app.aaps.core.keys.IntKey
import app.aaps.core.keys.interfaces.Preferences
import app.aaps.wear.R
import app.aaps.wear.data.ComplicationDataRepository
import app.aaps.wear.di.WearMetroActivity
import dev.zacsweers.metro.Inject
import kotlin.math.ceil
import kotlin.math.roundToInt

/**
 * Local change: start a Tsunami session with an optional prebolus, or stop the running session.
 *
 * This only asks the phone. The phone applies the limits and sends back a confirmation with the
 * real values, and nothing is given until the wearer accepts that too. While a session runs this
 * screen offers no insulin: a stale session state must not restart the timer, and a plain bolus
 * has its own button on the face.
 */
class TsunamiActivity : WearMetroActivity() {

    @Inject lateinit var rxBus: RxBus
    @Inject lateinit var preferences: Preferences
    @Inject lateinit var sp: SP
    @Inject lateinit var complicationDataRepository: ComplicationDataRepository

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val steps = TsunamiInputRules.insulinSteps(
            preferences.get(DoubleKey.TsuButtonIncrement1),
            preferences.get(DoubleKey.TsuButtonIncrement2),
            preferences.get(DoubleKey.TsuButtonIncrement3)
        )
        val maxBolus = sp.getDouble(getString(R.string.key_treatments_safety_max_bolus), 3.0)
        val defaultDuration = TsunamiInputRules.startDuration(preferences.get(IntKey.TsuDefaultDuration))

        setContent {
            MaterialTheme {
                val data by complicationDataRepository.complicationData.collectAsState(initial = null)
                // Nothing is drawn until the stored state is read, so a running session never shows the start screen for a moment
                data?.let { stored ->
                    val minutesLeft = TsunamiInputRules.minutesLeft(stored.tsunamiActiveUntil, System.currentTimeMillis())
                    if (minutesLeft > 0) TsunamiRunningScreen(minutesLeft) { requestStop() }
                    else TsunamiStartPager(steps, maxBolus, defaultDuration) { insulin, duration -> requestStart(insulin, duration) }
                }
            }
        }
    }

    private fun requestStart(insulin: Double, durationMinutes: Int) {
        rxBus.send(EventWearToMobile(ActionTsunamiPreCheck(insulin, durationMinutes)))
        showSentAndFinish(R.string.tsunami_requested)
    }

    private fun requestStop() {
        rxBus.send(EventWearToMobile(ActionTsunamiStopPreCheck(System.currentTimeMillis())))
        showSentAndFinish(R.string.tsunami_stop_requested)
    }

    private fun showSentAndFinish(message: Int) {
        startActivity(
            Intent(this, ConfirmationActivity::class.java).apply {
                putExtra(ConfirmationActivity.EXTRA_ANIMATION_TYPE, ConfirmationActivity.SUCCESS_ANIMATION)
                putExtra(ConfirmationActivity.EXTRA_MESSAGE, getString(message))
            }
        )
        finish()
    }
}

/** Local change: the plain rules of the Tsunami screen, apart from Android so that they can be tested. */
internal object TsunamiInputRules {

    const val MIN_DURATION = 5.0
    const val MAX_DURATION = 300.0
    private const val FINE_STEP = 0.1

    /** Used when the phone's default duration is 0, which on the phone means "enter it each time". */
    private const val FALLBACK_DURATION = 180

    fun insulinSteps(increment1: Double, increment2: Double, increment3: Double): List<Double> =
        listOf(increment1, increment2, increment3).map { (it * 10).roundToInt() / 10.0 }.map { if (it > 0.0) it else FINE_STEP }

    fun startDuration(phoneDefault: Int): Int =
        (if (phoneDefault > 0) phoneDefault else FALLBACK_DURATION).coerceIn(MIN_DURATION.toInt(), MAX_DURATION.toInt())

    /** Rounded up, so a session with seconds left still counts as running. 0 when no session runs. */
    fun minutesLeft(activeUntil: Long, now: Long): Int =
        if (activeUntil <= now) 0 else ceil((activeUntil - now) / 60_000.0).toInt()
}

@Composable
private fun TsunamiStartPager(
    steps: List<Double>,
    maxBolus: Double,
    defaultDuration: Int,
    onConfirm: (insulin: Double, durationMinutes: Int) -> Unit
) {
    var insulin by remember { mutableDoubleStateOf(0.0) }
    var duration by remember { mutableDoubleStateOf(defaultDuration.toDouble()) }
    val pagerState = rememberPagerState(pageCount = { 3 })

    Box(modifier = Modifier.fillMaxSize()) {
        HorizontalPager(state = pagerState) { page ->
            when (page) {
                0    -> PlusMinusInputScreen(
                    value = insulin,
                    onValueChange = { insulin = it },
                    valueRange = 0.0..maxBolus,
                    stepValues = steps,
                    format = NumberFormat.DECIMAL_1,
                    label = stringResource(R.string.action_insulin_units),
                    allowZero = true,
                    isActive = pagerState.currentPage == 0,
                    enabled = !pagerState.isScrollInProgress,
                    valueColor = InsulinBlue,
                    title = stringResource(R.string.tsunami_prebolus),
                )

                1    -> PlusMinusInputScreen(
                    value = duration,
                    onValueChange = { duration = it },
                    valueRange = TsunamiInputRules.MIN_DURATION..TsunamiInputRules.MAX_DURATION,
                    stepValues = listOf(5.0, 15.0, 30.0),
                    format = NumberFormat.INTEGER,
                    label = stringResource(R.string.tsunami_duration),
                    isActive = pagerState.currentPage == 1,
                    enabled = !pagerState.isScrollInProgress,
                    title = stringResource(R.string.tsunami_title),
                )

                else -> TsunamiConfirmScreen(insulin, duration.roundToInt()) { onConfirm(insulin, duration.roundToInt()) }
            }
        }
        HorizontalPageIndicator(
            pagerState = pagerState,
            modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 4.dp)
        )
    }
}

@Composable
private fun TsunamiConfirmScreen(insulin: Double, durationMinutes: Int, onConfirm: () -> Unit) {
    val haptic = LocalHapticFeedback.current
    var sent by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 24.dp, vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text(
            text = stringResource(R.string.tsunami_title),
            color = Color.White,
            fontSize = 18.sp,
            fontWeight = FontWeight.Bold
        )
        Spacer(Modifier.height(8.dp))
        Box(
            modifier = Modifier
                .size(90.dp)
                .clip(CircleShape)
                .clickable(enabled = !sent) {
                    sent = true
                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                    onConfirm()
                },
            contentAlignment = Alignment.Center
        ) {
            Icon(
                painter = painterResource(R.drawable.ic_confirm),
                contentDescription = stringResource(R.string.confirm),
                tint = ConfirmGreen,
                modifier = Modifier.fillMaxSize()
            )
        }
        Spacer(Modifier.height(4.dp))
        Text(
            text = "${NumberFormat.DECIMAL_1.format(insulin)} ${stringResource(R.string.insulin_unit_short)}",
            color = InsulinBlue,
            fontSize = 22.sp,
            fontWeight = FontWeight.Bold
        )
        Text(
            text = stringResource(R.string.tsunami_minutes, durationMinutes),
            color = Color.White,
            fontSize = 16.sp
        )
    }
}

@Composable
private fun TsunamiRunningScreen(minutesLeft: Int, onStop: () -> Unit) {
    var sent by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 24.dp, vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text(
            text = stringResource(R.string.tsunami_title),
            color = Color.White,
            fontSize = 18.sp,
            fontWeight = FontWeight.Bold
        )
        Spacer(Modifier.height(4.dp))
        Text(
            text = stringResource(R.string.tsunami_minutes_left, minutesLeft),
            color = ConfirmGreen,
            fontSize = 22.sp,
            fontWeight = FontWeight.Bold
        )
        Spacer(Modifier.height(12.dp))
        Button(
            enabled = !sent,
            onClick = {
                sent = true
                onStop()
            }
        ) {
            Text(text = stringResource(R.string.tsunami_stop))
        }
    }
}
