package io.slychat.messenger.services

import io.slychat.messenger.core.UserId
import io.slychat.messenger.core.currentTimestamp
import io.slychat.messenger.core.persistence.*
import nl.komponents.kovenant.Promise
import nl.komponents.kovenant.functional.bind
import org.slf4j.LoggerFactory
import rx.Observable
import rx.subjects.PublishSubject
import java.util.*

class MessageProcessorServiceImpl(
    private val contactsService: ContactsService,
    private val messagePersistenceManager: MessagePersistenceManager,
    private val groupPersistenceManager: GroupPersistenceManager
) : MessageProcessorService {
    private val log = LoggerFactory.getLogger(javaClass)

    private val newMessagesSubject = PublishSubject.create<MessageBundle>()
    override val newMessages: Observable<MessageBundle> = newMessagesSubject

    override fun processMessage(sender: UserId, wrapper: SlyMessageWrapper): Promise<Unit, Exception> {
        val m = wrapper.message
        val messageId = wrapper.messageId

        return when (m) {
            is TextMessageWrapper -> handleTextMessage(sender, messageId, m.m)

            is GroupEventWrapper -> handleGroupMessage(sender, messageId, m.m)

            else -> {
                log.error("Unhandled message type: {}", m.javaClass.name)
                throw IllegalArgumentException("Unhandled message type: ${m.javaClass.name}")
            }
        }
    }

    private fun handleTextMessage(sender: UserId, messageId: String, m: TextMessage): Promise<Unit, Exception> {
        val messageInfo = MessageInfo.newReceived(messageId, m.message, m.timestamp, currentTimestamp(), 0)

        val groupId = m.groupId
        return if (groupId == null) {
            messagePersistenceManager.addMessage(sender, messageInfo) mapUi { messageInfo ->
                val bundle = MessageBundle(sender, listOf(messageInfo))
                newMessagesSubject.onNext(bundle)
            }
        }
        else {
            groupPersistenceManager.getGroupInfo(groupId) bind { groupInfo ->
                runIfJoinedAndUserIsMember(groupInfo, sender) { addGroupMessage(groupId, sender, messageInfo) }
            }
        }
    }

    private fun addGroupMessage(groupId: GroupId, sender: UserId, messageInfo: MessageInfo): Promise<Unit, Exception> {
        return groupPersistenceManager.addMessage(groupId, sender, messageInfo) mapUi { messageInfo ->
            newMessagesSubject.onNext(MessageBundle(sender, listOf(messageInfo)))
        }
    }

    private fun handleGroupMessage(sender: UserId, messageId: String, m: GroupEvent): Promise<Unit, Exception> {
        return groupPersistenceManager.getGroupInfo(m.id) bind { groupInfo ->
            when (m) {
                is GroupInvitation -> handleGroupInvitation(groupInfo, m)
                is GroupJoin -> runIfJoinedAndUserIsMember(groupInfo, sender) { handleGroupJoin(m)  }
                is GroupPart -> runIfJoinedAndUserIsMember(groupInfo, sender) { handleGroupPart(m.id, sender) }
                else -> throw IllegalArgumentException("Invalid GroupEvent: ${m.javaClass.name}")
            }
        }
    }

    /** Only runs the given action if we're joined to the group and the sender is a member of said group. */
    private fun runIfJoinedAndUserIsMember(groupInfo: GroupInfo?, sender: UserId, action: () -> Promise<Unit, Exception>): Promise<Unit, Exception> {
        return if (groupInfo == null || groupInfo.membershipLevel != GroupMembershipLevel.JOINED)
            return Promise.ofSuccess(Unit)
        else
            checkGroupMembership(sender, groupInfo.id, action)

    }

    /** Only runs the given action if the user is a member of the given group. Otherwise, logs a warning. */
    private fun checkGroupMembership(sender: UserId, id: GroupId, action: () -> Promise<Unit, Exception>): Promise<Unit, Exception> {
        return groupPersistenceManager.isUserMemberOf(sender, id) bind { isMember ->
            if (isMember)
                action()
            else {
                log.warn("Received a group message for group <{}> from non-member <{}>, ignoring", id.string, sender.long)
                Promise.ofSuccess(Unit)
            }
        }
    }

    private fun handleGroupJoin(m: GroupJoin): Promise<Unit, Exception> {
        return groupPersistenceManager.addMember(m.id, m.joined) mapUi { wasAdded ->
            if (wasAdded)
                log.info("User {} joined group {}", m.joined, m.id.string)

            Unit
        }
    }

    private fun handleGroupPart(groupId: GroupId, sender: UserId): Promise<Unit, Exception> {
        return groupPersistenceManager.removeMember(groupId, sender) mapUi { wasRemoved ->
            if (wasRemoved)
                log.info("User {} has left group {}", sender, groupId.string)
        }
    }

    private fun handleGroupInvitation(groupInfo: GroupInfo?, m: GroupInvitation): Promise<Unit, Exception> {
        val members = HashSet(m.members)

        //XXX should we bother checking if the sender is a member of the group as well? seems pointless

        return if (groupInfo == null || groupInfo.membershipLevel == GroupMembershipLevel.PARTED) {
            contactsService.addMissingContacts(m.members) bind { invalidIds ->
                members.removeAll(invalidIds)
                val info = GroupInfo(m.id, m.name, true, GroupMembershipLevel.JOINED)
                groupPersistenceManager.joinGroup(info, members)
            }
        }
        else
            Promise.ofSuccess(Unit)
    }
}