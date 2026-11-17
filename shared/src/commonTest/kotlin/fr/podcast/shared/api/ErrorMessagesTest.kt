package fr.podcast.shared.api

import fr.podcast.shared.i18n.EnglishStrings
import kotlin.test.Test
import kotlin.test.assertEquals

class ErrorMessagesTest {

    private val strings = EnglishStrings

    @Test
    fun refusalsAreExplainedByTypeThenStatusThenField() {
        assertEquals(strings.notAFeed, ApiException.Rejected(422, "/problems/not-a-feed", null).userMessage(strings))
        assertEquals(strings.notAllowed, ApiException.Rejected(403, "about:blank", "Read-only").userMessage(strings))
        assertEquals(strings.notAllowed, ApiException.NotOffered("share").userMessage(strings))

        val wrongPassword = ApiException.Rejected(422, "about:blank", null, listOf(FieldError("currentPassword", "wrong password")))
        assertEquals(strings.wrongPassword, wrongPassword.userMessage(strings))
        val shortPassword = ApiException.Rejected(422, "about:blank", null, listOf(FieldError("newPassword", "at least 8 characters")))
        assertEquals(strings.passwordTooShort(8), shortPassword.userMessage(strings))
    }

    @Test
    fun theWaitIsRoundedUpToTheMinute() {
        assertEquals(strings.tooManyAttempts(1), ApiException.TooManyAttempts(1).userMessage(strings))
        assertEquals(strings.tooManyAttempts(2), ApiException.TooManyAttempts(61).userMessage(strings))
        assertEquals(strings.tooManyAttempts(15), ApiException.TooManyAttempts(899).userMessage(strings))
    }
}
