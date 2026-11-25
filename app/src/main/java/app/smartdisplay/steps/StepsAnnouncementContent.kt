package app.smartdisplay.steps

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
fun StepsAnnouncementContent(
    todaySteps: Long,
    weekSteps: Long,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        WalkingIcon(
            color = Color(0xFF1A73E8),
            modifier = Modifier.size(132.dp),
        )
        Spacer(modifier = Modifier.height(20.dp))
        Text(
            text = "歩数計",
            color = Color(0xFF111111),
            fontSize = 40.sp,
            fontWeight = FontWeight.SemiBold,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(modifier = Modifier.height(36.dp))
        StepReadout(
            label = "今日",
            steps = todaySteps,
        )
        Spacer(modifier = Modifier.height(28.dp))
        StepReadout(
            label = "今週",
            steps = weekSteps,
        )
    }
}

@Composable
private fun StepReadout(
    label: String,
    steps: Long,
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = label,
            color = Color(0xFF666666),
            fontSize = 22.sp,
            fontWeight = FontWeight.Medium,
            textAlign = TextAlign.Center,
        )
        Text(
            text = formatStepCount(steps),
            color = Color(0xFF111111),
            fontSize = 56.sp,
            fontWeight = FontWeight.SemiBold,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 4.dp),
        )
    }
}

@Composable
private fun WalkingIcon(
    color: Color,
    modifier: Modifier = Modifier,
) {
    Canvas(modifier = modifier) {
        val stroke = Stroke(
            width = size.minDimension * 0.055f,
            cap = StrokeCap.Round,
            join = StrokeJoin.Round,
        )
        fun point(x: Float, y: Float) = Offset(size.width * x, size.height * y)
        drawCircle(
            color = color,
            radius = size.minDimension * 0.09f,
            center = point(0.56f, 0.16f),
            style = stroke,
        )
        drawLine(color, point(0.54f, 0.27f), point(0.46f, 0.52f), stroke.width, StrokeCap.Round)
        drawLine(color, point(0.52f, 0.34f), point(0.28f, 0.46f), stroke.width, StrokeCap.Round)
        drawLine(color, point(0.52f, 0.34f), point(0.74f, 0.40f), stroke.width, StrokeCap.Round)
        drawLine(color, point(0.46f, 0.52f), point(0.26f, 0.78f), stroke.width, StrokeCap.Round)
        drawLine(color, point(0.46f, 0.52f), point(0.66f, 0.68f), stroke.width, StrokeCap.Round)
        drawLine(color, point(0.66f, 0.68f), point(0.80f, 0.84f), stroke.width, StrokeCap.Round)
    }
}
