package app.smartdisplay.audio

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.smartdisplay.jihou.HourlyChimeController
import app.smartdisplay.notification.NotificationCenter
import app.smartdisplay.speech.AnnouncementGate

private val TileShape = RoundedCornerShape(20.dp)

@Composable
fun SoundTestPage(
    notifications: NotificationCenter,
    chimeController: HourlyChimeController,
    announcementGate: AnnouncementGate,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val player = remember(context) { SoundTestPlayer(context.applicationContext) }
    val clips = remember { soundTestClips() }
    val trackId by player.trackId.collectAsState()
    val playing by player.playing.collectAsState()
    val speaking by announcementGate.speechActive.collectAsState()
    val current = clips.firstOrNull { it.resId == trackId }
    DisposableEffect(notifications, chimeController, player) {
        notifications.setSoundTestHold(true)
        chimeController.holdSoundTest(true)
        onDispose {
            player.stop()
            notifications.setSoundTestHold(false)
            chimeController.holdSoundTest(false)
        }
    }
    LaunchedEffect(speaking) {
        player.setSpeechActive(speaking)
    }
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(top = 28.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(
            text = "サウンドテスト",
            color = Color.White,
            fontSize = 30.sp,
            fontWeight = FontWeight.Medium,
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = current?.title ?: "停止中",
                color = Color.White,
                fontSize = 20.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            SoundTestIconButton(
                description = "最初から",
                enabled = current != null,
                onClick = {
                    if (current != null) {
                        player.restart()
                    }
                },
            ) {
                RestartIcon(color = Color.White.copy(alpha = if (current != null) 1f else 0.35f))
            }
            SoundTestIconButton(
                description = if (playing) "一時停止" else "再生",
                enabled = current != null,
                onClick = {
                    if (current != null) {
                        player.togglePause()
                    }
                },
            ) {
                if (playing) {
                    PauseIcon(color = Color.White.copy(alpha = if (current != null) 1f else 0.35f))
                } else {
                    PlayIcon(color = Color.White.copy(alpha = if (current != null) 1f else 0.35f))
                }
            }
        }
        LazyColumn(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            clips.groupBy { it.group }.forEach { (group, items) ->
                item(key = "group-$group") {
                    Text(
                        text = group,
                        color = Color.White.copy(alpha = 0.7f),
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Medium,
                    )
                }
                items.chunked(3).forEachIndexed { index, row ->
                    item(key = "$group-$index") {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            row.forEach { clip ->
                                SoundTestTile(
                                    title = clip.title,
                                    selected = clip.resId == trackId,
                                    onClick = {
                                        player.play(clip.resId, clip.playbackVolume)
                                    },
                                    modifier = Modifier
                                        .weight(1f)
                                        .height(84.dp),
                                )
                            }
                            repeat(3 - row.size) {
                                Spacer(modifier = Modifier.weight(1f))
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SoundTestTile(
    title: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val border = if (selected) Color.White else Color.White.copy(alpha = 0.16f)
    val fill = if (selected) Color.White.copy(alpha = 0.2f) else Color.White.copy(alpha = 0.08f)
    Box(
        modifier = modifier
            .clip(TileShape)
            .background(fill)
            .border(if (selected) 2.dp else 1.dp, border, TileShape)
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = title,
            color = Color.White,
            fontSize = 16.sp,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
            textAlign = TextAlign.Center,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun SoundTestIconButton(
    description: String,
    enabled: Boolean,
    onClick: () -> Unit,
    icon: @Composable () -> Unit,
) {
    val alpha = if (enabled) 1f else 0.35f
    Box(
        modifier = Modifier
            .size(56.dp)
            .clip(RoundedCornerShape(14.dp))
            .border(1.dp, Color.White.copy(alpha = 0.35f * alpha), RoundedCornerShape(14.dp))
            .clickable(enabled = enabled, onClick = onClick)
            .semantics { contentDescription = description },
        contentAlignment = Alignment.Center,
    ) {
        Box(modifier = Modifier.size(26.dp), content = { icon() })
    }
}

@Composable
private fun PlayIcon(color: Color) {
    Canvas(modifier = Modifier.fillMaxSize()) {
        val path = Path().apply {
            moveTo(size.width * 0.26f, size.height * 0.14f)
            lineTo(size.width * 0.26f, size.height * 0.86f)
            lineTo(size.width * 0.86f, size.height * 0.5f)
            close()
        }
        drawPath(path, color)
    }
}

@Composable
private fun PauseIcon(color: Color) {
    Canvas(modifier = Modifier.fillMaxSize()) {
        val barWidth = size.width * 0.22f
        val barHeight = size.height * 0.72f
        val top = (size.height - barHeight) / 2f
        val gap = size.width * 0.16f
        val left = (size.width - barWidth * 2f - gap) / 2f
        drawRoundRect(
            color = color,
            topLeft = Offset(left, top),
            size = Size(barWidth, barHeight),
            cornerRadius = CornerRadius(barWidth * 0.3f),
        )
        drawRoundRect(
            color = color,
            topLeft = Offset(left + barWidth + gap, top),
            size = Size(barWidth, barHeight),
            cornerRadius = CornerRadius(barWidth * 0.3f),
        )
    }
}

@Composable
private fun RestartIcon(color: Color) {
    Canvas(modifier = Modifier.fillMaxSize()) {
        val sx = size.width / 24f
        val sy = size.height / 24f
        fun x(value: Float) = value * sx
        fun y(value: Float) = value * sy
        val path = Path().apply {
            moveTo(x(12f), y(5f))
            lineTo(x(12f), y(1f))
            lineTo(x(7f), y(6f))
            lineTo(x(12f), y(11f))
            lineTo(x(12f), y(7f))
            cubicTo(x(15.31f), y(7f), x(18f), y(9.69f), x(18f), y(13f))
            cubicTo(x(18f), y(16.31f), x(15.31f), y(19f), x(12f), y(19f))
            cubicTo(x(8.69f), y(19f), x(6f), y(16.31f), x(6f), y(13f))
            lineTo(x(4f), y(13f))
            cubicTo(x(4f), y(17.42f), x(7.58f), y(21f), x(12f), y(21f))
            cubicTo(x(16.42f), y(21f), x(20f), y(17.42f), x(20f), y(13f))
            cubicTo(x(20f), y(8.58f), x(16.42f), y(5f), x(12f), y(5f))
            close()
        }
        drawPath(path, color)
    }
}
