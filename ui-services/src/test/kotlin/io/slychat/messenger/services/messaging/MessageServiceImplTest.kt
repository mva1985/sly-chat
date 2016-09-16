package io.slychat.messenger.services.messaging

import com.nhaarman.mockito_kotlin.*
import io.slychat.messenger.core.*
import io.slychat.messenger.core.crypto.randomMessageId
import io.slychat.messenger.core.persistence.*
import io.slychat.messenger.services.MessageUpdateEvent
import io.slychat.messenger.services.assertEventEmitted
import io.slychat.messenger.services.assertNoEventsEmitted
import io.slychat.messenger.services.subclassFilterTestSubscriber
import io.slychat.messenger.testutils.*
import org.assertj.core.api.Assertions.assertThat
import org.junit.Before
import org.junit.ClassRule
import org.junit.Ignore
import org.junit.Test
import rx.observers.TestSubscriber
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class MessageServiceImplTest {
    companion object {
        @JvmField
        @ClassRule
        val kovenantTestMode = KovenantTestModeRule()
    }

    val messagePersistenceManager: MessagePersistenceManager = mock()

    val messageService = MessageServiceImpl(messagePersistenceManager)

    @Before
    fun before() {
        whenever(messagePersistenceManager.addMessage(any(), any())).thenResolveUnit()
        whenever(messagePersistenceManager.markMessageAsDelivered(any(), any(), any())).thenResolve(null)
        whenever(messagePersistenceManager.markConversationAsRead(any())).thenResolveUnit()
        whenever(messagePersistenceManager.setExpiration(any(), any(), any())).thenResolve(true)
        whenever(messagePersistenceManager.expireMessages(any())).thenResolveUnit()
        whenever(messagePersistenceManager.deleteAllMessages(any())).thenResolveUnit()
        whenever(messagePersistenceManager.deleteMessages(any(), any())).thenResolveUnit()

        val conversationDisplayInfo = ConversationDisplayInfo(
            randomGroupConversationId(),
            randomGroupName(),
            0,
            LastMessageData("contact", randomMessageText(), currentTimestamp())
        )
        whenever(messagePersistenceManager.getConversationDisplayInfo(any())).thenResolve(conversationDisplayInfo)
    }

    fun forEachConvType(body: (ConversationId) -> Unit) {
        body(randomUserConversationId())
        body(randomGroupConversationId())
    }

    inline fun <reified T : MessageUpdateEvent> messageUpdateEventCollectorFor(): TestSubscriber<T> {
        return messageService.messageUpdates.subclassFilterTestSubscriber()
    }

    fun testDelivery(isAlreadyDelivered: Boolean) {
        forEachConvType { conversationId ->
            val conversationInfo = randomSentConversationMessageInfo()
            val timestamp = currentTimestamp()

            val messageId = conversationInfo.info.id

            val stubbing = whenever(messagePersistenceManager.markMessageAsDelivered(conversationId, messageId, timestamp))

            if (!isAlreadyDelivered)
                stubbing.thenResolve(conversationInfo)
            else
                stubbing.thenResolve(null)

            val testSubscriber = messageUpdateEventCollectorFor<MessageUpdateEvent.Delivered>()

            val got = messageService.markMessageAsDelivered(conversationId, messageId, timestamp).get()

            if (!isAlreadyDelivered) {
                assertEventEmitted(testSubscriber) {
                    assertEquals(conversationId, it.conversationId, "Invalid conversation id")
                    assertEquals(messageId, it.messageId, "Invalid message id")
                }

                assertNotNull(got, "Message info should be returned")
            }
            else {
                assertNoEventsEmitted(testSubscriber)
                assertNull(got, "Message info should not be returned")
            }
        }
    }

    @Test
    fun `it should emit an update event when markMessageAsDelivered is called for an undelivered message`() {
        testDelivery(false)
    }

    @Test
    fun `it should not emit an update event when markMessageAsDelivered is called for an already delivered message`() {
        testDelivery(true)
    }

    fun testAddMessage(isDuplicate: Boolean) {
        forEachConvType { conversationId ->
            val conversationMessageInfo = randomSentConversationMessageInfo()
            listOf(conversationMessageInfo, randomReceivedConversationMessageInfo(randomUserId())).forEach { conversationMessageInfo ->
                val expected = ConversationMessage(conversationId, conversationMessageInfo)

                val testSubscriber = messageService.newMessages.testSubscriber()

                messageService.addMessage(conversationId, conversationMessageInfo).get()

                assertThat(testSubscriber.onNextEvents).apply {
                    `as`("Should emit an event")
                    containsOnly(expected)
                }
            }
        }
    }

    @Test
    fun `it should emit a new message event when addMessage is called with a new message`() {
        testAddMessage(false)
    }

    @Ignore("TODO")
    @Test
    fun `it should not emit a new message event when addMessage is called with a duplicate message`() { TODO() }

    @Test
    fun `it should mark the conversation as read when markConversationAsRead is called`() {
        forEachConvType {
            messageService.markConversationAsRead(it).get()

            verify(messagePersistenceManager).markConversationAsRead(it)
        }
    }

    fun testConversationInfoUpdate(body: (ConversationId) -> Unit) {
        forEachConvType { conversationId ->
            val testSubscriber = messageService.conversationInfoUpdates.testSubscriber()

            val conversationDisplayInfo = ConversationDisplayInfo(
                conversationId,
                randomGroupName(),
                1,
                LastMessageData("contact", randomMessageText(), currentTimestamp())
            )

            whenever(messagePersistenceManager.getConversationDisplayInfo(conversationId)).thenResolve(conversationDisplayInfo)

            body(conversationId)

            assertThat(testSubscriber.onNextEvents).apply {
                `as`("Should emit an update event")
                containsOnly(conversationDisplayInfo)
            }
        }

    }

    @Test
    fun `it should emit a conversation info update when markConversationAsRead is called`() {
        testConversationInfoUpdate {
            messageService.markConversationAsRead(it).get()
        }
    }

    @Test
    fun `it should emit a conversation info update when addMessage is called for a received message`() {
        testConversationInfoUpdate { conversationId ->
            val conversationMessageInfo = randomReceivedConversationMessageInfo(randomUserId())

            messageService.addMessage(conversationId, conversationMessageInfo).get()
        }
    }

    @Test
    fun `it should not emit a conversation info update when addMessage is called for a sent message`() {
        forEachConvType { conversationId ->
            val testSubscriber = messageService.conversationInfoUpdates.testSubscriber()

            messageService.addMessage(conversationId, randomSentConversationMessageInfo()).get()

            assertThat(testSubscriber.onNextEvents).apply {
                `as`("Should not emit an update")
                isEmpty()
            }
        }
    }

    @Test
    fun `it should emit a conversation info update when deleteAllMessages is called`() {
        testConversationInfoUpdate { conversationId ->
            messageService.deleteAllMessages(conversationId)
        }
    }

    @Test
    fun `it should emit an expiring event when startMessageExpiration is called for an existing message id`() {
        forEachConvType { conversationId ->
            val baseTime = 1L
            val ttl = 500L

            val conversationMessageInfo = ConversationMessageInfo(
                null,
                MessageInfo.Companion.newSent(randomMessageText(), ttl)
            )

            val messageId = conversationMessageInfo.info.id

            whenever(messagePersistenceManager.get(conversationId, messageId)).thenResolve(conversationMessageInfo)

            val testSubscriber = messageUpdateEventCollectorFor<MessageUpdateEvent.Expiring>()

            withTimeAs(baseTime) {
                messageService.startMessageExpiration(conversationId, messageId).get()
            }

            val expiresAt = baseTime + ttl

            verify(messagePersistenceManager).setExpiration(conversationId, messageId, expiresAt)

            val expectedEvent = MessageUpdateEvent.Expiring(conversationId, messageId, ttl, expiresAt)

            assertEventEmitted(testSubscriber) {
                assertEquals(expectedEvent, it, "Invalid event")
            }
        }
    }

    @Test
    fun `it should not emit an expiring event when startMessageExpiration is called for an invalid message id`() {
        forEachConvType { conversationId ->
            whenever(messagePersistenceManager.get(any(), any())).thenResolve(null)

            val testSubscriber = messageUpdateEventCollectorFor<MessageUpdateEvent.Expiring>()

            messageService.startMessageExpiration(conversationId, randomMessageId()).get()

            verify(messagePersistenceManager, never()).setExpiration(any(), any(), any())

            assertNoEventsEmitted(testSubscriber)
        }
    }

    @Test
    fun `it should not emit an expiring event when startMessageExpiration is called for an already expiring message id`() {
        forEachConvType { conversationId ->
            whenever(messagePersistenceManager.get(any(), any())).thenResolve(null)

            whenever(messagePersistenceManager.setExpiration(any(), any(), any())).thenResolve(false)

            val testSubscriber = messageUpdateEventCollectorFor<MessageUpdateEvent.Expiring>()

            messageService.startMessageExpiration(conversationId, randomMessageId()).get()

            verify(messagePersistenceManager, never()).setExpiration(any(), any(), any())

            assertNoEventsEmitted(testSubscriber)
        }
    }

    @Test
    fun `it should emit expired events for messages when expireMessages is called`() {
        forEachConvType { conversationId ->
            val messageIds = (0..2).map { randomMessageId() }

            val messages = mapOf(
                conversationId to messageIds
            )

            val testSubscriber = messageUpdateEventCollectorFor<MessageUpdateEvent.Expired>()

            messageService.expireMessages(messages, false).get()

            val expected = messageIds.map {
                MessageUpdateEvent.Expired(conversationId, it, false)
            }

            assertThat(testSubscriber.onNextEvents).apply {
                `as`("Should emit events")
                containsOnlyElementsOf(expected)
            }
        }
    }

    @Test
    fun `it should emit expired events with the given fromSync value when expiredMessages is called`() {
        val conversationId = randomUserConversationId()
        val messageId = randomMessageId()

        val messages = mapOf<ConversationId, List<String>>(
            conversationId to listOf(messageId)
        )

        forEachConvType {
            listOf(true, false).forEach { fromSync ->
                val testSubscriber = messageUpdateEventCollectorFor<MessageUpdateEvent.Expired>()
                messageService.expireMessages(messages, fromSync)

                val expected = MessageUpdateEvent.Expired(conversationId, messageId, fromSync)

                assertThat(testSubscriber.onNextEvents).apply {
                    `as`("Should emit events")
                    containsOnly(expected)
                }
            }
        }
    }

    @Test
    fun `it should emit a Deleted event when deleteMessages is called`() {
        forEachConvType { conversationId ->
            val testSubscriber = messageUpdateEventCollectorFor<MessageUpdateEvent.Deleted>()
            val messageIds = (0..1).map { randomMessageId() }

            messageService.deleteMessages(conversationId, messageIds).get()

            val expected = MessageUpdateEvent.Deleted(conversationId, messageIds)

            assertEventEmitted(testSubscriber) {
                assertEquals(expected, it, "Invalid event")
            }
        }
    }

    @Test
    fun `it should emit a DeletedAll event when deleteAllMessages is called`() {
        forEachConvType { conversationId ->
            val testSubscriber = messageUpdateEventCollectorFor<MessageUpdateEvent.DeletedAll>()

            messageService.deleteAllMessages(conversationId).get()

            val expected = MessageUpdateEvent.DeletedAll(conversationId)

            assertEventEmitted(testSubscriber) {
                assertEquals(expected, it, "Invalid event")
            }
        }
    }
}