package cn.embeddedquiz

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class AccentPreferenceTest {
    private val prefs
        get() = ApplicationProvider.getApplicationContext<Context>().getSharedPreferences("settings", 0)

    @Before
    fun clear() {
        prefs.edit().clear().commit()
    }

    @Test
    fun defaultsToFirstAccentAndSurvivesARestart() {
        val first = AccentPreference(prefs)
        assertEquals(ACCENTS.first().id, first.id)
        first.choose("teal")
        assertEquals("teal", first.id)
        assertEquals("teal", prefs.getString(AccentPreference.KEY, null))
        assertEquals("teal", AccentPreference(prefs).id)
    }

    @Test
    fun staleStoredValueFallsBackAndUnknownChoiceIsRejected() {
        prefs.edit().putString(AccentPreference.KEY, "removed-color").commit()
        val accent = AccentPreference(prefs)
        assertEquals(ACCENTS.first().id, accent.id)
        assertThrows(IllegalArgumentException::class.java) { accent.choose("removed-color") }
        assertEquals(ACCENTS.first().id, accent.id)
    }
}
