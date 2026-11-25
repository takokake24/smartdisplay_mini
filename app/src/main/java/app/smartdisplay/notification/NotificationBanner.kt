package app.smartdisplay.notification

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale

@Composable
fun NotificationBanner(
    notification: AppNotification,
    onDismiss: () -> Unit,
    queueProgress: NotificationQueueProgress? = null,
    dismissible: Boolean = true,
    modifier: Modifier = Modifier,
) {
    val appIcon = notification.appIcon
    Column(
        modifier
            .fillMaxHeight()
            .background(Color.White)
            .padding(horizontal = 28.dp, vertical = 24.dp),
    ) {
        Box(modifier = Modifier.fillMaxWidth()) {
            if (appIcon != null) {
                DeviceAppIcon(
                    icon = appIcon,
                    contentDescription = notification.sourceLabel.ifBlank { null },
                )
            }
            if (queueProgress != null || dismissible) {
                Row(
                    modifier = Modifier.align(Alignment.TopEnd),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    if (queueProgress != null) {
                        Text(
                            text = queueProgress.label,
                            color = Color(0xFF444444),
                            fontSize = 22.sp,
                            fontFamily = FontFamily.SansSerif,
                            fontWeight = FontWeight.Medium,
                        )
                    }
                    if (dismissible) {
                        Box(
                            modifier = Modifier
                                .size(48.dp)
                                .clip(CircleShape)
                                .background(Color(0x11000000))
                                .clickable(onClick = onDismiss),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(
                                text = "×",
                                color = Color(0xFF222222),
                                fontSize = 30.sp,
                                fontFamily = FontFamily.SansSerif,
                                fontWeight = FontWeight.Medium,
                            )
                        }
                    }
                }
            }
        }
        Spacer(modifier = Modifier.height(if (appIcon != null) 28.dp else 12.dp))
        Text(
            text = notification.title,
            color = Color(0xFF111111),
            fontSize = 36.sp,
            lineHeight = 52.sp,
            fontWeight = FontWeight.SemiBold,
        )
        Spacer(modifier = Modifier.height(14.dp))
        Text(
            text = notification.body,
            color = Color(0xFF333333),
            fontSize = 26.sp,
            lineHeight = 38.sp,
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth(),
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun DeviceAppIcon(
    icon: Bitmap,
    contentDescription: String?,
    modifier: Modifier = Modifier,
) {
    val image = remember(icon) { icon.asImageBitmap() }
    Box(
        modifier = modifier
            .fillMaxWidth()
            .padding(top = 8.dp),
        contentAlignment = Alignment.Center,
    ) {
        Image(
            bitmap = image,
            contentDescription = contentDescription,
            modifier = Modifier
                .size(128.dp)
                .clip(RoundedCornerShape(28.dp)),
            contentScale = ContentScale.Fit,
        )
    }
}

@Composable
fun NotificationHistoryPanel(
    items: List<AppNotification>,
    onItemClick: (AppNotification) -> Unit,
    onClearAll: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val timeFormatter = remember {
        DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT).withLocale(Locale.getDefault())
    }
    Column(
        modifier
            .fillMaxSize()
            .padding(top = 28.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "通知履歴",
                color = Color.White,
                fontSize = 30.sp,
                fontWeight = FontWeight.Medium,
            )
            // 空のときも同じ領域を確保し、見出し位置が動かないようにする
            Text(
                text = "すべて消す",
                color = Color.White.copy(alpha = 0.8f),
                fontSize = 20.sp,
                modifier = Modifier
                    .alpha(if (items.isNotEmpty()) 1f else 0f)
                    .clip(RoundedCornerShape(14.dp))
                    .background(Color.White.copy(alpha = if (items.isNotEmpty()) 0.12f else 0f))
                    .clickable(enabled = items.isNotEmpty(), onClick = onClearAll)
                    .padding(horizontal = 14.dp, vertical = 10.dp),
            )
        }
        Spacer(modifier = Modifier.height(16.dp))
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            if (items.isEmpty()) {
                Text(
                    text = "通知はありません",
                    color = Color.White.copy(alpha = 0.6f),
                    fontSize = 20.sp,
                )
            } else {
                items.forEach { item ->
                    val alpha = if (item.read) 0.55f else 1f
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(22.dp))
                            .background(Color.White.copy(alpha = 0.08f))
                            .clickable { onItemClick(item) }
                            .padding(20.dp),
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                text = item.categoryLabel,
                                color = Color.White.copy(alpha = 0.55f * alpha),
                                fontSize = 15.sp,
                                fontWeight = FontWeight.Medium,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier
                                    .weight(1f)
                                    .padding(end = 12.dp),
                            )
                            Text(
                                text = item.createdAt.format(timeFormatter),
                                color = Color.White.copy(alpha = 0.5f * alpha),
                                fontSize = 15.sp,
                                fontWeight = FontWeight.Medium,
                            )
                        }
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.padding(top = 8.dp),
                        ) {
                            item.appIcon?.let { icon ->
                                Image(
                                    bitmap = icon.asImageBitmap(),
                                    contentDescription = null,
                                    modifier = Modifier
                                        .size(28.dp)
                                        .clip(RoundedCornerShape(6.dp)),
                                )
                            }
                            Text(
                                text = item.title,
                                color = Color.White.copy(alpha = alpha),
                                fontSize = 22.sp,
                                fontWeight = FontWeight.Medium,
                                modifier = Modifier.weight(1f),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        Text(
                            text = item.body,
                            color = Color.White.copy(alpha = 0.7f * alpha),
                            fontSize = 18.sp,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.padding(top = 8.dp),
                        )
                    }
                }
            }
        }
    }
}
