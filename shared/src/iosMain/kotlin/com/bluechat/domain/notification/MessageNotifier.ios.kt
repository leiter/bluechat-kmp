package com.bluechat.domain.notification

import platform.UserNotifications.UNMutableNotificationContent
import platform.UserNotifications.UNNotificationRequest
import platform.UserNotifications.UNUserNotificationCenter
import platform.Foundation.NSUUID

actual class MessageNotifier {
    actual fun showMessageNotification(senderName: String, messageText: String) {
        val content = UNMutableNotificationContent().apply {
            setTitle(senderName)
            setBody(messageText)
            setSound(platform.UserNotifications.UNNotificationSound.defaultSound)
        }
        val request = UNNotificationRequest.requestWithIdentifier(
            identifier = NSUUID().UUIDString,
            content = content,
            trigger = null,
        )
        UNUserNotificationCenter.currentNotificationCenter().addNotificationRequest(request, null)
    }
}
