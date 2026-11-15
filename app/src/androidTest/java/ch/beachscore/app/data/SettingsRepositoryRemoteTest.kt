package ch.beachscore.app.data

import android.view.KeyEvent
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import ch.beachscore.app.input.RemoteAction
import ch.beachscore.app.input.RemoteKeys
import ch.beachscore.app.score.Language
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** 3.4.0: remote buttons are stored; the old volume key toggle is handed over once. (DataStore cannot replace files on Windows JVMs, hence instrumented.) */
@RunWith(AndroidJUnit4::class)
class SettingsRepositoryRemoteTest {

    private val dir = File(InstrumentationRegistry.getInstrumentation().targetContext.cacheDir, "remote-test-${System.nanoTime()}").apply { mkdirs() }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val store by lazy { PreferenceDataStoreFactory.create(scope = scope) { File(dir, "settings.preferences_pb") } }
    private val repo by lazy { SettingsRepository(store, Language.EN) }

    @After
    fun tearDown() {
        scope.cancel()
        dir.deleteRecursively()
    }

    private fun settings() = runBlocking { repo.settings.first() }

    @Test
    fun remoteButtonsAreStoredAndReadBack() = runBlocking {
        val keys = RemoteKeys().assign(RemoteAction.BLUE_POINT, KeyEvent.KEYCODE_MEDIA_NEXT).assign(RemoteAction.UNDO, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE)
        repo.update { it.copy(remoteKeys = keys) }
        assertEquals(keys, settings().remoteKeys)
        assertEquals("${KeyEvent.KEYCODE_MEDIA_NEXT}=BLUE_POINT,${KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE}=UNDO", store.data.first()[stringPreferencesKey("remote_keys")])
    }

    @Test
    fun aFreshInstallHasNoRemoteButtonsAndNothingToMigrate() {
        val s = settings()
        assertTrue(s.remoteKeys.isEmpty)
        assertFalse(s.legacyVolumeKeys)
    }

    @Test
    fun theOldToggleOnIsReportedUntilRemoteButtonsAreStored() = runBlocking {
        store.edit { it[booleanPreferencesKey("hardware_buttons")] = true }
        assertTrue(settings().legacyVolumeKeys)
        // Another setting changes first: the hand-over is still pending.
        repo.update { it.copy(language = Language.DE) }
        assertTrue(settings().legacyVolumeKeys)
        repo.update { it.copy(remoteKeys = RemoteKeys.fromVolumeKeys(blueOnLeft = true), legacyVolumeKeys = false) }
        val s = settings()
        assertFalse(s.legacyVolumeKeys)
        assertEquals(RemoteAction.BLUE_POINT, s.remoteKeys.actionFor(KeyEvent.KEYCODE_VOLUME_UP))
        assertNull("the old key is gone", store.data.first()[booleanPreferencesKey("hardware_buttons")])
        // Clearing everything later does not bring the volume keys back.
        repo.update { it.copy(remoteKeys = RemoteKeys()) }
        assertFalse(settings().legacyVolumeKeys)
        assertTrue(settings().remoteKeys.isEmpty)
    }

    @Test
    fun theOldToggleOffMigratesToNothing() = runBlocking {
        store.edit { it[booleanPreferencesKey("hardware_buttons")] = false }
        assertFalse(settings().legacyVolumeKeys)
        repo.update { it.copy(announce = false) }
        assertNull(store.data.first()[booleanPreferencesKey("hardware_buttons")])
        assertTrue(settings().remoteKeys.isEmpty)
    }
}
