package fr.podcast.shared.account

import java.util.prefs.Preferences

/** Java user preferences (desktop app and CLI), one node per application. */
class PreferencesKeyValueStore(private val node: Preferences) : KeyValueStore {
    override fun get(key: String): String? = node.get(key, null)

    override fun set(key: String, value: String) {
        node.put(key, value)
        node.flush()
    }

    override fun remove(key: String) {
        node.remove(key)
        node.flush()
    }
}
