package com.craftforge.app.ui.theme

import android.annotation.SuppressLint
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

// ================= КОМПОНЕНТИ ІНФОРМАЦІЇ (ДЛЯ ХАРАКТЕРИСТИК) =================

@Composable
fun InfoRowStyled(label: String, value: String, labelColor: Color, valueColor: Color, fontSize: TextUnit) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(text = label, color = labelColor, fontSize = fontSize)
        Text(text = value, color = valueColor, fontWeight = FontWeight.Bold, fontSize = fontSize, textAlign = TextAlign.End, modifier = Modifier.fillMaxWidth(0.65f))
    }
}

@Composable
fun InfoCardUniversal(
    title: String,
    info: List<Pair<String, String>>,
    progressPercent: Int? = null,
    chartPoints: List<Float>? = null,
    itemSpacing: Dp = 2.dp,
    styles: InfoCardStyles = infoCardStyles(),
    onClick: (() -> Unit)? = null
) {
    StyledBlockCard(title = title, styles = styles) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
                .padding(styles.innerColumnPadding),
            verticalArrangement = Arrangement.spacedBy(itemSpacing)
        ) {
            progressPercent?.let { percent ->
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
                    ProgressCircleWithText(percent, styles)
                    Column(horizontalAlignment = Alignment.End, modifier = Modifier.weight(1f).padding(start = 16.dp)) {
                        info.forEach { (label, value) -> InfoRowStyled(label, value, styles.labelTextColor, styles.valueTextColor, styles.rowFontSize) }
                    }
                }
            } ?: run {
                info.forEach { (label, value) -> InfoRowStyled(label, value, styles.labelTextColor, styles.valueTextColor, styles.rowFontSize) }
            }
            chartPoints?.let { points -> LineChartBox(points, itemSpacing, styles) }
        }
    }
}

// ================= ГРАФІКИ =================

@Composable
fun ProgressCircleWithText(percent: Int, styles: InfoCardStyles = infoCardStyles()) {
    val size = styles.progressSize
    val strokeWidth = styles.progressStrokeWidth

    Box(contentAlignment = Alignment.Center, modifier = Modifier.size(size)) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val radius = (size.toPx() - strokeWidth.toPx()) / 2
            val centerOffset = Offset(size.toPx() / 2, size.toPx() / 2)
            drawCircle(color = styles.progressTrackColor, radius = radius, center = centerOffset, style = Stroke(width = strokeWidth.toPx(), cap = StrokeCap.Round))
            drawArc(color = styles.accentColor, startAngle = -90f, sweepAngle = 360f * (percent / 100f), useCenter = false, topLeft = Offset(centerOffset.x - radius, centerOffset.y - radius), size = Size(radius * 2, radius * 2), style = Stroke(width = strokeWidth.toPx(), cap = StrokeCap.Round))
        }
        Text(text = "$percent%", fontSize = styles.progressFontSize, fontWeight = FontWeight.Bold, color = styles.accentColor)
    }
}

@Composable
fun LineChartBox(points: List<Float>, itemSpacing: Dp, styles: InfoCardStyles) {
    Spacer(modifier = Modifier.height(itemSpacing))
    Box(
        modifier = Modifier.fillMaxWidth().height(styles.chartHeight).background(styles.chartBackgroundColor, RoundedCornerShape(4.dp)).padding(horizontal = 4.dp, vertical = 2.dp)
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            if (points.size > 1) {
                val step = size.width / (points.size - 1)
                for (i in 0 until points.size - 1) {
                    val y1 = size.height * (1f - points[i].coerceIn(0f, 1f))
                    val y2 = size.height * (1f - points[i + 1].coerceIn(0f, 1f))
                    drawLine(color = styles.accentColor, start = Offset(i * step, y1), end = Offset((i + 1) * step, y2), strokeWidth = styles.chartLineThickness, cap = StrokeCap.Round)
                }
            }
        }
    }
}

// ================= ГРАФІКИ ТА ЕЛЕМЕНТИ (ОНОВЛЕНО ПІД СТИЛЬ TWEAKS) =================

@Composable
fun RamGraphCompose(
    history: List<Float>,
    styles: InfoCardStyles = infoCardStyles(),
    @SuppressLint("ModifierParameter") modifier: Modifier = Modifier,
    height: Dp = styles.chartHeight
) {
    val density = LocalDensity.current
    val chartLineThicknessPx = remember(density) { with(density) { 1.5.dp.toPx() } }
    val gridStrokeWidthPx = remember(density) { with(density) { 0.5.dp.toPx() } }

    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(height)
            .clip(RoundedCornerShape(6.dp))
            .background(Color.Transparent)
            .border(1.dp, styles.accentColor.copy(alpha = 0.3f), RoundedCornerShape(6.dp))
    ) {
        Canvas(modifier = Modifier.matchParentSize()) {
            val w = size.width
            val h = size.height
            if (history.size < 2) return@Canvas

            // Сітка
            val gridColor = styles.titleTextColor.copy(alpha = 0.05f)
            listOf(0.25f, 0.50f, 0.75f).forEach { fraction ->
                drawLine(color = gridColor, start = Offset(0f, h * fraction), end = Offset(w, h * fraction), strokeWidth = gridStrokeWidthPx)
                drawLine(color = gridColor, start = Offset(w * fraction, 0f), end = Offset(w * fraction, h), strokeWidth = gridStrokeWidthPx)
            }

            // Малювання лінії
            val step = w / (60 - 1).coerceAtLeast(1)
            val yPoints = history.map { h - (it.coerceIn(0f, 100f) / 100f * h) }

            val linePath = Path().apply {
                val startX = w - ((history.size - 1) * step)
                moveTo(startX, yPoints.first())
                for (i in 1 until history.size) {
                    val currentX = w - ((history.size - 1 - i) * step)
                    lineTo(currentX, yPoints[i])
                }
            }

            val fillPath = Path().apply {
                addPath(linePath)
                lineTo(w, h)
                val startX = w - ((history.size - 1) * step)
                lineTo(startX, h)
                close()
            }

            drawPath(
                path = fillPath,
                brush = Brush.verticalGradient(
                    listOf(styles.accentColor.copy(alpha = 0.4f), styles.accentColor.copy(alpha = 0.0f))
                )
            )

            drawPath(
                path = linePath,
                color = styles.accentColor,
                style = Stroke(width = chartLineThicknessPx, cap = StrokeCap.Round)
            )
        }
    }
}

