package com.bluechat.domain.notification

expect class MessageNotifier {
    fun showMessageNotification(senderName: String, messageText: String)
}
