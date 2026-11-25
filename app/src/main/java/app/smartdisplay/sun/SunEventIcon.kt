package app.smartdisplay.sun

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import kotlin.math.min

/** 横÷縦。半円と、その左右に少し出る地平線に合わせている。 */
private const val SunEventIconAspect = 1.19f

private const val HeadLen = 0.42f
private const val ShaftLen = 0.58f
private const val ArrowGap = 0.10f
private const val LineWeight = 0.055f
private const val LineOverhang = 0.28f
private const val ShaftHalfRatio = 0.105f
private const val HeadHalfRatio = 0.27f
private const val AboveHorizon = HeadLen + ShaftLen + ArrowGap + 1f
private const val ContentHeight = AboveHorizon + LineWeight
private const val ContentWidth = 2f + LineOverhang * 2f

/** 地平線に乗る太陽。日の出は上向き、日の入りは下向きの矢印。 */
@Composable
fun SunEventIcon(
    rising: Boolean,
    color: Color,
    modifier: Modifier = Modifier,
) {
    val minLine = with(LocalDensity.current) { 1.dp.toPx() }
    val minShaft = with(LocalDensity.current) { 2.2.dp.toPx() }
    Canvas(
        modifier.aspectRatio(SunEventIconAspect, matchHeightConstraintsFirst = true),
    ) {
        val fit = 0.98f
        val radiusFromWidth = size.width * fit / ContentWidth
        val radiusIfNaturalLine = size.height * fit / ContentHeight
        val natural = min(radiusFromWidth, radiusIfNaturalLine)
        val radius: Float
        val lineStroke: Float
        if (LineWeight * natural >= minLine) {
            radius = natural
            lineStroke = LineWeight * natural
        } else {
            val radiusFromHeight = ((size.height * fit) - minLine) / AboveHorizon
            radius = min(radiusFromWidth, radiusFromHeight).coerceAtLeast(0f)
            lineStroke = minLine
        }
        val contentHeight = AboveHorizon * radius + lineStroke
        val top = (size.height - contentHeight) / 2f
        val cx = size.width / 2f
        val horizonY = top + AboveHorizon * radius

        drawArc(
            color = color,
            startAngle = 180f,
            sweepAngle = 180f,
            useCenter = true,
            topLeft = Offset(cx - radius, horizonY - radius),
            size = Size(radius * 2f, radius * 2f),
        )
        val lineY = horizonY + lineStroke * 0.25f
        val overhang = LineOverhang * radius
        drawLine(
            color = color,
            start = Offset(cx - radius - overhang, lineY),
            end = Offset(cx + radius + overhang, lineY),
            strokeWidth = lineStroke,
            cap = StrokeCap.Butt,
        )
        val shaftHalf = maxOf(ShaftHalfRatio * radius, minShaft / 2f)
        val headHalf = maxOf(HeadHalfRatio * radius, shaftHalf * 2.5f)
        drawPath(
            path = sunArrowPath(
                cx = cx,
                top = top,
                rising = rising,
                headLength = HeadLen * radius,
                shaftLength = ShaftLen * radius,
                shaftHalf = shaftHalf,
                headHalf = headHalf,
            ),
            color = color,
        )
    }
}

private fun sunArrowPath(
    cx: Float,
    top: Float,
    rising: Boolean,
    headLength: Float,
    shaftLength: Float,
    shaftHalf: Float,
    headHalf: Float,
): Path {
    return Path().apply {
        if (rising) {
            val baseY = top + headLength
            val tailY = baseY + shaftLength
            moveTo(cx, top)
            lineTo(cx + headHalf, baseY)
            lineTo(cx + shaftHalf, baseY)
            lineTo(cx + shaftHalf, tailY)
            lineTo(cx - shaftHalf, tailY)
            lineTo(cx - shaftHalf, baseY)
            lineTo(cx - headHalf, baseY)
        } else {
            val baseY = top + shaftLength
            val tipY = baseY + headLength
            moveTo(cx - shaftHalf, top)
            lineTo(cx + shaftHalf, top)
            lineTo(cx + shaftHalf, baseY)
            lineTo(cx + headHalf, baseY)
            lineTo(cx, tipY)
            lineTo(cx - headHalf, baseY)
            lineTo(cx - shaftHalf, baseY)
        }
        close()
    }
}
