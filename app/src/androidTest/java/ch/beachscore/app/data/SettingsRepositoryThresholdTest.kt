package ch.beachscore.app.data

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import ch.beachscore.app.score.Language
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** 3.5.0: "Minimum confidence" is stored in hundredths; the 3.4 level names map onto it. */
@RunWith(AndroidJUnit4::class)
class SettingsRepositoryThresholdTest {

    private val dir = File(InstrumentationRegistry.getInstrumentation().targetContext.cacheDir, "threshold-test-${System.nanoTime()}").apply { mkdirs() }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val store by lazy { PreferenceDataStoreFactory.create(scope = scope) { File(dir, "settings.preferences_pb") } }
    private val repo by lazy { SettingsRepository(store, Language.EN) }

    @After
    fun tearDown() {
        scope.cancel()
        dir.deleteRecursively()
    }

    private fun pct() = runBlocking { repo.settings.first() }.voiceMinConfidencePct

    @Test
    fun freshInstallIsNormal() {
        assertEquals(85, pct())
    }

    @Test
    fun theOldLevelsKeepTheirBehaviour() = runBlocking {
        for ((name, expected) in listOf("STRICT" to 95, "NORMAL" to 85, "RELAXED" to 70)) {
            store.edit { it.clear(); it[stringPreferencesKey("voice_sensitivity")] = name }
            assertEquals(name, expected, pct())
        }
    }

    @Test
    fun aSavedNumberReplacesTheOldLevel() = runBlocking {
        store.edit { it[stringPreferencesKey("voice_sensitivity")] = "RELAXED" }
        repo.update { it.copy(voiceMinConfidencePct = 50) }
        assertEquals(50, pct())
        val prefs = store.data.first()
        assertEquals(50, prefs[intPreferencesKey("voice_min_confidence_pct")])
        assertNull("the old key is gone", prefs[stringPreferencesKey("voice_sensitivity")])
        // Another change keeps the number.
        repo.update { it.copy(announce = false) }
        assertEquals(50, pct())
    }

    @Test
    fun valuesFromDiskAreBroughtIntoTheRange() = runBlocking {
        store.edit { it[intPreferencesKey("voice_min_confidence_pct")] = 3 }
        assertEquals(30, pct())
        store.edit { it[intPreferencesKey("voice_min_confidence_pct")] = 99 }
        assertEquals(95, pct())
        store.edit { it[intPreferencesKey("voice_min_confidence_pct")] = 62 }
        assertEquals(60, pct())
    }
}
