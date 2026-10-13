package fr.podcast.shared.api

import fr.podcast.shared.i18n.Strings

/** What to tell the user about a failure, in their language. */
fun Throwable.userMessage(strings: Strings): String = when (this) {
    is ApiException.Unreachable -> strings.serverUnreachable
    is ApiException.NotAPodcastServer -> strings.notAPodcastServer
    is ApiException.Unauthorized -> strings.invalidCredentials
    is ApiException.Rejected -> when (type?.substringAfterLast('/')) {
        "not-a-feed", "invalid-feed-url" -> strings.notAFeed
        "feed-unreachable", "feed-too-large" -> strings.feedUnreachable
        "forbidden-feed-address" -> strings.forbiddenFeedAddress
        "not-an-opml" -> strings.notAnOpml
        "search-failed" -> strings.searchFailed
        else -> strings.loadingError
    }
    else -> strings.loadingError
}
