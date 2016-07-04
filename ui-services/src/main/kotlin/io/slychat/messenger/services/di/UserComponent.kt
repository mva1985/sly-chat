package io.slychat.messenger.services.di

import dagger.Subcomponent
import io.slychat.messenger.core.persistence.*
import io.slychat.messenger.core.persistence.sqlite.SQLitePersistenceManager
import io.slychat.messenger.services.*
import io.slychat.messenger.services.auth.AuthTokenManager
import io.slychat.messenger.services.config.UserConfigService
import io.slychat.messenger.services.crypto.MessageCipherService

/** Scoped to a user's login session. */
@UserScope
@Subcomponent(modules = arrayOf(UserModule::class, PersistenceUserModule::class))
interface UserComponent {
    val keyVaultPersistenceManager: KeyVaultPersistenceManager

    val sqlitePersistenceManager: SQLitePersistenceManager

    val contactsPersistenceManager: ContactsPersistenceManager

    val messagePersistenceManager: MessagePersistenceManager

    val sessionDataPersistenceManager : SessionDataPersistenceManager

    val accountInfoPersistenceManager: AccountInfoPersistenceManager

    val preKeyPersistenceManager: PreKeyPersistenceManager

    val contactsService: ContactsService

    val messengerService: MessengerService

    val messageCipherService: MessageCipherService

    val notifierService: NotifierService

    val userLoginData: UserData

    val userPaths: UserPaths

    val relayClientManager: RelayClientManager

    val preKeyManager: PreKeyManager

    val offlineMessageManager: OfflineMessageManager

    val authTokenManager: AuthTokenManager

    val configService: UserConfigService
}
