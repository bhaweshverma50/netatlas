package atlas.netatlas

import android.content.Context
import atlas.net.ServerUrl

/**
 * Tiny SharedPreferences-backed store for the user-configurable backend URL.
 *
 * On a real phone the emulator alias (10.0.2.2) is unreachable, so a release build must
 * let the user point the app at their own server. [baseUrl] persists that choice:
 *  - the getter returns the stored value, or [ServerUrl.DEFAULT] for a fresh install;
 *  - the setter stores the [ServerUrl.normalize]d form, ignoring invalid input.
 *
 * Validation/normalization lives in the pure [ServerUrl] (commonMain, unit-tested);
 * this class only handles persistence.
 */
class Settings(context: Context) {

    private val prefs = context.applicationContext
        .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /** The backend base URL. Get falls back to [ServerUrl.DEFAULT]; set normalizes first. */
    var baseUrl: String
        get() = prefs.getString(KEY_BASE_URL, null) ?: ServerUrl.DEFAULT
        set(value) {
            val normalized = ServerUrl.normalize(value) ?: return
            prefs.edit().putString(KEY_BASE_URL, normalized).apply()
        }

    private companion object {
        const val PREFS_NAME = "netatlas-settings"
        const val KEY_BASE_URL = "base_url"
    }
}
