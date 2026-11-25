package app.smartdisplay.garbage

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.smartdisplay.ui.Panel
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.temporal.ChronoUnit

private val HintColor = Color.White.copy(alpha = 0.45f)

@Composable
fun GarbageDayRows(now: LocalDateTime) {
    val kinds = GarbageKind.entries.sortedWith(
        compareBy<GarbageKind> { nextCollectionDate(it, now) }.thenBy { it.ordinal },
    )
    kinds.forEach { kind ->
        GarbageDayRow(kind = kind, now = now)
    }
}

@Composable
private fun GarbageDayRow(
    kind: GarbageKind,
    now: LocalDateTime,
) {
    val next = nextCollectionDate(kind, now)
    val today = next == now.toLocalDate()
    Panel(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = kind.label,
                color = Color.White,
                fontSize = 22.sp,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            HintText("次は")
            Spacer(modifier = Modifier.width(6.dp))
            if (today) {
                ValueText("今日")
            } else {
                ValueText(garbageDateLabel(next))
                Spacer(modifier = Modifier.width(10.dp))
                HintText("あと")
                Spacer(modifier = Modifier.width(6.dp))
                ValueText("${daysUntil(now.toLocalDate(), next)}日")
            }
        }
    }
}

@Composable
private fun HintText(text: String) {
    Text(
        text = text,
        color = HintColor,
        fontSize = 15.sp,
        maxLines = 1,
    )
}

@Composable
private fun ValueText(text: String) {
    Text(
        text = text,
        color = Color.White,
        fontSize = 22.sp,
        fontWeight = FontWeight.Medium,
        maxLines = 1,
    )
}

private fun daysUntil(today: LocalDate, next: LocalDate): Long {
    return ChronoUnit.DAYS.between(today, next).coerceAtLeast(0L)
}
