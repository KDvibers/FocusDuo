package dev.focusduo.data

import dev.focusduo.domain.AuthValidation
import dev.focusduo.domain.RepositoryException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

/** Explicit demo accounts, repositories and salted password hashes live only in this process. */
class DemoAccounts(
    private val repositoryFactory: (username: String, displayName: String) -> FakeRepository = { username, displayName ->
        FakeRepository(initialDisplayName = displayName, initialUsername = username)
    },
) {
    private class Account(val salt: ByteArray, val passwordHash: ByteArray, val repository: FakeRepository)

    private val mutex = Mutex()
    private val accounts = mutableMapOf<String, Account>()
    private val random = SecureRandom()

    suspend fun authenticate(username: String, password: String): FakeRepository {
        val credentials = AuthValidation.login(username, password)
        return withContext(Dispatchers.Default) {
            mutex.withLock {
                ensureDemoAccount()
                val account = accounts[credentials.username]
                // The same derivation is performed for missing accounts to avoid a fast username probe.
                val salt = account?.salt ?: ByteArray(SALT_BYTES).also(random::nextBytes)
                val candidate = hash(credentials.password, salt)
                val matches = account != null && MessageDigest.isEqual(candidate, account.passwordHash)
                candidate.fill(0)
                if (!matches) throw RepositoryException("UNAUTHORIZED", "Неверный логин или пароль.")
                account!!.repository
            }
        }
    }

    suspend fun register(username: String, displayName: String, password: String): FakeRepository {
        val credentials = AuthValidation.registration(username, displayName, password)
        return withContext(Dispatchers.Default) {
            mutex.withLock {
                ensureDemoAccount()
                if (accounts.containsKey(credentials.username)) {
                    throw RepositoryException("USERNAME_TAKEN", "Этот логин уже занят.", mapOf("username" to "Этот логин уже занят."))
                }
                createAccount(credentials.username, credentials.displayName, credentials.password).repository
            }
        }
    }

    private fun ensureDemoAccount() {
        if (!accounts.containsKey(DEMO_USERNAME)) createAccount(DEMO_USERNAME, DEMO_DISPLAY_NAME, DEMO_PASSWORD)
    }

    private fun createAccount(username: String, displayName: String, password: String): Account {
        val salt = ByteArray(SALT_BYTES).also(random::nextBytes)
        val account = Account(salt, hash(password, salt), repositoryFactory(username, displayName))
        accounts[username] = account
        return account
    }

    private fun hash(password: String, salt: ByteArray): ByteArray {
        val chars = password.toCharArray()
        val spec = PBEKeySpec(chars, salt, HASH_ITERATIONS, HASH_BITS)
        return try {
            SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
        } finally {
            chars.fill('\u0000')
            spec.clearPassword()
        }
    }

    companion object {
        const val DEMO_USERNAME = "demo_user"
        const val DEMO_PASSWORD = "FocusDuoDemo1!"
        const val DEMO_DISPLAY_NAME = "Ты"
        private const val SALT_BYTES = 16
        private const val HASH_ITERATIONS = 120_000
        private const val HASH_BITS = 256
    }
}