@Composable
fun CpuGraphsGrid(histories: List<List<Float>>, maxFreq: Float, styles: InfoCardStyles = infoCardStyles()) {
    if (histories.isEmpty()) return

    val rows = histories.chunked(2)

    // Обгортаємо сітку ЦП у новий StyledBlockCard!
    StyledBlockCard(title = "Per-Core Usage (60s)", styles = styles) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            rows.forEachIndexed { rowIndex, rowHistories ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    rowHistories.forEachIndexed { colIndex, coreHistory ->
                        val coreNumber = rowIndex * 2 + colIndex
                        val currentPercent = coreHistory.lastOrNull() ?: 0f
                        val currentMhz = ((currentPercent / 100f) * maxFreq).toInt()

                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .aspectRatio(1.2f)
                                .clip(RoundedCornerShape(6.dp))
                                .border(1.dp, styles.accentColor.copy(alpha = 0.3f), RoundedCornerShape(6.dp))
                        ) {
                            CpuCoreGraph(history = coreHistory, styles = styles)

                            Text(
                                text = "CPU $coreNumber",
                                color = styles.titleTextColor.copy(alpha = 0.8f),
                                fontSize = 10.sp,
                                fontWeight = FontWeight.SemiBold,
                                modifier = Modifier.align(Alignment.TopStart).padding(4.dp)
                            )
                            Text(
                                text = "100%",
                                color = styles.titleTextColor.copy(alpha = 0.5f),
                                fontSize = 9.sp,
                                modifier = Modifier.align(Alignment.TopEnd).padding(4.dp)
                            )
                            Text(
                                text = "$currentMhz MHz",
                                color = styles.titleTextColor,
                                fontSize = 9.sp,
                                fontWeight = FontWeight.SemiBold,
                                modifier = Modifier.align(Alignment.BottomStart).padding(4.dp)
                            )
                            Text(
                                text = "0",
                                color = styles.titleTextColor.copy(alpha = 0.5f),
                                fontSize = 9.sp,
                                modifier = Modifier.align(Alignment.BottomEnd).padding(4.dp)
                            )
                        }
                    }
                    if (rowHistories.size == 1) {
                        Spacer(modifier = Modifier.weight(1f))
                    }
                }
            }
        }
    }
}
@Composable
fun CpuCoreGraph(
    history: List<Float>,
    styles: InfoCardStyles,
    @SuppressLint("ModifierParameter") modifier: Modifier = Modifier
) {
    val density = LocalDensity.current
    val chartLineThicknessPx = remember(density) { with(density) { 1.5.dp.toPx() } }
    val gridStrokeWidthPx = remember(density) { with(density) { 0.5.dp.toPx() } }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Color.Transparent)
    ) {
        Canvas(modifier = Modifier.matchParentSize()) {
            val w = size.width
            val h = size.height
            if (history.size < 2) return@Canvas

            val gridColor = styles.titleTextColor.copy(alpha = 0.05f)
            listOf(0.25f, 0.50f, 0.75f).forEach { fraction ->
                drawLine(color = gridColor, start = Offset(0f, h * fraction), end = Offset(w, h * fraction), strokeWidth = gridStrokeWidthPx)
                drawLine(color = gridColor, start = Offset(w * fraction, 0f), end = Offset(w * fraction, h), strokeWidth = gridStrokeWidthPx)
            }

            val step = w / (60 - 1).coerceAtLeast(1)
            val yPoints = history.map { h - (it.coerceIn(0f, 100f) / 100f * h) }

            val linePath = Path().apply {
                val startX = w - ((history.size - 1) * step)
                moveTo(startX, yPoints.first())
                for (i in 1 until history.size) {
                    val currentX = w - ((history.size - 1 - i) * step)
                    lineTo(currentX, yPoints[i])
                }
            }

            val fillPath = Path().apply {
                addPath(linePath)
                lineTo(w, h)
                val startX = w - ((history.size - 1) * step)
                lineTo(startX, h)
                close()
            }

            drawPath(
                path = fillPath,
                brush = Brush.verticalGradient(
                    listOf(styles.accentColor.copy(alpha = 0.4f), styles.accentColor.copy(alpha = 0.0f))
                )
            )

            drawPath(
                path = linePath,
                color = styles.accentColor,
                style = Stroke(width = chartLineThicknessPx, cap = StrokeCap.Round)
            )
        }
    }
}