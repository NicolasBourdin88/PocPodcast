package fr.podcast.shared.api

import fr.podcast.shared.i18n.Strings
import fr.podcast.shared.model.AccountRules
import fr.podcast.shared.model.ShareRules

/** What to tell the user about a failure, in their language. */
fun Throwable.userMessage(strings: Strings): String = when (this) {
    is ApiException.Unreachable -> strings.serverUnreachable
    is ApiException.NotAPodcastServer -> strings.notAPodcastServer
    is ApiException.Unauthorized -> strings.invalidCredentials
    is ApiException.TooManyAttempts -> strings.tooManyAttempts(((retryAfterSeconds + 59) / 60).toInt().coerceAtLeast(1))
    is ApiException.NotOffered -> strings.notAllowed
    is ApiException.Rejected -> when (type?.substringAfterLast('/')) {
        "not-a-feed", "invalid-feed-url" -> strings.notAFeed
        "feed-unreachable", "feed-too-large" -> strings.feedUnreachable
        "forbidden-feed-address" -> strings.forbiddenFeedAddress
        "not-an-opml" -> strings.notAnOpml
        "search-failed" -> strings.searchFailed
        else -> when {
            status == 403 -> strings.notAllowed
            errors.isNotEmpty() -> errors.first().userMessage(strings)
            else -> strings.loadingError
        }
    }
    else -> strings.loadingError
}

/** What to tell about a refused field; the fields are those of docs/api/conventions.md. */
fun FieldError.userMessage(strings: Strings): String = when (field) {
    "currentPassword" -> strings.wrongPassword
    "password", "newPassword" -> strings.passwordTooShort(AccountRules.MIN_PASSWORD_LENGTH)
    "username" -> strings.usernameUnavailable
    "displayName" -> strings.displayNameInvalid(AccountRules.MAX_DISPLAY_NAME_LENGTH)
    "role" -> strings.lastAdministrator
    "userId" -> strings.noSuchUser
    "message" -> strings.messageTooLong(ShareRules.MAX_MESSAGE_LENGTH)
    "feedUrl" -> strings.notAFeed
    else -> strings.loadingError
}
