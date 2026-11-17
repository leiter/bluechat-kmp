package com.bluechat.presentation.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.bluechat.util.SignalStrength

@Composable
fun SignalStrengthIndicator(
    signalStrength: SignalStrength,
    modifier: Modifier = Modifier,
) {
    val activeBars = signalStrength.bars
    val barHeights = listOf(6, 10, 14, 18)
    val activeColor = when (signalStrength) {
        SignalStrength.Excellent -> Color(0xFF4CAF50) // Green
        SignalStrength.Good -> Color(0xFF8BC34A) // Light Green
        SignalStrength.Fair -> Color(0xFFFFC107) // Yellow/Amber
        SignalStrength.Poor -> Color(0xFFF44336) // Red
        SignalStrength.None -> Color.Gray
    }
    val inactiveColor = MaterialTheme.colorScheme.outlineVariant

    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(2.dp),
        verticalAlignment = Alignment.Bottom,
    ) {
        barHeights.forEachIndexed { index, height ->
            val isActive = index < activeBars
            Box(
                modifier = Modifier
                    .width(4.dp)
                    .height(height.dp)
                    .clip(RoundedCornerShape(1.dp))
                    .background(if (isActive) activeColor else inactiveColor),
            )
        }
    }
}
