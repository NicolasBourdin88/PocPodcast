package fr.podcast.shared.account

import android.content.Context

/** App-private preferences. Device tokens are revocable per device (ADR 0005). */
class SharedPreferencesKeyValueStore(context: Context, name: String = "podcast") : KeyValueStore {
    private val preferences = context.getSharedPreferences(name, Context.MODE_PRIVATE)

    override fun get(key: String): String? = preferences.getString(key, null)

    override fun set(key: String, value: String) {
        preferences.edit().putString(key, value).apply()
    }

    override fun remove(key: String) {
        preferences.edit().remove(key).apply()
    }
}
