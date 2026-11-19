package fr.podcast.shared.account

import fr.podcast.shared.model.Avatar
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** An account on one server: a client can hold several, on several servers (ADR 0006). */
@Serializable
data class Account(
    val serverUrl: String,
    /** Name announced by the server's root resource. */
    val serverName: String,
    val username: String,
    val displayName: String,
    /** Device token (ADR 0005); the password is never stored. */
    val token: String,
    /** The user's [Avatar] id, kept to show the account (ADR 0013). */
    val avatar: String? = null,
) {
    val id: String get() = "$username@$serverUrl"

    val picture: Avatar get() = Avatar.of(avatar, username)
}

/** Small persistent storage, implemented by each platform (preferences, user defaults…). */
interface KeyValueStore {
    fun get(key: String): String?
    fun set(key: String, value: String)
    fun remove(key: String)
}

class InMemoryKeyValueStore : KeyValueStore {
    private val values = mutableMapOf<String, String>()
    override fun get(key: String) = values[key]
    override fun set(key: String, value: String) { values[key] = value }
    override fun remove(key: String) { values.remove(key) }
}

@Serializable
data class AccountsState(val accounts: List<Account> = emptyList(), val activeId: String? = null) {
    val active: Account? get() = accounts.firstOrNull { it.id == activeId }
}

/** The accounts saved on this device and the active one, persisted in [storage]. */
class AccountStore(private val storage: KeyValueStore) {

    private val json = Json { ignoreUnknownKeys = true }

    private val mutableState = MutableStateFlow(load())
    val state: StateFlow<AccountsState> = mutableState.asStateFlow()

    /** Adds or replaces [account] (same user on the same server) and makes it active. */
    fun save(account: Account) = change { state ->
        AccountsState(state.accounts.filterNot { it.id == account.id } + account, account.id)
    }

    /** Replaces the saved [account] in place (a new display name…); the active account stays. */
    fun update(account: Account) = change { state ->
        state.copy(accounts = state.accounts.map { if (it.id == account.id) account else it })
    }

    fun select(id: String) = change { state -> if (state.accounts.any { it.id == id }) state.copy(activeId = id) else state }

    /** Removes the account; another one becomes active if there is any. */
    fun remove(id: String) = change { state ->
        val remaining = state.accounts.filterNot { it.id == id }
        AccountsState(remaining, state.activeId.takeIf { it != id } ?: remaining.firstOrNull()?.id)
    }

    private fun change(transform: (AccountsState) -> AccountsState) {
        mutableState.update(transform)
        storage.set(KEY, json.encodeToString(AccountsState.serializer(), mutableState.value))
    }

    private fun load(): AccountsState =
        storage.get(KEY)?.let { runCatching { json.decodeFromString(AccountsState.serializer(), it) }.getOrNull() } ?: AccountsState()

    private companion object {
        const val KEY = "accounts"
    }
}
