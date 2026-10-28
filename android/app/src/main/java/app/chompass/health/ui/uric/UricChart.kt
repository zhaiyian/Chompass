package app.chompass.health.ui.uric

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.chompass.R
import app.chompass.health.domain.uric.AxisTick
import app.chompass.health.domain.uric.UricPeriod
import app.chompass.health.model.UricLevel
import app.chompass.health.ui.uricLevelColor
import app.chompass.health.ui.uricTickLabel
import app.chompass.ui.theme.warning

/**
 * Hand-drawn trend chart (no chart dependency, same choice the source app made).
 *
 * What it has to show, in priority order: where each reading sits relative to
 * the target line, whether the trend is heading up or down, and when "today" is
 * inside the plotted window. Everything else (grid, areas) is deliberately off —
 * a chronic-condition chart is read at a glance, not zoomed into.
 */
@Composable
fun UricTrendChart(
    points: List<UricPointUi>,
    ticks: List<AxisTick>,
    period: UricPeriod,
    targetUmol: Int,
    axisTop: Int,
    todayFraction: Float?,
    modifier: Modifier = Modifier,
    height: Dp = 190.dp,
) {
    val labelColor = MaterialTheme.colorScheme.onSurfaceVariant
    val lineColor = MaterialTheme.colorScheme.primary
    val gridColor = MaterialTheme.colorScheme.outlineVariant
    val todayColor = MaterialTheme.colorScheme.warning
    val onTargetColor = uricLevelColor(UricLevel.BELOW_TARGET)
    val nearColor = uricLevelColor(UricLevel.NEAR)
    val highColor = uricLevelColor(UricLevel.HIGH)
    val veryHighColor = uricLevelColor(UricLevel.VERY_HIGH)
    val targetText = stringResource(R.string.health_chart_target)
    val density = LocalDensity.current
    val labelPx = with(density) { 10.sp.toPx() }
    val dash = PathEffect.dashPathEffect(floatArrayOf(8f, 8f))

    fun colorFor(level: UricLevel): Color = when (level) {
        UricLevel.BELOW_TARGET -> onTargetColor
        UricLevel.NEAR -> nearColor
        UricLevel.HIGH -> highColor
        UricLevel.VERY_HIGH -> veryHighColor
    }

    Canvas(modifier.fillMaxWidth().height(height)) {
        val labelBand = labelPx * 2.2f
        val topPad = 10.dp.toPx()
        val bottom = size.height - labelBand
        val plotWidth = size.width - labelPx // keep the last label inside the box
        val plotHeight = (bottom - topPad).coerceAtLeast(1f)
        val scaleMax = axisTop.coerceAtLeast(1)

        fun x(fraction: Float): Float = fraction.coerceIn(0f, 1f) * plotWidth
        fun y(value: Int): Float = bottom - (value.toFloat() / scaleMax) * plotHeight

        // Target: the only horizontal reference. Dashed, so it never reads as data.
        drawLine(
            color = gridColor,
            start = Offset(0f, y(targetUmol)),
            end = Offset(plotWidth, y(targetUmol)),
            strokeWidth = 1.dp.toPx(),
            pathEffect = dash,
        )

        // "Today" as a vertical marker: the range can be paged back in time.
        todayFraction?.let { fraction ->
            drawLine(
                color = todayColor.copy(alpha = 0.5f),
                start = Offset(x(fraction), topPad),
                end = Offset(x(fraction), bottom),
                strokeWidth = 1.dp.toPx(),
                pathEffect = dash,
            )
        }

        if (points.isNotEmpty()) {
            val path = Path()
            points.forEachIndexed { index, point ->
                val px = x(point.fraction)
                val py = y(point.valueUmol)
                if (index == 0) path.moveTo(px, py) else path.lineTo(px, py)
            }
            if (points.size > 1) {
                drawPath(
                    path = path,
                    color = lineColor,
                    style = Stroke(
                        width = 2.dp.toPx(),
                        cap = StrokeCap.Round,
                        join = StrokeJoin.Round,
                    ),
                )
            }
            points.forEach { point ->
                val center = Offset(x(point.fraction), y(point.valueUmol))
                drawCircle(
                    color = colorFor(point.level),
                    radius = (if (point.latest) 5.dp else 3.5.dp).toPx(),
                    center = center,
                )
                if (point.latest) {
                    drawCircle(
                        color = colorFor(point.level).copy(alpha = 0.3f),
                        radius = 9.dp.toPx(),
                        center = center,
                    )
                }
            }
        }

        drawIntoCanvas { canvas ->
            val paint = android.graphics.Paint().apply {
                isAntiAlias = true
                color = labelColor.toArgb()
                textSize = labelPx
            }

            paint.textAlign = android.graphics.Paint.Align.LEFT
            canvas.nativeCanvas.drawText(
                targetText,
                0f,
                (y(targetUmol) - 4.dp.toPx()).coerceAtLeast(labelPx),
                paint,
            )

            paint.textAlign = android.graphics.Paint.Align.CENTER
            val labelBaseline = size.height - 2.dp.toPx()
            ticks.forEach { tick ->
                canvas.nativeCanvas.drawText(
                    uricTickLabel(period, tick.date),
                    x(tick.fraction).coerceIn(labelPx, plotWidth - labelPx),
                    labelBaseline,
                    paint,
                )
            }
        }
    }
}

/** Empty-state placeholder for the chart card. */
@Composable
fun UricChartEmpty(message: String, modifier: Modifier = Modifier, height: Dp = 190.dp) {
    Box(
        modifier = modifier.fillMaxWidth().height(height),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = message,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
    }
}
