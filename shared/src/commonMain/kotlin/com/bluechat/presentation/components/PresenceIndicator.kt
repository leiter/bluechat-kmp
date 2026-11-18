package com.bluechat.presentation.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.bluechat.domain.presence.Presence

/**
 * B5: a filled dot for [Presence.Connected], a ring for [Presence.Nearby], nothing otherwise.
 *
 * "Not nearby" is the normal state for most contacts most of the time, so it gets no mark.
 * Only the reachable ones stand out.
 */
@Composable
fun PresenceIndicator(presence: Presence, modifier: Modifier = Modifier) {
    val label = presenceLabel(presence) ?: return
    val color = MaterialTheme.colorScheme.primary
    val shape = Modifier
        .size(10.dp)
        .semantics { contentDescription = label }
    when (presence) {
        Presence.Connected -> Box(modifier.then(shape).background(color, CircleShape))
        Presence.Nearby -> Box(modifier.then(shape).border(2.dp, color, CircleShape))
        Presence.NotNearby -> Unit
    }
}

/** Text for [presence], or `null` for [Presence.NotNearby], which callers describe themselves. */
fun presenceLabel(presence: Presence): String? = when (presence) {
    Presence.Connected -> "Connected"
    Presence.Nearby -> "Nearby"
    Presence.NotNearby -> null
}
