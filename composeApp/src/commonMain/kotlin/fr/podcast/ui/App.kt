package fr.podcast.ui

import fr.podcast.shared.navigation.Destination
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import fr.podcast.shared.account.AccountService
import fr.podcast.shared.account.AccountStore
import fr.podcast.shared.i18n.Language
import fr.podcast.ui.account.SettingsScreen
import fr.podcast.ui.account.SignInScreen
import fr.podcast.ui.account.accountsSection
import fr.podcast.ui.player.LocalPlayback
import fr.podcast.ui.theme.LocalStrings
import fr.podcast.ui.theme.PodcastTheme
import fr.podcast.ui.theme.platformLanguage
import kotlinx.coroutines.launch

@Composable
fun App(environment: AppEnvironment, language: Language = platformLanguage(), initialDestination: Destination = Destination.Home) {
    PodcastTheme(language) {
        Surface(Modifier.fillMaxSize()) {
            AppRoot(environment, initialDestination)
        }
    }
}

/**
 * Without an account: sign-in (or demo data). With accounts: the active one feeds the
 * whole app through a RemotePodcastRepository; Settings switches between accounts (ADR 0006).
 */
@Composable
private fun AppRoot(environment: AppEnvironment, initialDestination: Destination) {
    val strings = LocalStrings.current
    val store = remember { AccountStore(environment.keyValueStore) }
    val service = remember { AccountService(environment.httpClient, environment.deviceName) }
    val accounts by store.state.collectAsState()
    var demo by rememberSaveable { mutableStateOf(false) }
    var addingAccount by rememberSaveable { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val active = accounts.active

    if (addingAccount || (active == null && !demo)) {
        SignInScreen(
            defaultServerUrl = environment.defaultServerUrl,
            signIn = service::signIn,
            onSignedIn = { account ->
                store.save(account)
                demo = false
                addingAccount = false
            },
            onDemo = if (accounts.accounts.isEmpty() && !addingAccount) ({ demo = true }) else null,
            onCancel = if (addingAccount) ({ addingAccount = false }) else null,
        )
        return
    }

    val usingDemo = active == null
    val repository = remember(active) { environment.clientFor(active) }
    // The demo plays without sound; an account plays on the platform's player (ADR 0008).
    // Leaving the screens does not stop playback: it goes on in the background.
    val playback = if (usingDemo) environment.demoPlayback else environment.playback
    LaunchedEffect(playback, repository) {
        environment.stopOtherThan(playback)
        playback.attach(repository)
    }
    CompositionLocalProvider(LocalPlayback provides playback) {
        MainScaffold(
            client = repository,
            accountLabel = active?.let { "${it.displayName} · ${it.serverName}" } ?: strings.demoAccount,
            onSessionExpired = { active?.let { store.remove(it.id) } },
            initialDestination = initialDestination,
            settings = { modifier ->
                SettingsScreen(
                    client = repository,
                    // The device keeps the name shown for the account up to date.
                    onProfileChanged = { user -> active?.let { store.update(it.copy(displayName = user.displayName, avatar = user.avatar)) } },
                    modifier = modifier,
                ) {
                    accountsSection(
                        state = accounts,
                        demo = usingDemo,
                        onSelect = { account ->
                            demo = false
                            store.select(account.id)
                        },
                        onAdd = { addingAccount = true },
                        onSignOut = { account ->
                            scope.launch {
                                service.signOut(account)
                                store.remove(account.id)
                            }
                        },
                        onLeaveDemo = { demo = false },
                    )
                }
            },
        )
    }
}
