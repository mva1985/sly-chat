@file:JvmName("CryptoUtils")
package io.slychat.messenger.core.crypto

import io.slychat.messenger.core.UserId
import io.slychat.messenger.core.crypto.ciphers.Key
import io.slychat.messenger.core.crypto.hashes.HashData
import io.slychat.messenger.core.crypto.hashes.HashParams
import io.slychat.messenger.core.crypto.hashes.HashType
import io.slychat.messenger.core.crypto.hashes.hashPasswordWithParams
import io.slychat.messenger.core.hexify
import io.slychat.messenger.core.persistence.MessageSendFailure
import org.whispersystems.libsignal.IdentityKey
import org.whispersystems.libsignal.IdentityKeyPair
import org.whispersystems.libsignal.util.KeyHelper
import java.security.SecureRandom

private const val MASTER_KEY_SIZE_BITS = 256
private const val LOCAL_MASTER_KEY_SIZE_BITS = 256
private const val ANONYMIZING_DATA_SIZE_BITS = 128

private fun defaultScryptParams(): HashParams.SCrypt2 {
    val keyLengthBits = 256

    val salt = getRandomBits(256)

    //default recommendations from: https://www.tarsnap.com/scrypt/scrypt-slides.pdf
    //due to the performance on android we currently can't bump these up, as it already takes 3s/call on a nexus 5
    val N = 16384
    val r = 8
    val p = 1

    return HashParams.SCrypt2(
        salt,
        N,
        r,
        p,
        keyLengthBits
    )
}

/** Default parameters for hashing a password into a key for decrypting the encrypted key pair. */
fun defaultKeyPasswordHashParams(): HashParams = defaultScryptParams()

fun defaultRemotePasswordHashParams(): HashParams = defaultScryptParams()

fun generateKeyPair(): IdentityKeyPair = KeyHelper.generateIdentityKeyPair()

/**
 * Generates a hash for using the password as a symmetric encryption key.
 */
fun hashPasswordForLocalWithDefaults(password: String): HashData {
    val params = defaultKeyPasswordHashParams()
    return HashData(hashPasswordWithParams(password, params, HashType.LOCAL), params)
}

/** Used to generate a password hash for a new password during registration. Uses the current default algorithm. */
fun hashPasswordForRemoteWithDefaults(password: String): HashData {
    val params = defaultRemotePasswordHashParams()
    return HashData(hashPasswordWithParams(password, params, HashType.REMOTE), params)
}

/** Return a randomly generated ByteArray of the given bit size. */
fun getRandomBits(bits: Int): ByteArray {
    require(bits >= 8) { "bits must be > 8" }
    require((bits % 8) == 0) { "bits must be a multiple of 8" }

    val iv = ByteArray(bits/8)
    SecureRandom().nextBytes(iv)
    return iv
}

/** Generate new anonymizing data. */
fun generateAnonymizingData(): ByteArray {
    return getRandomBits(ANONYMIZING_DATA_SIZE_BITS)
}

/** Generate a new master key. */
fun generateMasterKey(): Key {
    return generateKey(MASTER_KEY_SIZE_BITS)
}

fun generateLocalMasterKey(): Key {
    return generateKey(LOCAL_MASTER_KEY_SIZE_BITS)
}

/** Generate a key of the given key size (in bits). */
fun generateKey(keySizeBits: Int): Key {
    return Key(getRandomBits(keySizeBits))
}

/** Generates a new key vault for a new user. */
fun generateNewKeyVault(password: String): KeyVault {
    val identityKeyPair = generateKeyPair()
    val masterKey = generateMasterKey()
    val anonymizingData = generateAnonymizingData()
    val keyPasswordHashInfo = hashPasswordForLocalWithDefaults(password)

    return KeyVault(
        identityKeyPair,
        masterKey,
        anonymizingData,
        keyPasswordHashInfo.params,
        Key(keyPasswordHashInfo.hash)
    )
}

/** Returns a textual fingerprint of the given identity key. */
fun identityKeyFingerprint(identityKey: IdentityKey): String =
    identityKey.publicKey.serialize().hexify()

/** Returns a random UUID as a string, without dashes. */
fun randomUUID(): String {
    val bytes = ByteArray(16)
    SecureRandom().nextBytes(bytes)
    return bytes.hexify()
}

private val uuidRegex = "[0-9a-f]{32}".toRegex()
fun isValidUUIDFormat(s: String): Boolean {
    return uuidRegex.matches(s)
}

fun randomRegistrationId(): Int = KeyHelper.generateRegistrationId(false)

fun randomMessageId(): String = randomUUID()

fun randomMessageSendFailures(userId: UserId): Map<UserId, MessageSendFailure> = mapOf(
    userId to MessageSendFailure.InactiveUser()
)
