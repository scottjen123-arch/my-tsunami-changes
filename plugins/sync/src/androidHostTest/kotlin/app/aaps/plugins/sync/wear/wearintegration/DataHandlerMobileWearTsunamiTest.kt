package app.aaps.plugins.sync.wear.wearintegration

import app.aaps.core.data.model.TSU
import app.aaps.core.data.pump.defs.PumpDescription
import app.aaps.core.data.ue.Sources
import app.aaps.core.data.ui.ConfirmationLine
import app.aaps.core.data.ui.ConfirmationRole
import app.aaps.core.interfaces.aps.APS
import app.aaps.core.interfaces.aps.APSResult
import app.aaps.core.interfaces.aps.Loop
import app.aaps.core.interfaces.bolus.BatchAction
import app.aaps.core.interfaces.bolus.BatchExecutor
import app.aaps.core.interfaces.bolus.WizardBolusExecutor
import app.aaps.core.interfaces.bolus.WizardExecutor
import app.aaps.core.interfaces.clientcontrol.ActionProgress
import app.aaps.core.interfaces.db.PersistenceLayer
import app.aaps.core.interfaces.maintenance.ImportExportPrefs
import app.aaps.core.interfaces.nsclient.ProcessedDeviceStatusData
import app.aaps.core.interfaces.pump.PumpStatusProvider
import app.aaps.core.interfaces.pump.PumpWithConcentration
import app.aaps.core.interfaces.queue.CommandQueue
import app.aaps.core.interfaces.receivers.ReceiverStatusStore
import app.aaps.core.interfaces.rx.events.EventMobileToWear
import app.aaps.core.interfaces.rx.weardata.EventData
import app.aaps.core.interfaces.ui.UiInteraction
import app.aaps.core.interfaces.utils.TrendCalculator
import app.aaps.core.keys.BooleanKey
import app.aaps.core.keys.interfaces.TextRef
import app.aaps.core.objects.runningMode.RunningModeGuard
import app.aaps.core.objects.wizard.QuickWizard
import app.aaps.shared.tests.TestBaseWithProfile
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.Mock
import org.mockito.kotlin.any
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.eq
import org.mockito.kotlin.never
import org.mockito.kotlin.verifyBlocking
import org.mockito.kotlin.whenever

/**
 * Local change: Tsunami and prebolus from the watch.
 *
 * The phone must build the same batch as its own Tsunami dialog, must refuse by itself when wear
 * control is off, and must never restart the timer of a running session because the watch was out of date.
 */
class DataHandlerMobileWearTsunamiTest : TestBaseWithProfile() {

    @Mock private lateinit var loop: Loop
    @Mock private lateinit var processedDeviceStatusData: ProcessedDeviceStatusData
    @Mock private lateinit var receiverStatusStore: ReceiverStatusStore
    @Mock private lateinit var quickWizard: QuickWizard
    @Mock private lateinit var trendCalculator: TrendCalculator
    @Mock private lateinit var commandQueue: CommandQueue
    @Mock private lateinit var uiInteraction: UiInteraction
    @Mock private lateinit var persistenceLayer: PersistenceLayer
    @Mock private lateinit var importExportPrefs: ImportExportPrefs
    @Mock private lateinit var pumpStatusProvider: PumpStatusProvider
    @Mock private lateinit var runningModeGuard: RunningModeGuard
    @Mock private lateinit var wizardBolusExecutor: WizardBolusExecutor
    @Mock private lateinit var batchExecutor: BatchExecutor
    @Mock private lateinit var wizardExecutor: WizardExecutor
    @Mock private lateinit var pump: PumpWithConcentration
    @Mock private lateinit var aps: APS

    private lateinit var sut: DataHandlerMobile
    private val session = TSU(timestamp = 1_000L, duration = 60_000L, tsunamiMode = 2)

    @BeforeEach fun prepare() = runTest {
        sut = DataHandlerMobile(
            context, rxBus, aapsLogger, rh, preferences, config,
            iobCobCalculator, processedTbrEbData, smbGlucoseStatusProvider, profileFunction, profileUtil,
            loop, processedDeviceStatusData, receiverStatusStore, quickWizard, trendCalculator, dateUtil,
            constraintsChecker, activePlugin, commandQueue, fabricPrivacy, uiInteraction,
            persistenceLayer, importExportPrefs, decimalFormatter, pumpStatusProvider,
            ch, runningModeGuard, wizardBolusExecutor, batchExecutor, wizardExecutor
        )
        whenever(rh.gs(any<TextRef>())).thenReturn("TEXT")
        whenever(preferences.get(BooleanKey.WearControl)).thenReturn(true)
        whenever(activePlugin.activeAPS).thenReturn(aps)
        whenever(aps.algorithm).thenReturn(APSResult.Algorithm.TSUNAMI)
        whenever(activePlugin.activePump).thenReturn(pump)
        whenever(pump.pumpDescription).thenReturn(PumpDescription().apply { bolusStep = 0.05 })
        whenever(persistenceLayer.getTsunamiActiveAt(any())).thenReturn(null)
        whenever(batchExecutor.prepare(any(), any(), any())).thenReturn(ActionProgress.Prepared(77L, emptyList()))
    }

