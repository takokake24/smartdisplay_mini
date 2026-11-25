package app.smartdisplay.weather

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.min

private enum class WeatherIconKind {
    Clear,
    PartlyCloudy,
    Cloudy,
    Fog,
    Drizzle,
    Rain,
    Snow,
    Thunder,
}

private fun weatherIconKind(code: Int): WeatherIconKind {
    return when (code) {
        0 -> WeatherIconKind.Clear
        1, 2 -> WeatherIconKind.PartlyCloudy
        3 -> WeatherIconKind.Cloudy
        45, 48 -> WeatherIconKind.Fog
        51, 53, 55, 56, 57 -> WeatherIconKind.Drizzle
        61, 63, 65, 66, 67, 80, 81, 82 -> WeatherIconKind.Rain
        71, 73, 75, 77, 85, 86 -> WeatherIconKind.Snow
        95, 96, 99 -> WeatherIconKind.Thunder
        else -> WeatherIconKind.Cloudy
    }
}

@Composable
fun WeatherIcon(
    weatherCode: Int,
    modifier: Modifier = Modifier,
    size: Dp = 128.dp,
) {
    val kind = weatherIconKind(weatherCode)
    Canvas(modifier = modifier.size(size)) {
        val unit = min(this.size.width, this.size.height)
        when (kind) {
            WeatherIconKind.Clear -> drawClear(unit)
            WeatherIconKind.PartlyCloudy -> drawPartlyCloudy(unit)
            WeatherIconKind.Cloudy -> drawCloud(
                unit = unit,
                center = Offset(unit * 0.50f, unit * 0.52f),
                scale = 1f,
                color = Color(0xFF78909C),
            )
            WeatherIconKind.Fog -> {
                drawCloud(
                    unit = unit,
                    center = Offset(unit * 0.50f, unit * 0.42f),
                    scale = 0.88f,
                    color = Color(0xFF90A4AE),
                )
                drawFogLines(unit)
            }
            WeatherIconKind.Drizzle -> {
                drawCloud(
                    unit = unit,
                    center = Offset(unit * 0.50f, unit * 0.38f),
                    scale = 0.88f,
                    color = Color(0xFF90A4AE),
                )
                drawDrizzle(unit)
            }
            WeatherIconKind.Rain -> {
                drawCloud(
                    unit = unit,
                    center = Offset(unit * 0.50f, unit * 0.36f),
                    scale = 0.88f,
                    color = Color(0xFF78909C),
                )
                drawRain(unit)
            }
            WeatherIconKind.Snow -> {
                drawCloud(
                    unit = unit,
                    center = Offset(unit * 0.50f, unit * 0.36f),
                    scale = 0.88f,
                    color = Color(0xFF90A4AE),
                )
                drawSnow(unit)
            }
            WeatherIconKind.Thunder -> {
                drawCloud(
                    unit = unit,
                    center = Offset(unit * 0.50f, unit * 0.34f),
                    scale = 0.88f,
                    color = Color(0xFF607D8B),
                )
                drawThunder(unit)
            }
        }
    }
}

private fun DrawScope.drawClear(unit: Float) {
    val center = Offset(unit * 0.5f, unit * 0.5f)
    drawSun(center = center, radius = unit * 0.22f, rayLength = unit * 0.14f)
}

private fun DrawScope.drawPartlyCloudy(unit: Float) {
    drawSun(
        center = Offset(unit * 0.68f, unit * 0.34f),
        radius = unit * 0.16f,
        rayLength = unit * 0.11f,
        rayCount = 8,
    )
    drawCloud(
        unit = unit,
        center = Offset(unit * 0.46f, unit * 0.62f),
        scale = 0.92f,
        color = Color(0xFF90A4AE),
    )
}

private fun DrawScope.drawSun(
    center: Offset,
    radius: Float,
    rayLength: Float,
    rayCount: Int = 8,
) {
    val sun = Color(0xFFF5A623)
    val rayWidth = radius * 0.28f
    repeat(rayCount) { index ->
        rotate(degrees = index * (360f / rayCount), pivot = center) {
            drawRoundRect(
                color = sun,
                topLeft = Offset(center.x - rayWidth / 2f, center.y - radius - rayLength),
                size = Size(rayWidth, rayLength * 0.85f),
                cornerRadius = CornerRadius(rayWidth / 2f, rayWidth / 2f),
            )
        }
    }
    drawCircle(color = sun, radius = radius, center = center)
}

