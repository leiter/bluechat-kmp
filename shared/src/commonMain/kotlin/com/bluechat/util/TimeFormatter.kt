package com.bluechat.util

expect fun formatTimestamp(epochMillis: Long): String

expect fun formatRelativeTime(epochMillis: Long): String

expect fun formatDateSeparator(epochMillis: Long): String

expect fun isSameDay(epochMillis1: Long, epochMillis2: Long): Boolean
