package app.smartdisplay.expiration

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.smartdisplay.notification.ExpirationNotificationItem

private val ExpiredColor = Color(0xFFFF5C5C)
private val SoonColor = Color(0xFFFF9A3C)
private val NormalColor = Color(0xFFE6E6E6)
private val InkColor = Color(0xFF1A1A1A)
private val NameColor = Color(0xFF222222)
private val HintColor = Color(0xFF666666)

@Composable
fun ExpirationNotificationContent(
    items: List<ExpirationNotificationItem>,
    fallbackBody: String,
    modifier: Modifier = Modifier,
) {
    if (items.isEmpty()) {
        Text(
            text = fallbackBody,
            color = Color(0xFF333333),
            fontSize = 26.sp,
            lineHeight = 38.sp,
            modifier = modifier.verticalScroll(rememberScrollState()),
        )
        return
    }
    Column(modifier = modifier) {
        Text(
            text = "以下の賞味期限が近づいています。",
            color = NameColor,
            fontSize = 22.sp,
            lineHeight = 32.sp,
        )
        Spacer(modifier = Modifier.height(14.dp))
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            items.forEach { item ->
                ExpirationNoticeRow(item)
            }
        }
    }
}

@Composable
private fun ExpirationNoticeRow(item: ExpirationNotificationItem) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(Color(0xFFF4F4F4))
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = item.name,
                color = NameColor,
                fontSize = 20.sp,
                fontWeight = FontWeight.Medium,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = "${item.quantity}個　${item.dateLabel}",
                color = HintColor,
                fontSize = 15.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Spacer(modifier = Modifier.width(12.dp))
        NoticeRemainingLabel(item.remainingDays)
    }
}

@Composable
private fun NoticeRemainingLabel(days: Int) {
    val urgency = expirationUrgency(days)
    val background = when (urgency) {
        ExpirationUrgency.Expired -> ExpiredColor
        ExpirationUrgency.Soon -> SoonColor
        ExpirationUrgency.Normal -> NormalColor
    }
    Column(
        modifier = Modifier
            .widthIn(min = 96.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(background)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        when {
            days < 0 -> {
                NoticeNumber("${expirationDigits(days)}日")
                NoticeCaption("超過")
            }
            days == 0 -> {
                Text(
                    text = "今日",
                    color = InkColor,
                    fontSize = 24.sp,
                    fontWeight = FontWeight.SemiBold,
                    textAlign = TextAlign.Center,
                    maxLines = 1,
                )
            }
            else -> {
                NoticeCaption("あと")
                NoticeNumber("${expirationDigits(days)}日")
            }
        }
    }
}

@Composable
private fun NoticeNumber(text: String) {
    Text(
        text = text,
        color = InkColor,
        fontSize = 28.sp,
        fontWeight = FontWeight.SemiBold,
        textAlign = TextAlign.Center,
        maxLines = 1,
    )
}

@Composable
private fun NoticeCaption(text: String) {
    Text(
        text = text,
        color = InkColor,
        fontSize = 13.sp,
        fontWeight = FontWeight.Medium,
        textAlign = TextAlign.Center,
        maxLines = 1,
    )
}
