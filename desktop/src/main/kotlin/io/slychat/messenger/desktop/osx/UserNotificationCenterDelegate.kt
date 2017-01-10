package io.slychat.messenger.desktop.osx

import io.slychat.messenger.core.SlyAddress
import io.slychat.messenger.core.persistence.ConversationId
import io.slychat.messenger.desktop.DesktopApp
import io.slychat.messenger.desktop.osx.ns.NSUserNotification
import io.slychat.messenger.desktop.osx.ns.NSUserNotificationCenter
import io.slychat.messenger.desktop.osx.ns.NSUserNotificationCenterDelegate

class UserNotificationCenterDelegate(private val desktopApp: DesktopApp) : NSUserNotificationCenterDelegate() {
    override fun didActivateNotification(center: NSUserNotificationCenter, notification: NSUserNotification) {
        val userInfo = notification.userInfo

        val typeString = userInfo[OSXNotificationService.USERINFO_TYPE_KEY] ?: return
        val type = NotificationType.valueOf(typeString)

        when (type) {
            NotificationType.CONVERSATION -> {
                val conversationIdString = userInfo[OSXNotificationService.USERINFO_CONVERSATION_ID_KEY] ?: return
                val accountString = userInfo[OSXNotificationService.USERINFO_ACCOUNT_KEY] ?: return

                val conversationId = ConversationId.fromString(conversationIdString)
                val account = SlyAddress.fromString(accountString)!!
                desktopApp.handleConversationNotificationActivated(account, conversationId)
            }
        }

        center.removeDeliveredNotification(notification)
    }

    override fun didDeliverNotification(center: NSUserNotificationCenter, notification: NSUserNotification) {
    }

    override fun shouldPresentNotification(center: NSUserNotificationCenter, notification: NSUserNotification): Boolean {
        return false
    }
}
