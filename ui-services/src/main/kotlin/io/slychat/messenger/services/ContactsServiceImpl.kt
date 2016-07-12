package io.slychat.messenger.services

import io.slychat.messenger.core.UserId
import io.slychat.messenger.core.http.api.contacts.*
import io.slychat.messenger.core.persistence.AllowedMessageLevel
import io.slychat.messenger.core.persistence.ContactInfo
import io.slychat.messenger.core.persistence.ContactsPersistenceManager
import io.slychat.messenger.services.auth.AuthTokenManager
import nl.komponents.kovenant.Deferred
import nl.komponents.kovenant.Promise
import nl.komponents.kovenant.deferred
import nl.komponents.kovenant.functional.bind
import nl.komponents.kovenant.ui.successUi
import org.slf4j.LoggerFactory
import rx.Observable
import rx.subjects.PublishSubject
import java.util.*

class ContactsServiceImpl(
    private val authTokenManager: AuthTokenManager,
    private val contactClient: ContactAsyncClient,
    private val contactsPersistenceManager: ContactsPersistenceManager,
    private val contactJobRunner: ContactJobRunner
) : ContactsService {
    private val log = LoggerFactory.getLogger(javaClass)

    private val contactEventsSubject = PublishSubject.create<ContactEvent>()

    override val contactEvents: Observable<ContactEvent> = contactEventsSubject

    init {
        contactJobRunner.running.subscribe { onContactJobStatusUpdate(it) }
    }

    private fun <V, E> wrap(deferred: Deferred<V, E>, promise: Promise<V, E>): Promise<V, E> {
        return promise success { deferred.resolve(it) } fail { deferred.reject(it) }
    }

    override fun addContact(contactInfo: ContactInfo): Promise<Boolean, Exception> {
        val d = deferred<Boolean, Exception>()

        contactJobRunner.runOperation {
            wrap(d, contactsPersistenceManager.add(contactInfo)) successUi { wasAdded ->
                if (wasAdded) {
                    withCurrentJob { doUpdateRemoteContactList() }
                    contactEventsSubject.onNext(ContactEvent.Added(setOf(contactInfo)))
                }
            }
        }

        return d.promise
    }

    /** Remove the given contact from the contact list. */
    override fun removeContact(contactInfo: ContactInfo): Promise<Boolean, Exception> {
        val d = deferred<Boolean, Exception>()

        contactJobRunner.runOperation {
            wrap(d, contactsPersistenceManager.remove(contactInfo.id)) successUi { wasRemoved ->
                if (wasRemoved) {
                    withCurrentJob { doUpdateRemoteContactList() }
                    contactEventsSubject.onNext(ContactEvent.Removed(setOf(contactInfo)))
                }
            }
        }

        return d.promise
    }

    override fun updateContact(contactInfo: ContactInfo): Promise<Unit, Exception> {
        val d = deferred<Unit, Exception>()

        contactJobRunner.runOperation {
            wrap(d, contactsPersistenceManager.update(contactInfo)) successUi {
                contactEventsSubject.onNext(ContactEvent.Updated(setOf(contactInfo)))
            }
        }

        return d.promise
    }

    /** Filter out users whose messages we should ignore. */
    //in the future, this will also check for blocked/deleted users
    override fun allowMessagesFrom(users: Set<UserId>): Promise<Set<UserId>, Exception> {
        val d = deferred<Set<UserId>, Exception>()

        //avoid errors if the caller modifiers the set after giving it
        val usersCopy = HashSet(users)

        contactJobRunner.runOperation {
            wrap(d, contactsPersistenceManager.filterBlocked(usersCopy))
        }

        return d.promise
    }

    override fun doRemoteSync() {
        withCurrentJob { doRemoteSync() }
    }

    override fun doLocalSync() {
        withCurrentJob { doLocalSync() }
    }

    private fun onContactJobStatusUpdate(info: ContactJobInfo) {
        //if remote sync is at all enabled, we want the entire process to lock down the contact list
        if (info.remoteSync)
            contactEventsSubject.onNext(ContactEvent.Sync(info.isRunning))
    }

    /** Process the given unadded users. */
    private fun addNewContactData(users: Set<UserId>): Promise<Set<UserId>, Exception> {
        if (users.isEmpty())
            return Promise.ofSuccess(emptySet())

        val request = FetchContactInfoByIdRequest(users.toList())

        return authTokenManager.bind { userCredentials ->
            contactClient.fetchContactInfoById(userCredentials, request) bindUi { response ->
                handleContactLookupResponse(users, response)
            } fail { e ->
                //the only recoverable error would be a network error; when the network is restored, this'll get called again
                log.error("Unable to fetch contact info: {}", e.message, e)
            }
        }
    }

    private fun handleContactLookupResponse(users: Set<UserId>, response: FetchContactInfoByIdResponse): Promise<Set<UserId>, Exception> {
        val foundIds = response.contacts.mapTo(HashSet()) { it.id }

        val missing = HashSet(users)
        missing.removeAll(foundIds)

        val invalidContacts = HashSet<UserId>()

        //XXX blacklist? at least temporarily or something
        if (missing.isNotEmpty())
            invalidContacts.addAll(missing)

        val contacts = response.contacts.map { it.toCore(true, AllowedMessageLevel.GROUP_ONLY) }

        return contactsPersistenceManager.add(contacts) mapUi { newContacts ->
            if (newContacts.isNotEmpty()) {
                val ev = ContactEvent.Added(newContacts)
                contactEventsSubject.onNext(ev)
            }

            invalidContacts
        } fail { e ->
            log.error("Unable to add new contacts: {}", e.message, e)
        }
    }

    override fun addMissingContacts(users: Set<UserId>): Promise<Set<UserId>, Exception> {
        //defensive copy
        val missing = HashSet(users)

        val d = deferred<Set<UserId>, Exception>()

        contactJobRunner.runOperation {
            wrap(d, contactsPersistenceManager.exists(users) bind { exists ->
                missing.removeAll(exists)
                addNewContactData(missing)
            }) successUi {
                doRemoteSync()
            }
        }

        return d.promise
    }

    /** Used to mark job components for execution. */
    private fun withCurrentJob(body: ContactJobDescription.() -> Unit) {
        contactJobRunner.withCurrentJob(body)
    }

    override fun shutdown() {
    }

    //FIXME return an Either<String, ApiContactInfo>
    override fun fetchRemoteContactInfo(email: String?, queryPhoneNumber: String?): Promise<FetchContactResponse, Exception> {
        return authTokenManager.bind { userCredentials ->
            val request = NewContactRequest(email, queryPhoneNumber)

            contactClient.fetchNewContactInfo(userCredentials, request)
        }
    }
}