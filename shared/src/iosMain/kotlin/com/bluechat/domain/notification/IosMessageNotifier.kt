package com.bluechat.domain.notification

import platform.Foundation.NSUUID
import platform.UserNotifications.UNAuthorizationOptionAlert
import platform.UserNotifications.UNAuthorizationOptionBadge
import platform.UserNotifications.UNAuthorizationOptionSound
import platform.UserNotifications.UNMutableNotificationContent
import platform.UserNotifications.UNNotification
import platform.UserNotifications.UNNotificationRequest
import platform.UserNotifications.UNNotificationSound
import platform.UserNotifications.UNUserNotificationCenter

/**
 * iOS keeps one notification per message but groups them by conversation through
 * `threadIdentifier`, which is the platform's equivalent of Android's per-conversation
 * notification.
 *
 * Permission is requested when this is created, i.e. at app start through
 * `IncomingMessageCoordinator`, the same moment Android asks for `POST_NOTIFICATIONS`.
 * Before E4 the app never asked, so iOS could not show a notification at all.
 *
 * Not done yet: opening the conversation on tap, which needs a
 * `UNUserNotificationCenterDelegate`.
 */
class IosMessageNotifier : MessageNotifier {

    private val center get() = UNUserNotificationCenter.currentNotificationCenter()

    init {
        center.requestAuthorizationWithOptions(
            UNAuthorizationOptionAlert or UNAuthorizationOptionSound or UNAuthorizationOptionBadge,
        ) { _, _ -> }
    }

    override fun showMessage(
        conversationId: String,
        senderName: String,
        text: String,
        timestamp: Long,
    ) {
        val content = UNMutableNotificationContent().apply {
            setTitle(senderName)
            setBody(text)
            setThreadIdentifier(conversationId)
            setSound(UNNotificationSound.defaultSound)
        }
        val request = UNNotificationRequest.requestWithIdentifier(
            identifier = NSUUID().UUIDString,
            content = content,
            trigger = null,
        )
        center.addNotificationRequest(request, null)
    }

    override fun cancel(conversationId: String) {
        center.getDeliveredNotificationsWithCompletionHandler { delivered ->
            val ids = delivered.orEmpty()
                .filterIsInstance<UNNotification>()
                .filter { it.request.content.threadIdentifier == conversationId }
                .map { it.request.identifier }
            if (ids.isNotEmpty()) center.removeDeliveredNotificationsWithIdentifiers(ids)
        }
    }
}
