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
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

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
