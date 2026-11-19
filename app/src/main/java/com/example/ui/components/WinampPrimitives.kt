package com.example.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.ui.theme.JetBrainsMonoFamily
import com.example.ui.theme.SpaceGroteskFamily
import com.example.ui.theme.WinampColors
import java.util.Locale

fun Modifier.winampBevel(
    raised: Boolean = true,
    strokeWidth: Dp = 1.5.dp
): Modifier = drawBehind {
    val sw = strokeWidth.toPx()
    val half = sw / 2f
    val topLeftColor = if (raised) WinampColors.BevelHighlight else WinampColors.BevelShadow
    val bottomRightColor = if (raised) WinampColors.BevelShadow else WinampColors.BevelHighlight

    // Top edge
    drawLine(
        color = topLeftColor,
        start = Offset(0f, half),
        end = Offset(size.width, half),
        strokeWidth = sw
    )
    // Left edge
    drawLine(
        color = topLeftColor,
        start = Offset(half, 0f),
        end = Offset(half, size.height),
        strokeWidth = sw
    )
    // Bottom edge
    drawLine(
        color = bottomRightColor,
        start = Offset(0f, size.height - half),
        end = Offset(size.width, size.height - half),
        strokeWidth = sw
    )
    // Right edge
    drawLine(
        color = bottomRightColor,
        start = Offset(size.width - half, 0f),
        end = Offset(size.width - half, size.height),
        strokeWidth = sw
    )
}

@Composable
fun WinampModulePanel(
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(
                brush = Brush.verticalGradient(
                    colors = listOf(
                        WinampColors.ChassisSurfaceTop,
                        WinampColors.ChassisSurface,
                        WinampColors.ChassisSurfaceBottom
                    )
                )
            )
            .winampBevel(raised = true, strokeWidth = 1.8.dp)
            .padding(horizontal = 6.dp, vertical = 5.dp),
        content = content
    )
}

@Composable
fun WinampModuleTitleBar(
    title: String,
    rightBadge: String? = null,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        // Tiny corner indicator stud
        Box(
            modifier = Modifier
                .size(6.dp)
                .background(WinampColors.ChassisSurfaceBottom)
                .winampBevel(raised = false, strokeWidth = 1.dp)
        )

        GroovedLines(modifier = Modifier.weight(1f))

        Text(
            text = title,
            fontFamily = SpaceGroteskFamily,
            fontWeight = FontWeight.Bold,
            fontSize = 11.sp,
            letterSpacing = 1.4.sp,
            color = WinampColors.TitleGold,
            maxLines = 1
        )

        if (!rightBadge.isNullOrBlank()) {
            Text(
                text = rightBadge,
                fontFamily = JetBrainsMonoFamily,
                fontWeight = FontWeight.Medium,
                fontSize = 9.sp,
                color = WinampColors.TitleSilver,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }

        GroovedLines(modifier = Modifier.weight(1f))

        // Tiny corner stud
        Box(
            modifier = Modifier
                .size(6.dp)
                .background(WinampColors.ChassisSurfaceBottom)
                .winampBevel(raised = false, strokeWidth = 1.dp)
        )
    }
}

@Composable
private fun GroovedLines(modifier: Modifier = Modifier) {
    Canvas(
        modifier = modifier.height(10.dp)
    ) {
        val offsets = listOf(0.2f, 0.5f, 0.8f)
        offsets.forEach { frac ->
            val y = size.height * frac
            drawLine(
                color = WinampColors.GrooveHighlight.copy(alpha = 0.75f),
                start = Offset(0f, y),
                end = Offset(size.width, y),
                strokeWidth = 1.dp.toPx()
            )
            drawLine(
                color = WinampColors.GrooveLine,
                start = Offset(0f, y + 1.dp.toPx()),
                end = Offset(size.width, y + 1.dp.toPx()),
                strokeWidth = 1.dp.toPx()
            )
        }
    }
}

@Composable
fun WinampLcdBox(
    modifier: Modifier = Modifier,
    contentAlignment: Alignment = Alignment.TopStart,
    content: @Composable BoxScope.() -> Unit
) {
    Box(
        modifier = modifier
            .background(WinampColors.LcdBlack)
            .winampBevel(raised = false, strokeWidth = 1.4.dp),
        contentAlignment = contentAlignment,
        content = content
    )
}

@Composable
fun WinampMetallicButton(
    onClick: () -> Unit,
    testTag: String,
    contentDesc: String,
    modifier: Modifier = Modifier,
    showLed: Boolean = false,
    ledActive: Boolean = false,
    minHeight: Dp = 36.dp,
    content: @Composable () -> Unit
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()

    val topColor = if (isPressed) WinampColors.ChassisSurfaceBottom else Color(0xFF475068)
    val bottomColor = if (isPressed) Color(0xFF343B4D) else Color(0xFF202533)

    Box(
        modifier = modifier
            .testTag(testTag)
            .defaultMinSize(minHeight = minHeight)
            .clip(RoundedCornerShape(2.dp))
            .background(Brush.verticalGradient(listOf(topColor, bottomColor)))
            .winampBevel(raised = !isPressed, strokeWidth = 1.4.dp)
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                role = Role.Button,
                onClick = onClick
            )
            .semantics { contentDescription = contentDesc }
            .padding(horizontal = 6.dp, vertical = 4.dp),
        contentAlignment = Alignment.Center
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center
        ) {
            if (showLed) {
                Box(
                    modifier = Modifier
                        .size(width = 5.dp, height = 9.dp)
                        .background(if (ledActive) WinampColors.LedOn else WinampColors.LedOff)
                        .winampBevel(raised = false, strokeWidth = 0.75.dp)
                )
                Spacer(Modifier.width(4.dp))
            }
            content()
        }
    }
}

fun formatWinampTime(ms: Long): String {
    val totalSeconds = (ms / 1000L).coerceAtLeast(0L)
    val minutes = totalSeconds / 60L
    val seconds = totalSeconds % 60L
    return String.format(Locale.US, "%02d:%02d", minutes, seconds)
}

fun formatShortTime(ms: Long): String {
    val totalSeconds = (ms / 1000L).coerceAtLeast(0L)
    val minutes = totalSeconds / 60L
    val seconds = totalSeconds % 60L
    return String.format(Locale.US, "%d:%02d", minutes, seconds)
}
