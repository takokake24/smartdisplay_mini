package app.smartdisplay.media

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.smartdisplay.R
import app.smartdisplay.ui.Panel

/**
 * 通知のメディア操作に出ている再生を、この画面から操作する。
 * 通知へのアクセスを許可していないときは、許可の案内を出す。
 */
@Composable
fun MusicContent(
    onTapSound: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val systemMedia = rememberSystemMedia()
    val sessions by systemMedia.sessions.collectAsState()
    val listenerEnabled by systemMedia.listenerEnabled.collectAsState()
    var selectedServiceId by remember { mutableStateOf<String?>(null) }
    val current = sessions.firstOrNull { it.id == selectedServiceId }
        ?: sessions.firstOrNull { it.playing }
        ?: sessions.firstOrNull()

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(top = 28.dp),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            if (!listenerEnabled) {
                AccessButton(
                    onClick = {
                        onTapSound()
                        systemMedia.openAccessSettings()
                    },
                )
            } else if (sessions.isNotEmpty()) {
                ServiceRow(
                    sessions = sessions,
                    selectedId = current?.id,
                    onSelect = { id ->
                        onTapSound()
                        selectedServiceId = id
                    },
                )
            }
            Text(
                text = "メディア",
                color = Color.White,
                fontSize = 30.sp,
                fontWeight = FontWeight.Medium,
            )
            PlaybackPanel(system = current)
        }
        val playing = current?.playing == true
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 14.dp)
                .height(112.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ModeButton(
                icon = R.drawable.ic_player_shuffle,
                description = "シャッフル",
                selected = current?.shuffle == true,
                enabled = current?.canShuffle == true,
                onClick = {
                    if (current != null) {
                        onTapSound()
                        systemMedia.setShuffle(current.id, !current.shuffle)
                    }
                },
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight(),
            )
            TransportButton(
                icon = R.drawable.ic_player_previous,
                description = "前へ",
                enabled = current?.canPrevious == true,
                onClick = {
                    if (current != null) {
                        onTapSound()
                        systemMedia.previous(current.id)
                    }
                },
                modifier = Modifier
                    .weight(0.8f)
                    .fillMaxHeight(),
            )
            TransportButton(
                icon = if (playing) R.drawable.ic_player_pause else R.drawable.ic_player_play,
                description = if (playing) "一時停止" else "再生",
                enabled = current != null,
                primary = true,
                onClick = {
                    if (current != null) {
                        onTapSound()
                        if (playing) {
                            systemMedia.pause(current.id)
                        } else {
                            systemMedia.play(current.id)
                        }
                    }
                },
                modifier = Modifier
                    .weight(1.15f)
                    .fillMaxHeight(),
            )
            TransportButton(
                icon = R.drawable.ic_player_next,
                description = "次へ",
                enabled = current?.canNext == true,
                onClick = {
                    if (current != null) {
                        onTapSound()
                        systemMedia.next(current.id)
                    }
                },
                modifier = Modifier
                    .weight(0.8f)
                    .fillMaxHeight(),
            )
            ModeButton(
                icon = R.drawable.ic_player_repeat,
                description = "全曲ループ",
                selected = current?.repeatAll == true,
                enabled = current?.canRepeat == true,
                onClick = {
                    if (current != null) {
                        onTapSound()
                        systemMedia.setRepeatAll(current.id, !current.repeatAll)
                    }
                },
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight(),
            )
        }
    }
}

@Composable
private fun PlaybackPanel(system: SystemMedia?) {
    val stateLabel = when {
        system == null -> "停止中"
        system.playing -> "再生中"
        else -> "一時停止"
    }
    val headline = if (system == null) {
        "再生中のメディアはありません"
    } else {
        system.title.ifBlank { system.label }
    }
    val detail = if (system == null) {
        "通知に出ている再生を操作できます"
    } else {
        system.artist.ifBlank { system.label }
    }
    Panel(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ArtworkFrame(
                artwork = system?.artwork,
                modifier = Modifier.size(96.dp),
            )
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = stateLabel,
                    color = Color.White.copy(alpha = 0.55f),
                    fontSize = 16.sp,
                )
                Text(
                    text = headline,
                    color = Color.White,
                    fontSize = 28.sp,
                    lineHeight = 40.sp,
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier.padding(top = 6.dp),
                )
                Text(
                    text = detail,
                    color = Color.White.copy(alpha = 0.75f),
                    fontSize = 18.sp,
                    modifier = Modifier.padding(top = 6.dp),
                )
            }
        }
    }
}

@Composable
private fun AccessButton(onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(88.dp)
            .clip(RoundedCornerShape(22.dp))
            .background(Color.White.copy(alpha = 0.08f))
            .border(1.dp, Color.White.copy(alpha = 0.16f), RoundedCornerShape(22.dp))
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text = "通知へのアクセスを許可",
                color = Color.White,
                fontSize = 20.sp,
                fontWeight = FontWeight.Medium,
            )
            Text(
                text = "通知に出ている再生を操作できます",
                color = Color.White.copy(alpha = 0.55f),
                fontSize = 14.sp,
                modifier = Modifier.padding(top = 4.dp),
            )
        }
    }
}

@Composable
private fun ServiceRow(
    sessions: List<SystemMedia>,
    selectedId: String?,
    onSelect: (String) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(72.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        sessions.forEach { session ->
            ServiceTab(
                label = session.label,
                selected = session.id == selectedId,
                onClick = { onSelect(session.id) },
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight(),
            )
        }
    }
}

@Composable
private fun ServiceTab(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(22.dp))
            .background(if (selected) Color.White else Color.White.copy(alpha = 0.08f))
            .border(
                width = 1.dp,
                color = Color.White.copy(alpha = if (selected) 0.9f else 0.16f),
                shape = RoundedCornerShape(22.dp),
            )
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            color = if (selected) Color.Black else Color.White,
            fontSize = 20.sp,
            fontWeight = FontWeight.Medium,
            maxLines = 1,
            modifier = Modifier.padding(horizontal = 12.dp),
        )
    }
}

@Composable
private fun ModeButton(
    icon: Int,
    description: String,
    selected: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .alpha(if (enabled) 1f else 0.4f)
            .clip(RoundedCornerShape(22.dp))
            .background(if (selected) Color.White else Color.White.copy(alpha = 0.08f))
            .border(
                width = 1.dp,
                color = Color.White.copy(alpha = if (selected) 0.9f else 0.16f),
                shape = RoundedCornerShape(22.dp),
            )
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            painter = painterResource(icon),
            contentDescription = description,
            tint = if (selected) Color.Black else Color.White,
            modifier = Modifier.size(36.dp),
        )
    }
}

@Composable
private fun TransportButton(
    icon: Int,
    description: String,
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    primary: Boolean = false,
) {
    Box(
        modifier = modifier
            .alpha(if (enabled) 1f else 0.4f)
            .clip(RoundedCornerShape(22.dp))
            .background(if (primary) Color.White else Color.White.copy(alpha = 0.08f))
            .border(
                1.dp,
                Color.White.copy(alpha = if (primary) 0.9f else 0.16f),
                RoundedCornerShape(22.dp),
            )
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            painter = painterResource(icon),
            contentDescription = description,
            tint = if (primary) Color.Black else Color.White,
            modifier = Modifier.size(if (primary) 48.dp else 40.dp),
        )
    }
}
