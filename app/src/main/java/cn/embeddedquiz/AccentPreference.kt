package cn.embeddedquiz

import android.content.SharedPreferences
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * The chosen theme accent. It is a display preference kept in SharedPreferences, outside Room,
 * so database migrations, learning backups and progress resets never touch it.
 */
class AccentPreference(private val prefs: SharedPreferences) {
    var id by mutableStateOf(accentOf(prefs.getString(KEY, null)).id)
        private set

    /** Writes durably first, so the screen only changes once the choice is on disk. */
    fun choose(value: String) {
        require(ACCENTS.any { it.id == value })
        if (value == id) return
        prefs.writeDurably { putString(KEY, value) }
        id = value
    }

    companion object {
        const val KEY = "accent"
    }
}
