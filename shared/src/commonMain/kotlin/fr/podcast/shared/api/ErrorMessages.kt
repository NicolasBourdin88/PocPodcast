package fr.podcast.shared.api

import fr.podcast.shared.i18n.Strings

/** What to tell the user about a failure, in their language. */
fun Throwable.userMessage(strings: Strings): String = when (this) {
    is ApiException.Unreachable -> strings.serverUnreachable
    is ApiException.NotAPodcastServer -> strings.notAPodcastServer
    is ApiException.Unauthorized -> strings.invalidCredentials
    else -> strings.loadingError
}
