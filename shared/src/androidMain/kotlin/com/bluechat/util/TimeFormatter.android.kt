package com.bluechat.util

import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

actual fun formatTimestamp(epochMillis: Long): String {
    val formatter = SimpleDateFormat("HH:mm", Locale.getDefault())
    return formatter.format(Date(epochMillis))
}

actual fun formatRelativeTime(epochMillis: Long): String {
    val now = System.currentTimeMillis()
    val diffMs = now - epochMillis
    val diffMinutes = diffMs / (1000 * 60)
    val diffHours = diffMs / (1000 * 60 * 60)

    return when {
        diffMinutes < 1 -> "Just now"
        diffMinutes < 60 -> "$diffMinutes min ago"
        diffHours < 24 && isSameDay(epochMillis, now) -> formatTimestamp(epochMillis)
        else -> {
            val formatter = SimpleDateFormat("MMM d, HH:mm", Locale.getDefault())
            formatter.format(Date(epochMillis))
        }
    }
}

actual fun formatDateSeparator(epochMillis: Long): String {
    val now = System.currentTimeMillis()
    val messageCalendar = Calendar.getInstance().apply { timeInMillis = epochMillis }
    val todayCalendar = Calendar.getInstance().apply { timeInMillis = now }
    val yesterdayCalendar = Calendar.getInstance().apply {
        timeInMillis = now
        add(Calendar.DAY_OF_YEAR, -1)
    }

    return when {
        isSameCalendarDay(messageCalendar, todayCalendar) -> "Today"
        isSameCalendarDay(messageCalendar, yesterdayCalendar) -> "Yesterday"
        else -> {
            val formatter = SimpleDateFormat("EEEE, MMMM d, yyyy", Locale.getDefault())
            formatter.format(Date(epochMillis))
        }
    }
}

actual fun isSameDay(epochMillis1: Long, epochMillis2: Long): Boolean {
    val cal1 = Calendar.getInstance().apply { timeInMillis = epochMillis1 }
    val cal2 = Calendar.getInstance().apply { timeInMillis = epochMillis2 }
    return isSameCalendarDay(cal1, cal2)
}

private fun isSameCalendarDay(cal1: Calendar, cal2: Calendar): Boolean {
    return cal1.get(Calendar.YEAR) == cal2.get(Calendar.YEAR) &&
            cal1.get(Calendar.DAY_OF_YEAR) == cal2.get(Calendar.DAY_OF_YEAR)
}
