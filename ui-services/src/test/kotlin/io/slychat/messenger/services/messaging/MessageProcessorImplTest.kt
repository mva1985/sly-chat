package io.slychat.messenger.services.messaging

import com.nhaarman.mockito_kotlin.*
import io.slychat.messenger.core.*
import io.slychat.messenger.core.persistence.*
import io.slychat.messenger.core.persistence.sqlite.InvalidMessageLevelException
import io.slychat.messenger.services.GroupService
import io.slychat.messenger.services.contacts.ContactsService
import io.slychat.messenger.testutils.KovenantTestModeRule
import io.slychat.messenger.testutils.testSubscriber
import io.slychat.messenger.testutils.thenReturn
import io.slychat.messenger.testutils.thenReturnNull
import nl.komponents.kovenant.Promise
import org.assertj.core.api.Assertions.assertThat
import org.junit.ClassRule
import org.junit.Test
import java.util.*
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class MessageProcessorImplTest {
    companion object {
        @JvmField
        @ClassRule
        val kovenantTestMode = KovenantTestModeRule()
    }

    val contactsService: ContactsService = mock()
    val messagePersistenceManager: MessagePersistenceManager = mock()
    val groupService: GroupService = mock()

    fun createProcessor(): MessageProcessorImpl {
        whenever(messagePersistenceManager.addMessage(any(), any())).thenAnswer {
            @Suppress("UNCHECKED_CAST")
            val a = it.arguments[1] as MessageInfo
            Promise.ofSuccess<MessageInfo, Exception>(a)
        }

        whenever(contactsService.addMissingContacts(any())).thenReturn(emptySet())

        whenever(groupService.addMessage(any(), any())).thenAnswer {
            @Suppress("UNCHECKED_CAST")
            val a = it.arguments[1] as GroupMessageInfo
            Promise.ofSuccess<GroupMessageInfo, Exception>(a)
        }

        whenever(groupService.join(any(), any())).thenReturn(Unit)
        whenever(groupService.getInfo(any())).thenReturnNull()
        whenever(groupService.addMembers(any(), any())).thenReturn(Unit)
        whenever(groupService.removeMember(any(), any())).thenReturn(Unit)
        whenever(groupService.isUserMemberOf(any(), any())).thenReturn(true)

        return MessageProcessorImpl(
            contactsService,
            messagePersistenceManager,
            groupService
        )
    }

    @Test
    fun `it should store newly received text messages`() {
        val processor = createProcessor()

        val m = TextMessage(currentTimestamp(), "m", null)

        val wrapper = SlyMessageWrapper(randomUUID(), TextMessageWrapper(m))

        processor.processMessage(UserId(1), wrapper).get()

        verify(messagePersistenceManager).addMessage(eq(UserId(1)), any())
    }

    @Test
    fun `it should emit new message events after storing new text messages`() {
        val processor = createProcessor()

        val m = TextMessage(currentTimestamp(), "m", null)

        val wrapper = SlyMessageWrapper(randomUUID(), TextMessageWrapper(m))

        val testSubscriber = processor.newMessages.testSubscriber()

        val from = UserId(1)

        processor.processMessage(from, wrapper).get()

        val bundles = testSubscriber.onNextEvents

        assertThat(bundles)
            .hasSize(1)
            .`as`("Bundle check")

        val bundle = bundles[0]

        assertEquals(bundle.userId, from, "Invalid user id")
    }

    @Test
    fun `it should handle InvalidMessageLevelException by calling ContactsService and retrying afterwards`() {
        val processor = createProcessor()

        val m = randomTextMessage()

        val wrapper = SlyMessageWrapper(randomUUID(), TextMessageWrapper(m))

        val from = randomUserId()

        whenever(contactsService.allowAll(from)).thenReturn(Unit)
        whenever(messagePersistenceManager.addMessage(any(), any()))
            .thenReturn(InvalidMessageLevelException(from))
            .thenReturn(randomReceivedMessageInfo())

        processor.processMessage(from, wrapper).get()

        verify(contactsService).allowAll(from)
        verify(messagePersistenceManager, times(2)).addMessage(any(), any())
    }

    fun randomTextMessage(groupId: GroupId? = null): TextMessage =
        TextMessage(currentTimestamp(), randomUUID(), groupId)

    fun returnGroupInfo(groupInfo: GroupInfo?) {
        if (groupInfo != null)
            whenever(groupService.getInfo(groupInfo.id)).thenReturn(groupInfo)
        else
            whenever(groupService.getInfo(any())).thenReturnNull()
    }

    fun wrap(m: TextMessage): SlyMessageWrapper = SlyMessageWrapper(randomUUID(), TextMessageWrapper(m))
    fun wrap(m: GroupEventMessage): SlyMessageWrapper = SlyMessageWrapper(randomUUID(), GroupEventMessageWrapper(m))

    /* Group stuff */

    fun generateInvite(): GroupEventMessage.Invitation {
        val groupId = randomGroupId()
        val members = (1..3L).mapTo(HashSet()) { UserId(it) }

        return GroupEventMessage.Invitation(groupId, randomGroupName(), members)
    }

    @Test
    fun `it should add group info and fetch member contact info when receiving a new group invitation for an unknown group`() {
        val sender = randomUserId()

        val m = generateInvite()

        val fullMembers = HashSet(m.members)
        fullMembers.add(sender)

        val processor = createProcessor()

        processor.processMessage(sender, wrap(m)).get()

        val info = GroupInfo(m.id, m.name, GroupMembershipLevel.JOINED)

        verify(contactsService).addMissingContacts(m.members)

        verify(groupService).join(info, fullMembers)
    }

    @Test
    fun `it should ignore duplicate invitations`() {
        val sender = randomUserId()

        val m = generateInvite()

        val processor = createProcessor()

        val groupInfo = GroupInfo(m.id, m.name, GroupMembershipLevel.JOINED)

        whenever(groupService.getInfo(m.id)).thenReturn(groupInfo)

        processor.processMessage(sender, wrap(m)).get()

        verify(groupService, never()).join(any(), any())
    }

    @Test
    fun `it should filter out invalid user ids in group invitations`() {
        val sender = randomUserId()

        val m = generateInvite()

        val process = createProcessor()

        val groupInfo = GroupInfo(m.id, m.name, GroupMembershipLevel.JOINED)

        val invalidUser = m.members.first()
        val remaining = HashSet(m.members)
        remaining.add(sender)
        remaining.remove(invalidUser)

        whenever(groupService.getInfo(m.id)).thenReturnNull()

        whenever(contactsService.addMissingContacts(anySet())).thenReturn(setOf(invalidUser))

        process.processMessage(sender, wrap(m)).get()

        verify(groupService).join(groupInfo, remaining)
    }

    @Test
    fun `it should handle an empty members list invitation`() {
        val sender = randomUserId()

        val m = GroupEventMessage.Invitation(randomGroupId(), randomGroupName(), emptySet())

        val process = createProcessor()

        val groupInfo = GroupInfo(m.id, m.name, GroupMembershipLevel.JOINED)

        whenever(groupService.getInfo(m.id)).thenReturnNull()

        process.processMessage(sender, wrap(m)).get()

        verify(contactsService).addMissingContacts(emptySet())

        verify(groupService).join(groupInfo, setOf(sender))
    }

    @Test
    fun `it should ignore invitations for parted groups which have been blocked`() {
        val sender = randomUserId()

        val m = generateInvite()

        val processor = createProcessor()

        val groupInfo = GroupInfo(m.id, m.name, GroupMembershipLevel.BLOCKED)

        whenever(groupService.getInfo(m.id)).thenReturn(groupInfo)

        whenever(contactsService.addMissingContacts(anySet())).thenReturn(emptySet())

        processor.processMessage(sender, wrap(m)).get()

        verify(groupService, never()).join(any(), any())
    }

    @Test
    fun `it should not ignore invitations for parted groups which have not been blocked`() {
        val sender = randomUserId()

        val m = generateInvite()

        val fullMembers = HashSet(m.members)
        fullMembers.add(sender)

        val processor = createProcessor()

        val groupInfo = GroupInfo(m.id, m.name, GroupMembershipLevel.PARTED)

        whenever(groupService.getInfo(m.id)).thenReturn(groupInfo)

        processor.processMessage(sender, wrap(m)).get()

        val newGroupInfo = GroupInfo(m.id, m.name, GroupMembershipLevel.JOINED)

        verify(groupService).join(newGroupInfo, fullMembers)

        verify(contactsService).addMissingContacts(m.members)
    }

    @Test
    fun `it should ignore group joins for parted groups`() {
        val sender = UserId(1)
        val newMember = UserId(2)
        val groupInfo = randomGroupInfo(GroupMembershipLevel.PARTED)

        val m = GroupEventMessage.Join(groupInfo.id, newMember)

        val processor = createProcessor()

        whenever(groupService.getInfo(m.id)).thenReturn(groupInfo)

        processor.processMessage(sender, wrap(m)).get()

        verify(groupService, never()).addMembers(any(), any())
    }

    @Test
    fun `it should ignore group parts for parted groups`() {
        val sender = randomUserId()
        val groupInfo = randomGroupInfo(GroupMembershipLevel.PARTED)

        val m = GroupEventMessage.Part(groupInfo.id)

        val processor = createProcessor()

        whenever(groupService.getInfo(m.id)).thenReturn(groupInfo)

        processor.processMessage(sender, wrap(m)).get()

        verify(groupService, never()).removeMember(any(), any())
    }

    @Test
    fun `it should add a member when receiving a new member join from a member for a joined group`() {
        val sender = UserId(1)
        val newMember = UserId(2)
        val groupInfo = randomGroupInfo(GroupMembershipLevel.JOINED)

        val m = GroupEventMessage.Join(groupInfo.id, newMember)

        val processor = createProcessor()

        whenever(groupService.isUserMemberOf(groupInfo.id, sender)).thenReturn(true)

        whenever(groupService.getInfo(m.id)).thenReturn(groupInfo)

        processor.processMessage(sender, wrap(m)).get()

        verify(groupService).addMembers(groupInfo.id, setOf(newMember))
    }

    @Test
    fun `it should emit a Join event when receiving a group join`() {
        val sender = UserId(1)
        val newMember = UserId(2)
        val groupInfo = randomGroupInfo(GroupMembershipLevel.JOINED)

        val m = GroupEventMessage.Join(groupInfo.id, newMember)

        val processor = createProcessor()

        whenever(groupService.getInfo(m.id)).thenReturn(groupInfo)

        processor.processMessage(sender, wrap(m)).get()
    }

    @Test
    fun `it should remove a member when receiving a group part from that user for a joined group`() {
        val sender = UserId(1)
        val groupInfo = randomGroupInfo(GroupMembershipLevel.JOINED)

        val m = GroupEventMessage.Part(groupInfo.id)

        val processor = createProcessor()

        whenever(groupService.isUserMemberOf(groupInfo.id, sender)).thenReturn(true)

        whenever(groupService.getInfo(m.id)).thenReturn(groupInfo)

        processor.processMessage(sender, wrap(m)).get()

        verify(groupService).removeMember(groupInfo.id, sender)
    }

    @Test
    fun `it should ignore an add from a non-member user for a joined group`() {
        val sender = UserId(1)
        val newMember = UserId(2)

        val groupInfo = randomGroupInfo(GroupMembershipLevel.JOINED)

        val m = GroupEventMessage.Join(groupInfo.id, newMember)

        val processor = createProcessor()

        whenever(groupService.isUserMemberOf(groupInfo.id, sender)).thenReturn(false)

        whenever(groupService.getInfo(m.id)).thenReturn(groupInfo)

        processor.processMessage(sender, wrap(m)).get()

        verify(groupService, never()).addMembers(any(), any())
    }

    @Test
    fun `it should ignore a part from a non-member user for a joined group`() {
        val sender = randomUserId()
        val groupInfo = randomGroupInfo(GroupMembershipLevel.JOINED)

        val m = GroupEventMessage.Part(groupInfo.id)

        val processor = createProcessor()

        whenever(groupService.isUserMemberOf(groupInfo.id, sender)).thenReturn(false)

        whenever(groupService.getInfo(m.id)).thenReturn(groupInfo)

        processor.processMessage(sender, wrap(m)).get()

        verify(groupService, never()).removeMember(any(), any())
    }

    @Test
    fun `it should ignore group joins for blocked groups`() {
        val sender = UserId(1)
        val newMember = UserId(2)

        val groupId = randomGroupId()
        val groupInfo = randomGroupInfo(GroupMembershipLevel.BLOCKED)

        val m = GroupEventMessage.Join(groupId, newMember)

        val processor = createProcessor()

        whenever(groupService.getInfo(m.id)).thenReturn(groupInfo)

        processor.processMessage(sender, wrap(m)).get()

        verify(groupService, never()).addMembers(any(), any())
    }

    @Test
    fun `it should ignore group parts for blocked groups`() {
        val sender = randomUserId()
        val groupInfo = randomGroupInfo(GroupMembershipLevel.BLOCKED)

        val m = GroupEventMessage.Part(groupInfo.id)

        val processor = createProcessor()

        whenever(groupService.getInfo(m.id)).thenReturn(groupInfo)

        processor.processMessage(sender, wrap(m)).get()

        verify(groupService, never()).removeMember(any(), any())
    }

    @Test
    fun `it should fetch remote contact info when receiving a group join`() {
        val sender = UserId(1)
        val newMember = UserId(2)
        val groupInfo = randomGroupInfo(GroupMembershipLevel.JOINED)

        val m = GroupEventMessage.Join(groupInfo.id, newMember)

        val processor = createProcessor()

        returnGroupInfo(groupInfo)

        whenever(groupService.addMembers(groupInfo.id, setOf(newMember))).thenReturn(Unit)
        whenever(contactsService.addMissingContacts(any())).thenReturn(emptySet())

        processor.processMessage(sender, wrap(m)).get()

        verify(contactsService).addMissingContacts(setOf(newMember))
    }

    @Test
    fun `it should not add a member if the user id is invalid when receiving a group join`() {
        val sender = UserId(1)
        val newMember = UserId(2)
        val groupInfo = randomGroupInfo(GroupMembershipLevel.JOINED)

        val m = GroupEventMessage.Join(groupInfo.id, newMember)

        val processor = createProcessor()

        returnGroupInfo(groupInfo)

        whenever(contactsService.addMissingContacts(any())).thenReturn(setOf(newMember))

        processor.processMessage(sender, wrap(m)).get()

        verify(groupService, never()).addMembers(any(), any())
    }

    @Test
    fun `it should store received group text messages to the proper group`() {
        val sender = randomUserId()

        val groupInfo = randomGroupInfo(GroupMembershipLevel.JOINED)
        val m = randomTextMessage(groupInfo.id)

        val processor = createProcessor()

        returnGroupInfo(groupInfo)

        processor.processMessage(sender, wrap(m)).get()

        verify(groupService).addMessage(eq(groupInfo.id), capture { groupMessageInfo ->
            val messageInfo = groupMessageInfo.info
            assertFalse(messageInfo.isSent, "Message marked as sent")
            assertEquals(m.message, messageInfo.message, "Invalid message")
        })
    }

    @Test
    fun `it should emit a new message event when receiving a new group text message`() {
        val sender = randomUserId()

        val groupInfo = randomGroupInfo(GroupMembershipLevel.JOINED)
        val m = randomTextMessage(groupInfo.id)

        val processor = createProcessor()

        returnGroupInfo(groupInfo)

        val testSubscriber = processor.newMessages.testSubscriber()

        val wrapper = wrap(m)
        processor.processMessage(sender, wrapper).get()

        val newMessages = testSubscriber.onNextEvents
        assertEquals(1, newMessages.size, "Invalid number of new message events")

        val bundle = newMessages[0]
        assertEquals(1, bundle.messages.size, "Invalid number of messages in bundle")
        assertEquals(groupInfo.id, bundle.groupId, "Invalid group id")

        val message = bundle.messages[0]
        assertEquals(m.message, message.message, "Invalid message")
        assertEquals(wrapper.messageId, message.id, "Invalid message id")
    }

    fun testDropGroupTextMessage(senderIsMember: Boolean, membershipLevel: GroupMembershipLevel) {
        val sender = randomUserId()

        val groupInfo = randomGroupInfo(membershipLevel)
        val m = randomTextMessage(groupInfo.id)

        val processor = createProcessor()

        returnGroupInfo(groupInfo)

        whenever(groupService.isUserMemberOf(groupInfo.id, sender)).thenReturn(senderIsMember)

        processor.processMessage(sender, wrap(m)).get()

        verify(groupService, never()).addMessage(any(), any())
    }

    @Test
    fun `it should ignore group text messages from non-members for joined groups`() {
        testDropGroupTextMessage(false, GroupMembershipLevel.JOINED)
    }

    @Test
    fun `it should ignore group text messages for parted groups`() {
        testDropGroupTextMessage(true, GroupMembershipLevel.PARTED)
    }

    @Test
    fun `it should ignore group text messages for blocked groups`() {
        testDropGroupTextMessage(true, GroupMembershipLevel.BLOCKED)
    }
}