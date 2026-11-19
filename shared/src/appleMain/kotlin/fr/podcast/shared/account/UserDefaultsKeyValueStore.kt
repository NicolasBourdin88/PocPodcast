package fr.podcast.shared.account

import platform.Foundation.NSUserDefaults

/** App user defaults. Moving the token to the Keychain is planned with the accounts work (step 6). */
class UserDefaultsKeyValueStore(private val defaults: NSUserDefaults = NSUserDefaults.standardUserDefaults) : KeyValueStore {
    override fun get(key: String): String? = defaults.stringForKey(key)

    override fun set(key: String, value: String) {
        defaults.setObject(value, forKey = key)
    }

    override fun remove(key: String) {
        defaults.removeObjectForKey(key)
    }
}
