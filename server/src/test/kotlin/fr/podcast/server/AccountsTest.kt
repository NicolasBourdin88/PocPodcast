package fr.podcast.server

import com.password4j.Argon2Function
import com.password4j.Password
import com.password4j.types.Argon2
import fr.podcast.server.auth.Accounts
import kotlinx.coroutines.runBlocking
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class AccountsTest {

    @BeforeTest
    fun reset() = TestDatabase.reset()

    @Test
    fun passwordsHashedWithOtherArgon2ParametersStillWork() = runBlocking {
        val database = TestDatabase.database
        // As if the account had been created before a change of psw4j.properties.
        val olderHash = Password.hash("old password").addRandomSalt()
            .with(Argon2Function.getInstance(4096, 3, 1, 32, Argon2.ID, 19)).result
        database.query { usersQueries.insertUser("carol", "Carol", olderHash, "USER").executeAsOne() }

        val accounts = Accounts(database)
        assertEquals("carol", accounts.authenticate("carol", "old password")?.username)
        assertNull(accounts.authenticate("carol", "wrong"))
        assertNull(accounts.authenticate("nobody", "old password"))
    }
}