    private inline fun captureAll(block: () -> Unit): List<EventData> {
        val out = mutableListOf<EventData>()
        val job = CoroutineScope(Dispatchers.Unconfined).launch(start = CoroutineStart.UNDISPATCHED) {
            rxBus.toFlow(EventMobileToWear::class).collect { out += it.payload }
        }
        block()
        job.cancel()
        return out
    }

    private suspend fun preparedActions(): List<BatchAction> {
        val captor = argumentCaptor<List<BatchAction>>()
        verifyBlocking(batchExecutor) { prepare(captor.capture(), eq(Sources.Wear), any()) }
        return captor.firstValue
    }

    private fun List<EventData>.confirm() = filterIsInstance<EventData.ConfirmAction>().single()

    @Test fun `start with a prebolus asks for the bolus and the session, and the OK carries only the parked id`() = runTest {
        val sent = captureAll { sut.handleTsunamiPreCheck(EventData.ActionTsunamiPreCheck(insulin = 2.0, durationMinutes = 180)) }

        val actions = preparedActions()
        assertThat(actions).hasSize(2)
        assertThat((actions[0] as BatchAction.Bolus).insulin).isEqualTo(2.0)
        assertThat((actions[0] as BatchAction.Bolus).carbs).isEqualTo(0)
        assertThat((actions[1] as BatchAction.Tsunami).durationMinutes).isEqualTo(180)
        assertThat(sent.confirm().returnCommand).isEqualTo(EventData.ActionBolusConfirmed(77L))
    }

    @Test fun `start without a prebolus asks only for the session`() = runTest {
        captureAll { sut.handleTsunamiPreCheck(EventData.ActionTsunamiPreCheck(insulin = 0.0, durationMinutes = 60)) }

        assertThat(preparedActions().single()).isInstanceOf(BatchAction.Tsunami::class.java)
    }

    @Test fun `the prebolus is rounded down to what the pump can give`() = runTest {
        captureAll { sut.handleTsunamiPreCheck(EventData.ActionTsunamiPreCheck(insulin = 1.27, durationMinutes = 60)) }

        assertThat((preparedActions()[0] as BatchAction.Bolus).insulin).isWithin(1e-9).of(1.25)
    }

    @Test fun `a very long duration is limited as on the phone`() = runTest {
        captureAll { sut.handleTsunamiPreCheck(EventData.ActionTsunamiPreCheck(insulin = 0.0, durationMinutes = 999)) }

        assertThat((preparedActions().single() as BatchAction.Tsunami).durationMinutes).isEqualTo(300)
    }

    @Test fun `no duration is refused`() = runTest {
        captureAll { sut.handleTsunamiPreCheck(EventData.ActionTsunamiPreCheck(insulin = 1.0, durationMinutes = 0)) }

        verifyBlocking(batchExecutor, never()) { prepare(any(), any(), any()) }
    }

    @Test fun `start while a session runs is refused and the watch is corrected`() = runTest {
        whenever(persistenceLayer.getTsunamiActiveAt(any())).thenReturn(session)

        val sent = captureAll { sut.handleTsunamiPreCheck(EventData.ActionTsunamiPreCheck(insulin = 2.0, durationMinutes = 180)) }

        verifyBlocking(batchExecutor, never()) { prepare(any(), any(), any()) }
        assertThat(sent.filterIsInstance<EventData.TsunamiState>().single().activeUntil).isEqualTo(session.end)
    }

    @Test fun `start is refused when wear control is off`() = runTest {
        whenever(preferences.get(BooleanKey.WearControl)).thenReturn(false)

        captureAll { sut.handleTsunamiPreCheck(EventData.ActionTsunamiPreCheck(insulin = 2.0, durationMinutes = 180)) }

        verifyBlocking(batchExecutor, never()) { prepare(any(), any(), any()) }
    }

    @Test fun `start is refused when Tsunami is not the active APS`() = runTest {
        whenever(aps.algorithm).thenReturn(APSResult.Algorithm.SMB)

        captureAll { sut.handleTsunamiPreCheck(EventData.ActionTsunamiPreCheck(insulin = 2.0, durationMinutes = 180)) }

        verifyBlocking(batchExecutor, never()) { prepare(any(), any(), any()) }
    }

    @Test fun `stop asks for a cancel and tells the wearer what will happen`() = runTest {
        whenever(persistenceLayer.getTsunamiActiveAt(any())).thenReturn(session)
        whenever(batchExecutor.prepare(any(), any(), any()))
            .thenReturn(ActionProgress.Prepared(88L, listOf(ConfirmationLine(ConfirmationRole.NORMAL, "other"))))

        val sent = captureAll { sut.handleTsunamiStopPreCheck() }

        assertThat(preparedActions().single()).isEqualTo(BatchAction.CancelTsunami)
        val confirm = sent.confirm()
        assertThat(confirm.returnCommand).isEqualTo(EventData.ActionBolusConfirmed(88L))
        assertThat(confirm.lines.map { it.text }).containsExactly("TEXT", "other").inOrder()
    }

    @Test fun `stop with no session is refused and the watch is corrected`() = runTest {
        val sent = captureAll { sut.handleTsunamiStopPreCheck() }

        verifyBlocking(batchExecutor, never()) { prepare(any(), any(), any()) }
        assertThat(sent.filterIsInstance<EventData.TsunamiState>().single().activeUntil).isEqualTo(0L)
    }
}
