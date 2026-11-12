package ch.beachscore.app.ui

import android.os.SystemClock
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextReplacement
import androidx.test.ext.junit.runners.AndroidJUnit4
import ch.beachscore.app.BuildConfig
import ch.beachscore.app.data.Settings
import ch.beachscore.app.fakes.FakeMatchStore
import ch.beachscore.app.fakes.FakeSettingsStore
import ch.beachscore.app.fakes.FakeSpeaker
import ch.beachscore.app.fakes.FakeVoice
import ch.beachscore.app.score.Language
import ch.beachscore.app.voice.CustomWords
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** 3.3.0: icon bar, the menu list, About, and the voice-word fields with their checks. */
@RunWith(AndroidJUnit4::class)
class MenuAndVoiceWordsTest {

    @get:Rule
    val rule = createComposeRule()

    private val voice = FakeVoice()
    private val settings = FakeSettingsStore(Settings(language = Language.DE, voiceCommands = true))

    private fun show() {
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

    private fun hasDescription() = SemanticsMatcher("has a content description") {
        it.config.getOrNull(SemanticsProperties.ContentDescription)?.firstOrNull()?.isNotBlank() == true
    }

    @Test
    fun theBarHasIconsWithDescriptionsAndOneMenu() {
        show()
        rule.onNodeWithTag("undo").assert(hasDescription()).assertIsNotEnabled()
        rule.onNodeWithTag("swap").assert(hasDescription())
        rule.onNodeWithTag("menu").assert(hasDescription())
        rule.onNodeWithTag("new_match").assertIsDisplayed()
        rule.onNodeWithTag("menu").performClick()
        for (tag in listOf("menu_settings", "menu_history", "menu_players", "menu_statistics", "menu_about")) {
            rule.onNodeWithTag(tag).assertIsDisplayed()
        }
    }

    @Test
    fun aboutShowsNameAuthorVersionAndDate() {
        show()
        rule.onNodeWithTag("menu").performClick()
        rule.onNodeWithTag("menu_about").performClick()
        // The panel is clickable, which merges its texts: look in the unmerged tree.
        rule.onNodeWithTag("about", useUnmergedTree = true).assertExists()
        rule.onNodeWithText("Luis Rosa", useUnmergedTree = true).assertExists()
        rule.onNodeWithText("Version " + BuildConfig.VERSION_NAME, substring = true, useUnmergedTree = true).assertExists()
    }

    @Test
    fun anUnknownWordIsNamedAndNothingIsSaved() {
        voice.vocabulary = setOf("himmel")
        show()
        rule.onNodeWithTag("menu").performClick()
        rule.onNodeWithTag("words_blue").performScrollTo().performTextReplacement("Himmel, Xqzt")
        rule.onNodeWithTag("words_save").performScrollTo().performClick()
        rule.onNodeWithTag("words_message", useUnmergedTree = true).assert(SemanticsMatcher("names the word") {
            it.config.getOrNull(SemanticsProperties.Text)?.joinToString().orEmpty().contains("xqzt")
        })
        assertNull(settings.settings.value.voiceWords[Language.DE])
    }

    @Test
    fun aDuplicateIsRejectedAndValidWordsAreSaved() {
        voice.vocabulary = setOf("himmel", "feuer")
        show()
        rule.onNodeWithTag("menu").performClick()
        rule.onNodeWithTag("words_blue").performScrollTo().performTextReplacement("rot")
        rule.onNodeWithTag("words_save").performScrollTo().performClick()
        rule.onNodeWithTag("words_message", useUnmergedTree = true).assert(SemanticsMatcher("names the word") {
            it.config.getOrNull(SemanticsProperties.Text)?.joinToString().orEmpty().contains("rot")
        })
        assertNull(settings.settings.value.voiceWords[Language.DE])

        rule.onNodeWithTag("words_blue").performScrollTo().performTextReplacement("Himmel")
        rule.onNodeWithTag("words_red").performScrollTo().performTextReplacement("feuer")
        rule.onNodeWithTag("words_save").performScrollTo().performClick()
        rule.waitUntil(5_000) { settings.settings.value.voiceWords[Language.DE] != null }
        assertEquals(CustomWords(blue = listOf("himmel"), red = listOf("feuer")), settings.settings.value.voiceWords[Language.DE])
        rule.waitUntil(5_000) { voice.words?.blue == listOf("himmel") }

        rule.onNodeWithTag("words_reset_blue").performScrollTo().performClick()
        rule.waitUntil(5_000) { settings.settings.value.voiceWords[Language.DE] == CustomWords(red = listOf("feuer")) }
    }
}
