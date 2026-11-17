package com.bluechat.util

import platform.Foundation.NSCalendar
import platform.Foundation.NSCalendarUnitDay
import platform.Foundation.NSCalendarUnitYear
import platform.Foundation.NSDate
import platform.Foundation.NSDateFormatter
import platform.Foundation.dateWithTimeIntervalSince1970
import platform.Foundation.timeIntervalSince1970

actual fun formatTimestamp(epochMillis: Long): String {
    val date = NSDate.dateWithTimeIntervalSince1970(epochMillis / 1000.0)
    val formatter = NSDateFormatter()
    formatter.dateFormat = "HH:mm"
    return formatter.stringFromDate(date)
}

actual fun formatRelativeTime(epochMillis: Long): String {
    val now = (NSDate().timeIntervalSince1970 * 1000).toLong()
    val diffMs = now - epochMillis
    val diffMinutes = diffMs / (1000 * 60)
    val diffHours = diffMs / (1000 * 60 * 60)

    return when {
        diffMinutes < 1 -> "Just now"
        diffMinutes < 60 -> "$diffMinutes min ago"
        diffHours < 24 && isSameDay(epochMillis, now) -> formatTimestamp(epochMillis)
        else -> {
            val date = NSDate.dateWithTimeIntervalSince1970(epochMillis / 1000.0)
            val formatter = NSDateFormatter()
            formatter.dateFormat = "MMM d, HH:mm"
            formatter.stringFromDate(date)
        }
    }
}

actual fun formatDateSeparator(epochMillis: Long): String {
    val now = (NSDate().timeIntervalSince1970 * 1000).toLong()
    val calendar = NSCalendar.currentCalendar

    val messageDate = NSDate.dateWithTimeIntervalSince1970(epochMillis / 1000.0)
    val todayDate = NSDate()

    if (calendar.isDateInToday(messageDate)) {
        return "Today"
    }

    if (calendar.isDateInYesterday(messageDate)) {
        return "Yesterday"
    }

    val formatter = NSDateFormatter()
    formatter.dateFormat = "EEEE, MMMM d, yyyy"
    return formatter.stringFromDate(messageDate)
}

actual fun isSameDay(epochMillis1: Long, epochMillis2: Long): Boolean {
    val calendar = NSCalendar.currentCalendar
    val date1 = NSDate.dateWithTimeIntervalSince1970(epochMillis1 / 1000.0)
    val date2 = NSDate.dateWithTimeIntervalSince1970(epochMillis2 / 1000.0)

    val components1 = calendar.components(NSCalendarUnitYear or NSCalendarUnitDay, date1)
    val components2 = calendar.components(NSCalendarUnitYear or NSCalendarUnitDay, date2)

    return components1.year == components2.year && components1.day == components2.day
}
