@file:JvmName("RandomUtils")
package io.slychat.messenger.core

import io.slychat.messenger.core.persistence.*
import java.util.*

fun randomGroupInfo(): GroupInfo = randomGroupInfo(false, GroupMembershipLevel.JOINED)
fun randomGroupInfo(isPending: Boolean, membershipLevel: GroupMembershipLevel): GroupInfo =
    GroupInfo(randomGroupId(), randomGroupName(), isPending, membershipLevel)

fun randomGroupName(): String = randomUUID()

fun randomGroupMembers(n: Int = 2): Set<UserId> = (1..n).mapTo(HashSet()) { randomUserId() }

fun randomUserId(): UserId {
    val l = 1 + Random().nextInt(10000-1) + 1
    return UserId(l.toLong())
}

fun randomUserIds(n: Int = 2): Set<UserId> = (1..n).mapToSet { randomUserId() }

fun randomGroupId(): GroupId = GroupId(randomUUID())

fun randomMessageId(): String = randomUUID()

fun randomTextGroupMetadata(groupId: GroupId? = null): MessageMetadata {
    return MessageMetadata(
        randomUserId(),
        groupId ?: randomGroupId(),
        MessageCategory.TEXT_GROUP,
        randomMessageId()
    )
}

fun randomTextSingleMetadata(): MessageMetadata {
    val recipient = randomUserId()
    val messageId = randomUUID()
    return MessageMetadata(
        recipient,
        null,
        MessageCategory.TEXT_SINGLE,
        messageId
    )

}

fun randomSerializedMessage(): ByteArray = Random().nextInt().toString().toByteArray()

fun randomQueuedMessage(): QueuedMessage {
    val serialized = randomSerializedMessage()

    val metadata = randomTextSingleMetadata()

    val queued = QueuedMessage(
        metadata,
        currentTimestamp(),
        serialized
    )

    return queued
}

fun randomQueuedMessages(n: Int = 2): List<QueuedMessage> {
    return (1..n).map { randomQueuedMessage() }
}

fun randomContactInfo(): ContactInfo {
    val userId = randomUserId()

    return ContactInfo(
        userId,
        "$userId@domain.com",
        userId.toString(),
        AllowedMessageLevel.ALL,
        false,
        null,
        "pubkey"
    )
}
