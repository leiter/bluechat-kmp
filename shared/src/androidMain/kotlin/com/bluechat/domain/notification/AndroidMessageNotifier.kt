package com.bluechat.domain.notification

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.Person
import androidx.core.content.ContextCompat

/**
 * One `MessagingStyle` notification per conversation (E4).
 *
 * Each conversation's notification is posted under the tag `conversationId` with a fixed id,
 * so a new message replaces that conversation's notification instead of stacking another one
 * (the old `notificationId++` did that). Earlier messages are carried over by reading the
 * style back from the notification that is currently showing, so nothing is kept in memory
 * here and a process restart loses nothing the user can still see.
 *
 * Tapping opens the app on that conversation. The launch intent carries
 * [EXTRA_CONVERSATION_ID], which `MainActivity` hands to `ConversationDeepLinks`.
 */
class AndroidMessageNotifier(
    private val context: Context,
) : MessageNotifier {

    private val manager = NotificationManagerCompat.from(context)

    private val self = Person.Builder().setName("You").build()

    init {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.getSystemService(NotificationManager::class.java).createNotificationChannel(
                NotificationChannel(CHANNEL_ID, CHANNEL_NAME, NotificationManager.IMPORTANCE_HIGH),
            )
        }
    }

    override fun showMessage(
        conversationId: String,
        senderName: String,
        text: String,
        timestamp: Long,
    ) {
        if (!canNotify()) return

        val sender = Person.Builder().setName(senderName).setKey(conversationId).build()
        val style = activeStyle(conversationId) ?: NotificationCompat.MessagingStyle(self)
        style.addMessage(text, timestamp, sender)
        // Keep the notification readable. The full history is one tap away.
        while (style.messages.size > MAX_MESSAGES) style.messages.removeAt(0)

        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_email)
            .setStyle(style)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setContentIntent(openConversationIntent(conversationId))
            .setAutoCancel(true)
            .build()

        manager.notify(conversationId, NOTIFICATION_ID, notification)
    }

    override fun cancel(conversationId: String) {
        manager.cancel(conversationId, NOTIFICATION_ID)
    }

    private fun canNotify(): Boolean {
        val permitted = Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED
        return permitted && manager.areNotificationsEnabled()
    }

    /** The style of [conversationId]'s notification if it is still showing, so messages accumulate. */
    private fun activeStyle(conversationId: String): NotificationCompat.MessagingStyle? =
        context.getSystemService(NotificationManager::class.java)
            .activeNotifications
            .firstOrNull { it.tag == conversationId && it.id == NOTIFICATION_ID }
            ?.notification
            ?.let(NotificationCompat.MessagingStyle::extractMessagingStyleFromNotification)

    private fun openConversationIntent(conversationId: String): PendingIntent? {
        val launch = context.packageManager.getLaunchIntentForPackage(context.packageName)
            ?: return null
        launch.putExtra(EXTRA_CONVERSATION_ID, conversationId)
        // Reuse the running activity (onNewIntent) instead of stacking a second one. The
        // manifest's singleTask does that already; the flags keep it true if that changes.
        launch.addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        return PendingIntent.getActivity(
            context,
            // PendingIntents that differ only in extras are the same PendingIntent, so each
            // conversation needs its own request code or every tap would open the last one.
            conversationId.hashCode(),
            launch,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    companion object {
        const val EXTRA_CONVERSATION_ID = "com.bluechat.extra.CONVERSATION_ID"

        private const val CHANNEL_ID = "bluechat_messages"
        private const val CHANNEL_NAME = "Messages"

        /** Constant: the tag (the conversation id) is what tells notifications apart. */
        private const val NOTIFICATION_ID = 1
        private const val MAX_MESSAGES = 7
    }
}
