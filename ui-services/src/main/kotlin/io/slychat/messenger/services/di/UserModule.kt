package io.slychat.messenger.services.di

import dagger.Module
import dagger.Provides
import io.slychat.messenger.core.BuildConfig
import io.slychat.messenger.core.BuildConfig.ServerUrls
import io.slychat.messenger.core.persistence.AccountInfoPersistenceManager
import io.slychat.messenger.core.persistence.ContactsPersistenceManager
import io.slychat.messenger.core.persistence.MessagePersistenceManager
import io.slychat.messenger.core.persistence.PreKeyPersistenceManager
import io.slychat.messenger.core.relay.base.RelayConnector
import io.slychat.messenger.services.*
import io.slychat.messenger.services.auth.AuthTokenManager
import io.slychat.messenger.services.auth.AuthenticationServiceTokenProvider
import io.slychat.messenger.services.auth.TokenProvider
import io.slychat.messenger.services.crypto.MessageCipherService
import io.slychat.messenger.services.ui.UIEventService
import org.whispersystems.libsignal.state.SignalProtocolStore
import rx.Scheduler

@Module
class UserModule(
    @get:UserScope
    @get:Provides
    val providesUserLoginData: UserData
) {
    @UserScope
    @Provides
    fun provideRelayClientFactory(
        scheduler: Scheduler,
        relayConnector: RelayConnector,
        serverUrls: ServerUrls
    ): RelayClientFactory=
        RelayClientFactory(scheduler, relayConnector, serverUrls)

    @UserScope
    @Provides
    fun providesRelayClientManager(
        scheduler: Scheduler,
        relayClientFactory: RelayClientFactory
    ): RelayClientManager =
        RelayClientManager(scheduler, relayClientFactory)

    @UserScope
    @Provides
    fun providesContactsService(
        authTokenManager: AuthTokenManager,
        serverUrls: BuildConfig.ServerUrls,
        application: SlyApplication,
        contactsPersistenceManager: ContactsPersistenceManager
    ): ContactsService =
        ContactsService(authTokenManager, serverUrls, application, contactsPersistenceManager)

    @UserScope
    @Provides
    fun providesMessengerService(
        application: SlyApplication,
        scheduler: Scheduler,
        contactsService: ContactsService,
        messagePersistenceManager: MessagePersistenceManager,
        contactsPersistenceManager: ContactsPersistenceManager,
        relayClientManager: RelayClientManager,
        messageCipherService: MessageCipherService,
        userLoginData: UserData
    ): MessengerService =
        MessengerService(
            application,
            scheduler,
            contactsService,
            messagePersistenceManager,
            contactsPersistenceManager,
            relayClientManager,
            messageCipherService,
            userLoginData
        )

    @UserScope
    @Provides
    fun providersUserPaths(
        userLoginData: UserData,
        userPathsGenerator: UserPathsGenerator
    ): UserPaths =
        userPathsGenerator.getPaths(userLoginData.userId)

    @UserScope
    @Provides
    fun providesNotifierService(
        messengerService: MessengerService,
        uiEventService: UIEventService,
        contactsPersistenceManager: ContactsPersistenceManager,
        platformNotificationService: PlatformNotificationService
    ): NotifierService =
        NotifierService(messengerService, uiEventService, contactsPersistenceManager, platformNotificationService)

    @UserScope
    @Provides
    fun providesMessageCipherService(
        authTokenManager: AuthTokenManager,
        serverUrls: ServerUrls,
        signalProtocolStore: SignalProtocolStore
    ): MessageCipherService =
        MessageCipherService(authTokenManager, signalProtocolStore, serverUrls)

    @UserScope
    @Provides
    fun providesPreKeyManager(
        application: SlyApplication,
        serverUrls: ServerUrls,
        userLoginData: UserData,
        preKeyPersistenceManager: PreKeyPersistenceManager,
        authTokenManager: AuthTokenManager
    ): PreKeyManager =
        PreKeyManager(application, serverUrls.API_SERVER, userLoginData, preKeyPersistenceManager, authTokenManager)

    @UserScope
    @Provides
    fun providesOfflineMessageManager(
        application: SlyApplication,
        serverUrls: ServerUrls,
        messengerService: MessengerService,
        authTokenManager: AuthTokenManager
    ): OfflineMessageManager =
        OfflineMessageManager(application, serverUrls.API_SERVER, messengerService, authTokenManager)

    @UserScope
    @Provides
    fun providesContactSyncManager(
        application: SlyApplication,
        userLoginData: UserData,
        accountInfoPersistenceManager: AccountInfoPersistenceManager,
        serverUrls: ServerUrls,
        platformContacts: PlatformContacts,
        contactsPersistenceManager: ContactsPersistenceManager,
        authTokenManager: AuthTokenManager
    ): ContactSyncManager =
        ContactSyncManager(
            application,
            userLoginData,
            accountInfoPersistenceManager,
            serverUrls.API_SERVER,
            platformContacts,
            contactsPersistenceManager,
            authTokenManager
        )

    @UserScope
    @Provides
    fun providesTokenProvider(
        application: SlyApplication,
        userLoginData: UserData,
        authenticationService: AuthenticationService
    ): TokenProvider =
        AuthenticationServiceTokenProvider(
            application,
            userLoginData,
            authenticationService
        )

    @UserScope
    @Provides
    fun providesAuthTokenManager(
        userLoginData: UserData,
        tokenProvider: TokenProvider
    ): AuthTokenManager =
        AuthTokenManager(userLoginData.address, tokenProvider)
}