package ch.beachscore.app.ui

import android.os.SystemClock
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import ch.beachscore.app.data.Settings
import ch.beachscore.app.fakes.FakeMatchStore
import ch.beachscore.app.fakes.FakeSettingsStore
import ch.beachscore.app.fakes.FakeSpeaker
import ch.beachscore.app.fakes.FakeVoice
import ch.beachscore.app.voice.InputDevice
import ch.beachscore.app.voice.MicChoice
import ch.beachscore.app.voice.MicKind
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** The "Microphone" setting and the in-use label, with a fake device list (no USB mic here). */
@RunWith(AndroidJUnit4::class)
class MicrophoneSettingsTest {

    @get:Rule
    val rule = createComposeRule()

    private val phone = InputDevice(3, MicKind.BUILTIN, "moto g15 power")
    private val receiver = InputDevice(31, MicKind.USB, "Wireless Mic Rx")
    private val voice = FakeVoice()

    private fun show(settings: FakeSettingsStore) {
        val vm = ScoreboardViewModel(FakeMatchStore(), settings, FakeSpeaker(), clock = SystemClock::elapsedRealtime, voice = voice)
        rule.setContent {
            BeachScoreTheme {
                val state by vm.uiState.collectAsState()
                ScoreboardRoute(vm, state)
            }
        }
        rule.waitUntil(5_000) { vm.uiState.value.loaded }
        rule.runOnIdle {
            vm.onMicPermission(true)
            vm.onForegroundChanged(true)
        }
    }

    @Test
    fun choosingTheUsbReceiverStoresItAndTheIndicatorShowsUsb() {
        val settings = FakeSettingsStore(Settings(voiceCommands = true))
        show(settings)
        rule.runOnIdle {
            voice.microphones.value = listOf(phone, receiver)
            voice.active.value = phone
        }
        rule.onNodeWithTag("mic_in_use", useUnmergedTree = true).assertTextEquals("Phone")

        rule.onNodeWithTag("menu").performClick()
        rule.onNodeWithTag("mic_auto").performScrollTo().assertIsSelected()
        rule.onNodeWithTag("microphone_in_use", useUnmergedTree = true).assertTextEquals("In use: Phone · moto g15 power")
        rule.onNodeWithTag("mic_USB_Wireless Mic Rx").performScrollTo().performClick()
        rule.waitUntil(5_000) { voice.microphone == MicChoice(MicKind.USB, "Wireless Mic Rx") }
        assertEquals(MicChoice(MicKind.USB, "Wireless Mic Rx"), runBlocking { settings.settings.first() }.microphone)
        rule.onNodeWithTag("mic_USB_Wireless Mic Rx").assertIsSelected()

        rule.runOnIdle { voice.active.value = receiver }
        rule.onNodeWithTag("microphone_in_use", useUnmergedTree = true).assertTextEquals("In use: USB · Wireless Mic Rx")
        rule.onNodeWithTag("settings_close").performClick()
        rule.onNodeWithTag("mic_in_use", useUnmergedTree = true).assertTextEquals("USB")
    }

    @Test
    fun anUnpluggedChoiceStaysVisibleAndThePhoneIsShownInUse() {
        show(FakeSettingsStore(Settings(voiceCommands = true, microphone = MicChoice(MicKind.USB, "Wireless Mic Rx"))))
        rule.runOnIdle {
            voice.microphones.value = listOf(phone)
            voice.active.value = phone
        }
        rule.onNodeWithTag("menu").performClick()
        rule.onNodeWithText("USB · Wireless Mic Rx (not connected)").performScrollTo().assertIsDisplayed()
        rule.onNodeWithTag("mic_USB_Wireless Mic Rx").assertIsSelected()
        rule.onNodeWithTag("microphone_in_use", useUnmergedTree = true).assertTextEquals("In use: Phone · moto g15 power")
    }
}
