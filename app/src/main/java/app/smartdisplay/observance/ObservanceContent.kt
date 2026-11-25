package app.smartdisplay.observance

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.smartdisplay.jihou.upcomingObservances
import app.smartdisplay.ui.Panel
import java.time.LocalDate
import java.time.temporal.ChronoUnit

private val HintColor = Color.White.copy(alpha = 0.45f)
private val WeekdayLabels = listOf("月", "火", "水", "木", "金", "土", "日")

@Composable
fun ObservanceRows(
    today: LocalDate,
    onTapSound: () -> Unit,
) {
    var holidaysOnly by remember { mutableStateOf(false) }
    val rows = remember(today) { upcomingObservances(today) }
    val shown = if (holidaysOnly) rows.filter { it.holiday } else rows
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        ObservanceFilterChip(
            label = "すべて",
            selected = !holidaysOnly,
            onClick = {
                onTapSound()
                holidaysOnly = false
            },
        )
        ObservanceFilterChip(
            label = "祝日",
            selected = holidaysOnly,
            onClick = {
                onTapSound()
                holidaysOnly = true
            },
        )
    }
    shown.forEach { row ->
        ObservanceRow(
            name = row.name,
            date = row.date,
            today = today,
            holiday = row.holiday,
        )
    }
}

@Composable
private fun ObservanceFilterChip(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    Text(
        text = label,
        color = if (selected) Color.Black else Color.White.copy(alpha = 0.85f),
        fontSize = 18.sp,
        modifier = Modifier
            .clip(CircleShape)
            .background(if (selected) Color.White else Color.White.copy(alpha = 0.12f))
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 8.dp),
    )
}

@Composable
private fun ObservanceRow(
    name: String,
    date: LocalDate,
    today: LocalDate,
    holiday: Boolean,
) {
    val days = ChronoUnit.DAYS.between(today, date).coerceAtLeast(0L)
    Panel(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Row(
                modifier = Modifier.weight(1f),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = name,
                    color = Color.White,
                    fontSize = 22.sp,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                if (holiday) {
                    Spacer(modifier = Modifier.width(8.dp))
                    HolidayLabel()
                }
            }
            Spacer(modifier = Modifier.width(16.dp))
            HintText("次は")
            Spacer(modifier = Modifier.width(6.dp))
            ValueText(
                text = observanceDateLabel(date, today),
                tabularFigures = true,
            )
            Spacer(modifier = Modifier.width(10.dp))
            Box(
                modifier = Modifier.width(CountdownColumnWidth),
                contentAlignment = Alignment.CenterEnd,
            ) {
                if (days == 0L) {
                    ValueText("今日")
                } else {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        HintText("あと")
                        Spacer(modifier = Modifier.width(6.dp))
                        ValueText("${groupedDigits(days)}日")
                    }
                }
            }
        }
    }
}

@Composable
private fun HolidayLabel() {
    Text(
        text = "祝日",
        color = Color.White,
        fontSize = 13.sp,
        fontWeight = FontWeight.Medium,
        maxLines = 1,
        modifier = Modifier
            .clip(CircleShape)
            .background(Color(0xFFE53935))
            .padding(horizontal = 8.dp, vertical = 2.dp),
    )
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
private fun ValueText(
    text: String,
    modifier: Modifier = Modifier,
    tabularFigures: Boolean = false,
) {
    Text(
        text = text,
        color = Color.White,
        fontSize = 22.sp,
        fontWeight = FontWeight.Medium,
        maxLines = 1,
        style = if (tabularFigures) {
            LocalTextStyle.current.merge(TextStyle(fontFeatureSettings = "tnum"))
        } else {
            LocalTextStyle.current
        },
        modifier = modifier,
    )
}

private val CountdownColumnWidth = 148.dp

private fun observanceDateLabel(date: LocalDate, today: LocalDate): String {
    val weekday = WeekdayLabels[date.dayOfWeek.value - 1]
    val month = date.monthValue.toString().padStart(2, '0')
    val day = date.dayOfMonth.toString().padStart(2, '0')
    val monthDay = "$month/$day（$weekday）"
    return if (date.year > today.year) {
        "${date.year}/$monthDay"
    } else {
        monthDay
    }
}

private fun groupedDigits(count: Long): String {
    val digits = count.toString()
    if (digits.length < 4) {
        return digits
    }
    return digits.reversed().chunked(3).joinToString(",").reversed()
}