private fun DrawScope.drawCloud(
    unit: Float,
    center: Offset,
    scale: Float,
    color: Color,
) {
    val s = unit * scale
    val left = center.x - s * 0.38f
    val right = center.x + s * 0.38f
    val top = center.y - s * 0.18f
    val bottom = center.y + s * 0.22f
    val bodyHeight = bottom - top
    drawRoundRect(
        color = color,
        topLeft = Offset(left, top + bodyHeight * 0.35f),
        size = Size(right - left, bodyHeight * 0.65f),
        cornerRadius = CornerRadius(bodyHeight * 0.35f, bodyHeight * 0.35f),
    )
    drawCircle(color = color, radius = s * 0.18f, center = Offset(left + s * 0.20f, top + bodyHeight * 0.55f))
    drawCircle(color = color, radius = s * 0.24f, center = Offset(center.x - s * 0.02f, top + bodyHeight * 0.28f))
    drawCircle(color = color, radius = s * 0.17f, center = Offset(right - s * 0.18f, top + bodyHeight * 0.50f))
}

private fun DrawScope.drawFogLines(unit: Float) {
    val color = Color(0xFFB0BEC5)
    val y1 = unit * 0.78f
    val y2 = unit * 0.88f
    drawRoundRect(
        color = color,
        topLeft = Offset(unit * 0.18f, y1),
        size = Size(unit * 0.64f, unit * 0.045f),
        cornerRadius = CornerRadius(unit * 0.02f, unit * 0.02f),
    )
    drawRoundRect(
        color = color,
        topLeft = Offset(unit * 0.24f, y2),
        size = Size(unit * 0.52f, unit * 0.045f),
        cornerRadius = CornerRadius(unit * 0.02f, unit * 0.02f),
    )
}

private fun DrawScope.drawDrizzle(unit: Float) {
    val color = Color(0xFF42A5F5)
    val width = unit * 0.035f
    val height = unit * 0.12f
    listOf(0.34f, 0.50f, 0.66f).forEachIndexed { index, x ->
        val top = unit * (0.72f + if (index == 1) 0.03f else 0f)
        drawRoundRect(
            color = color,
            topLeft = Offset(unit * x, top),
            size = Size(width, height),
            cornerRadius = CornerRadius(width / 2f, width / 2f),
        )
    }
}

private fun DrawScope.drawRain(unit: Float) {
    val color = Color(0xFF1E88E5)
    listOf(0.34f, 0.50f, 0.66f).forEachIndexed { index, x ->
        val top = unit * (0.70f + if (index == 1) 0.03f else 0f)
        val path = Path().apply {
            moveTo(unit * x, top)
            lineTo(unit * x - unit * 0.04f, top + unit * 0.18f)
            lineTo(unit * x + unit * 0.03f, top + unit * 0.18f)
            close()
        }
        drawPath(path, color = color)
    }
}

private fun DrawScope.drawSnow(unit: Float) {
    val color = Color(0xFF81D4FA)
    listOf(
        Offset(unit * 0.34f, unit * 0.78f) to unit * 0.035f,
        Offset(unit * 0.50f, unit * 0.84f) to unit * 0.035f,
        Offset(unit * 0.66f, unit * 0.78f) to unit * 0.035f,
        Offset(unit * 0.42f, unit * 0.92f) to unit * 0.028f,
        Offset(unit * 0.58f, unit * 0.92f) to unit * 0.028f,
    ).forEach { (center, radius) ->
        drawCircle(color = color, radius = radius, center = center)
    }
}

private fun DrawScope.drawThunder(unit: Float) {
    val path = Path().apply {
        moveTo(unit * 0.56f, unit * 0.64f)
        lineTo(unit * 0.40f, unit * 0.64f)
        lineTo(unit * 0.36f, unit * 0.78f)
        lineTo(unit * 0.48f, unit * 0.78f)
        lineTo(unit * 0.38f, unit * 0.96f)
        lineTo(unit * 0.66f, unit * 0.72f)
        lineTo(unit * 0.52f, unit * 0.72f)
        close()
    }
    drawPath(path, color = Color(0xFFFFC107))
}
