package io.slychat.messenger.services

import io.slychat.messenger.core.crypto.*
import io.slychat.messenger.core.http.HttpClientFactory
import io.slychat.messenger.core.http.api.authentication.AuthenticationClient
import io.slychat.messenger.core.http.api.authentication.AuthenticationRequest
import nl.komponents.kovenant.Promise
import nl.komponents.kovenant.task
import org.slf4j.LoggerFactory
import java.io.FileNotFoundException

/** API for various remote authentication functionality. */
class AuthenticationService(
    private val serverUrl: String,
    private val httpClientFactory: HttpClientFactory,
    private val localAccountDirectory: LocalAccountDirectory
) {
    companion object {
        private sealed class LocalAuthOutcome {
            class Successful(val result: AuthResult) : LocalAuthOutcome()
            class NoLocalData : LocalAuthOutcome()
            class Failure(val deviceId: Int) : LocalAuthOutcome()
        }
    }

    private val log = LoggerFactory.getLogger(javaClass)

    private fun remoteAuth(emailOrPhoneNumber: String, password: String, registrationId: Int, deviceId: Int): AuthResult {
        val loginClient = AuthenticationClient(serverUrl, httpClientFactory.create())

        val paramsResponse = loginClient.getParams(emailOrPhoneNumber)

        if (paramsResponse.errorMessage != null)
            throw AuthApiResponseException(paramsResponse.errorMessage)

        val authParams = paramsResponse.params!!

        val hashParams = HashDeserializers.deserialize(authParams.hashParams)
        val hash = hashPasswordWithParams(password, hashParams)

        val request = AuthenticationRequest(emailOrPhoneNumber, hash.hexify(), authParams.csrfToken, registrationId, deviceId)

        val response = loginClient.auth(request)
        if (response.errorMessage != null)
            throw AuthApiResponseException(response.errorMessage)

        val data = response.data!!
        val keyVault = KeyVault.deserialize(data.keyVault, password)
        return AuthResult(data.authToken, keyVault, data.accountInfo)
    }

    private fun localAuth(emailOrPhoneNumber: String, password: String): LocalAuthOutcome {
        val accountInfo = localAccountDirectory.findAccountFor(emailOrPhoneNumber) ?: return LocalAuthOutcome.NoLocalData()

        //if this doesn't exist it'll throw and we'll just try remote auth
        val keyVaultPersistenceManager = localAccountDirectory.getKeyVaultManager(accountInfo.id)

        val keyVault = try {
            keyVaultPersistenceManager.retrieveSync(password)
        }
        catch (e: KeyVaultDecryptionFailedException) {
           return LocalAuthOutcome.Failure(accountInfo.deviceId)
        }
        catch (e: FileNotFoundException) {
            return LocalAuthOutcome.NoLocalData()
        }

        //this isn't important; just use a null token in the auth result if this isn't present, and then fetch one remotely by refreshing
        val authToken = try {
            val sessionData = localAccountDirectory.getSessionDataManager(accountInfo.id, keyVault.localDataEncryptionKey, keyVault.localDataEncryptionParams).retrieveSync()
            sessionData.authToken
        }
        catch (e: FileNotFoundException) {
            null
        }

        return LocalAuthOutcome.Successful(AuthResult(authToken, keyVault, accountInfo))
    }

    private fun authSync(emailOrPhoneNumber: String, password: String, registrationId: Int): AuthResult {
        val localAuthResult = localAuth(emailOrPhoneNumber, password)
        return when (localAuthResult) {
            is LocalAuthOutcome.NoLocalData -> {
                log.debug("No local data found")
                remoteAuth(emailOrPhoneNumber, password, registrationId, 0)
            }

            is LocalAuthOutcome.Successful -> {
                log.debug("Local auth successful")
                localAuthResult.result
            }

            //can occur if user changed their account password but no longer remember the old password
            is LocalAuthOutcome.Failure -> {
                log.debug("Local auth failure")
                remoteAuth(emailOrPhoneNumber, password, registrationId, localAuthResult.deviceId)
            }
        }
    }

    /** Attempts to authentication using a local session first, then falls back to remote authentication. */
    fun auth(emailOrPhoneNumber: String, password: String, registrationId: Int): Promise<AuthResult, Exception> = task {
        authSync(emailOrPhoneNumber, password, registrationId)
    }
}