package com.android.systemui.axdynamicbar.ui.compose

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.android.systemui.axdynamicbar.shared.AlphaHint

@Composable
fun AxDynamicBarEventIndicator(
    count: Int,
    activeIndex: Int,
    color: Color,
    modifier: Modifier = Modifier,
) {
    if (count <= 1) return

    val dotCount = count.coerceAtMost(5)
    val safeIndex = activeIndex.coerceAtLeast(0) % dotCount

    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(3.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        repeat(dotCount) { i ->
            val active = i == safeIndex
            Box(
                Modifier
                    .size(if (active) 5.dp else 4.dp)
                    .background(
                        color = color.copy(alpha = if (active) 1f else AlphaHint),
                        shape = CircleShape,
                    )
            )
        }
    }
}
