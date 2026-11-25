package app.smartdisplay.spotify

import android.content.ActivityNotFoundException
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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import app.smartdisplay.R
import app.smartdisplay.media.SystemMedia
import app.smartdisplay.media.rememberSystemMedia
import app.smartdisplay.ui.Panel
import app.smartdisplay.util.InternetConnectivity
import app.smartdisplay.util.showOsAlert
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@Composable
fun MusicContent(
    onTapSound: () -> Unit,
    onErrorSound: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val appContext = LocalContext.current.applicationContext
    val scope = rememberCoroutineScope()
    val player = remember(appContext) { SpotifyPlayer(appContext) }
    val connected by SpotifySession.connected.collectAsState()
    val linking by SpotifySession.linking.collectAsState()
    var output by remember { mutableStateOf(SpotifySession.output(appContext)) }
    var playlist by remember { mutableStateOf(SpotifySession.playlist(appContext)) }
    var playback by remember { mutableStateOf<MusicPlayback?>(null) }
    var knownDevices by remember { mutableStateOf<List<SpotifyDevice>>(emptyList()) }
    var catalog by remember { mutableStateOf<List<SpotifyPlaylistItem>>(emptyList()) }
    var extraDevice by remember { mutableStateOf<SpotifyDevice?>(null) }
    var extraPlaylist by remember { mutableStateOf<SpotifyPlaylistItem?>(null) }
    var showDevices by remember { mutableStateOf(false) }
    var showPlaylists by remember { mutableStateOf(false) }
    var shuffleOn by remember { mutableStateOf(SpotifySession.shuffleOn(appContext)) }
    var repeatAll by remember { mutableStateOf(SpotifySession.repeatAll(appContext)) }
    var busy by remember { mutableStateOf(false) }
    val busyState = rememberUpdatedState(busy)
    val systemMedia = rememberSystemMedia()
    val systemSessions by systemMedia.sessions.collectAsState()
    val listenerEnabled by systemMedia.listenerEnabled.collectAsState()
    val sessionsReady by systemMedia.sessionsReady.collectAsState()
    var selectedServiceId by remember { mutableStateOf<String?>(null) }
    var pickedPlayingService by remember { mutableStateOf(false) }
    val external = systemSessions.firstOrNull { it.id == selectedServiceId }
    val spotifyMode = external == null
    val currentExtraDevice = extraDevice?.takeUnless { player.isCastDevice(it) }
    val listedOthers = player.otherDevices(knownDevices)
    val otherDeviceChoices = if (
        currentExtraDevice != null &&
        listedOthers.none { it.id == currentExtraDevice.id }
    ) {
        listedOthers + currentExtraDevice
    } else {
        listedOthers
    }
    val activePlayback = playback
    val activePreset = activePlayback?.let { player.outputFor(it, knownDevices) }
    val playbackDeviceId = activePlayback?.deviceId
    val thisDeviceSelected = extraDevice == null && (
        activePlayback == null ||
            activePreset == MusicOutput.ThisDevice ||
            (activePreset == null && playbackDeviceId.isNullOrBlank())
    )
    val playingAway = activePlayback != null && !thisDeviceSelected && extraDevice == null
    val destinationLabel = currentExtraDevice?.name?.ifBlank { null }
        ?: activePlayback?.deviceName?.takeIf { playingAway }?.ifBlank { null }
        ?: MusicOutput.ThisDevice.label
    val showDevicePicker = otherDeviceChoices.size >= 4

    LaunchedEffect(showDevicePicker) {
        if (!showDevicePicker) {
            showDevices = false
        }
    }

    LaunchedEffect(Unit) {
        SpotifySession.attach(appContext)
    }

    LaunchedEffect(sessionsReady) {
        if (!sessionsReady || pickedPlayingService) {
            return@LaunchedEffect
        }
        pickedPlayingService = true
        val playing = systemMedia.sessions.value.firstOrNull { it.playing }
        if (playing != null) {
            selectedServiceId = playing.id
        }
    }

    fun syncOutput(latest: MusicPlayback) {
        if (latest.deviceId.isNullOrBlank() && latest.deviceName.isNullOrBlank()) {
            return
        }
        val listed = knownDevices.firstOrNull { it.id == latest.deviceId }
        if (listed != null && player.isCastDevice(listed)) {
            extraDevice = null
            return
        }
        if (player.outputFor(latest, knownDevices) == MusicOutput.ThisDevice) {
            extraDevice = null
            if (output != MusicOutput.ThisDevice) {
                output = MusicOutput.ThisDevice
                SpotifySession.saveOutput(appContext, MusicOutput.ThisDevice)
            }
            return
        }
        val id = latest.deviceId ?: return
        val found = listed
        extraDevice = found ?: SpotifyDevice(
            id = id,
            name = latest.deviceName?.ifBlank { null } ?: "その他の端末",
            type = "",
            active = true,
            restricted = false,
            privateSession = false,
        )
    }

    fun syncPlaylist(latest: MusicPlayback) {
        val uri = latest.contextUri ?: return
        if (!uri.startsWith("spotify:playlist:")) {
            extraPlaylist = null
            playlist = null
            return
        }
        val named = catalog.firstOrNull { it.uri == uri }
        val side = when {
            named?.name == MusicPlaylist.SideA.label -> MusicPlaylist.SideA
            named?.name == MusicPlaylist.SideB.label -> MusicPlaylist.SideB
            SpotifySession.cachedPlaylistUri(appContext, MusicPlaylist.SideA) == uri -> MusicPlaylist.SideA
            SpotifySession.cachedPlaylistUri(appContext, MusicPlaylist.SideB) == uri -> MusicPlaylist.SideB
            else -> null
        }
        if (side != null) {
            extraPlaylist = null
            playlist = side
            SpotifySession.savePlaylist(appContext, side)
            if (named != null) {
                SpotifySession.savePlaylistUri(appContext, side, named.uri)
            }
            return
        }
        playlist = null
        extraPlaylist = named ?: SpotifyPlaylistItem(name = "その他のプレイリスト", uri = uri)
    }

    fun adoptPlayback(latest: MusicPlayback?) {
        playback = latest
        if (latest == null) {
            return
        }
        if (shuffleOn != latest.shuffle) {
            shuffleOn = latest.shuffle
            SpotifySession.saveShuffleOn(appContext, latest.shuffle)
        }
        if (repeatAll != latest.repeatAll) {
            repeatAll = latest.repeatAll
            SpotifySession.saveRepeatAll(appContext, latest.repeatAll)
        }
        syncOutput(latest)
        syncPlaylist(latest)
    }

    LaunchedEffect(connected) {
        if (!connected) {
            playback = null
            knownDevices = emptyList()
            catalog = emptyList()
            return@LaunchedEffect
        }
        val unresolved = mutableSetOf<String>()
        while (true) {
            val revision = player.playbackRevision()
            if (!busyState.value) {
                val latest = catching { player.playback() }
                val fetchedDevices = catching { player.devices() }
                if (fetchedDevices.isSuccess) {
                    knownDevices = fetchedDevices.getOrThrow()
                }
                val current = if (latest.isSuccess) latest.getOrNull() else null
                val uri = current?.contextUri
                val needsCatalog = catalog.isEmpty() || (
                    uri != null &&
                        uri.startsWith("spotify:playlist:") &&
                        catalog.none { it.uri == uri } &&
                        uri !in unresolved
                    )
                if (needsCatalog) {
                    val fetched = catching { player.playlists() }
                    if (fetched.isSuccess) {
                        catalog = fetched.getOrThrow()
                    }
                    if (!uri.isNullOrBlank()) {
                        unresolved += uri
                    }
                }
                if (
                    latest.isSuccess &&
                    player.playbackRevision() == revision &&
                    !busyState.value
                ) {
                    adoptPlayback(current)
                }
            }
            delay(2_000)
        }
    }

    fun operate(sound: Boolean = true, action: suspend SpotifyPlayer.() -> PlaybackRead) {
        if (busy) {
            return
        }
        if (sound) {
            onTapSound()
        }
        if (!spotifyConfigured) {
            showOsAlert("認証情報がありません")
            onErrorSound()
            return
        }
        if (!connected) {
            runCatching { SpotifySession.beginLogin(appContext) }
                .onFailure { error ->
                    showOsAlert(loginError(error))
                    onErrorSound()
                }
            return
        }
        busy = true
        scope.launch {
            try {
                val result = catching { player.action() }
                result.onSuccess { settled ->
                    if (settled is PlaybackRead.Value) {
                        adoptPlayback(settled.playback)
                    }
                }.onFailure { error ->
                    showOsAlert(error.message?.takeIf { it.isNotBlank() } ?: "操作に失敗しました")
                    if (InternetConnectivity.hasInternet()) {
                        onErrorSound()
                    }
                }
            } finally {
                busy = false
            }
        }
    }

    fun selectThisDevice() {
        onTapSound()
        showDevices = false
        extraDevice = null
        output = MusicOutput.ThisDevice
        SpotifySession.saveOutput(appContext, MusicOutput.ThisDevice)
        val current = playback
        if (current != null) {
            operate(sound = false) {
                movePlayback(MusicOutput.ThisDevice, null, current.playing)
            }
        }
    }

    fun selectOtherDevice(device: SpotifyDevice) {
        onTapSound()
        showDevices = false
        extraDevice = device
        val current = playback
        val selectedPlaylist = extraPlaylist
        val selected = playlist
        when {
            selectedPlaylist != null -> {
                operate(sound = false) { playUri(MusicOutput.ThisDevice, selectedPlaylist.uri, device.id) }
            }
            selected != null -> {
                operate(sound = false) { play(MusicOutput.ThisDevice, selected, device.id) }
            }
            current?.contextUri != null -> {
                val uri = current.contextUri
                operate(sound = false) { playUri(MusicOutput.ThisDevice, uri, device.id) }
            }
            current != null -> {
                operate(sound = false) { movePlayback(MusicOutput.ThisDevice, device.id, current.playing) }
            }
        }
    }

    fun switchMode(
        next: Boolean,
        save: (Boolean) -> Unit,
        send: suspend SpotifyPlayer.(String, Boolean) -> PlaybackRead,
    ) {
        if (busy) {
            return
        }
        onTapSound()
        val previous = !next
        save(next)
        val deviceId = playback?.deviceId
        if (!connected || deviceId.isNullOrBlank()) {
            return
        }
        busy = true
        scope.launch {
            try {
                val result = catching { player.send(deviceId, next) }
                result.onSuccess { settled ->
                    if (settled is PlaybackRead.Value) {
                        adoptPlayback(settled.playback)
                    }
                }.onFailure { error ->
                    save(previous)
                    showOsAlert(error.message?.takeIf { it.isNotBlank() } ?: "操作に失敗しました")
                    if (InternetConnectivity.hasInternet()) {
                        onErrorSound()
                    }
                }
            } finally {
                busy = false
            }
        }
    }

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
            } else if (systemSessions.isNotEmpty()) {
                ServiceRow(
                    sessions = systemSessions,
                    selectedId = external?.id,
                    onSelect = { id ->
                        onTapSound()
                        selectedServiceId = id
                    },
                )
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "メディア",
                    color = Color.White,
                    fontSize = 30.sp,
                    fontWeight = FontWeight.Medium,
                )
                if (spotifyMode) {
                    HeaderAction(
                    label = when {
                        linking -> "接続中"
                        connected -> "切断"
                        else -> "接続"
                    },
                    enabled = !linking && !busy,
                    onClick = {
                        onTapSound()
                        if (connected) {
                            SpotifySession.disconnect(appContext)
                            playback = null
                            return@HeaderAction
                        }
                        runCatching { SpotifySession.beginLogin(appContext) }
                            .onFailure { error ->
                                showOsAlert(loginError(error))
                                onErrorSound()
                            }
                    },
                )
                }
            }
            if (spotifyMode) {
                Text(
                    text = when {
                        !spotifyConfigured -> "認証情報がありません"
                        linking -> "Spotifyに接続しています"
                        connected -> "接続済み"
                        else -> "未接続"
                    },
                    color = Color.White.copy(alpha = if (spotifyConfigured) 0.4f else 0.75f),
                    fontSize = 15.sp,
                )
                SectionLabel("再生先")
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(96.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    if (showDevicePicker) {
                        ChoiceButton(
                            label = destinationLabel,
                            selected = true,
                            enabled = !busy,
                            onClick = {
                                onTapSound()
                                showDevices = true
                            },
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxHeight(),
                        )
                    } else {
                        ChoiceButton(
                            label = MusicOutput.ThisDevice.label,
                            selected = thisDeviceSelected,
                            enabled = !busy,
                            onClick = { selectThisDevice() },
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxHeight(),
                        )
                        otherDeviceChoices.forEach { device ->
                            ChoiceButton(
                                label = device.name.ifBlank { "名称のない端末" },
                                selected = device.id == extraDevice?.id,
                                enabled = !busy,
                                onClick = { selectOtherDevice(device) },
                                modifier = Modifier
                                    .weight(1f)
                                    .fillMaxHeight(),
                            )
                        }
                    }
                }
                SectionLabel("プレイリスト")
                val otherPlaylists = catalog.filter { item ->
                    item.name != MusicPlaylist.SideA.label && item.name != MusicPlaylist.SideB.label
                }
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(96.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    MusicPlaylist.entries.forEach { option ->
                        ChoiceButton(
                            label = option.label,
                            selected = extraPlaylist == null && option == playlist,
                            enabled = !busy,
                            onClick = {
                                onTapSound()
                                extraPlaylist = null
                                playlist = option
                                SpotifySession.savePlaylist(appContext, option)
                                operate(sound = false) { play(MusicOutput.ThisDevice, option, extraDevice?.id) }
                            },
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxHeight(),
                        )
                    }
                    ChoiceButton(
                        label = "その他",
                        selected = extraPlaylist != null,
                        enabled = !busy && (otherPlaylists.isNotEmpty() || extraPlaylist != null),
                        onClick = {
                            onTapSound()
                            showPlaylists = true
                        },
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxHeight(),
                    )
                }
            }
            val spotifyArtwork = rememberSpotifyArtwork(
                player,
                if (spotifyMode) playback?.artworkUrl else null,
            )
            PlaybackPanel(
                playback = playback,
                artwork = external?.artwork ?: spotifyArtwork,
                outputLabel = destinationLabel,
                playlistLabel = extraPlaylist?.name ?: playlist?.label,
                connected = connected,
                system = external,
            )
        }
        val playing = if (external != null) external.playing else playback?.playing == true
        val shuffleShown = if (external != null) external.shuffle else playback?.shuffle ?: shuffleOn
        val repeatShown = if (external != null) external.repeatAll else playback?.repeatAll ?: repeatAll
        val canShuffle = external?.canShuffle ?: true
        val canRepeat = external?.canRepeat ?: true
        val canPrevious = external?.canPrevious ?: true
        val canNext = external?.canNext ?: true
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
                selected = shuffleShown,
                enabled = !busy && canShuffle,
                onClick = {
                    if (external != null) {
                        onTapSound()
                        systemMedia.setShuffle(external.id, !shuffleShown)
                    } else {
                        switchMode(
                        next = !shuffleShown,
                        save = { enabled ->
                            shuffleOn = enabled
                            SpotifySession.saveShuffleOn(appContext, enabled)
                            playback = playback?.copy(shuffle = enabled)
                        },
                            send = { deviceId, enabled -> setShuffle(deviceId, enabled) },
                        )
                    }
                },
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight(),
            )
            TransportButton(
                icon = R.drawable.ic_player_previous,
                description = "前へ",
                enabled = !busy && canPrevious,
                onClick = {
                    if (external != null) {
                        onTapSound()
                        systemMedia.previous(external.id)
                    } else {
                        operate { previous(MusicOutput.ThisDevice, extraDevice?.id) }
                    }
                },
                modifier = Modifier
                    .weight(0.8f)
                    .fillMaxHeight(),
            )
            TransportButton(
                icon = if (playing) R.drawable.ic_player_pause else R.drawable.ic_player_play,
                description = if (playing) "一時停止" else "再生",
                enabled = !busy && (external != null || playing || playlist != null || extraPlaylist != null),
                primary = true,
                onClick = {
                    if (external != null) {
                        onTapSound()
                        if (playing) {
                            systemMedia.pause(external.id)
                        } else {
                            systemMedia.play(external.id)
                        }
                    } else if (playing) {
                        operate { pause(MusicOutput.ThisDevice, extraDevice?.id) }
                    } else {
                        val selectedPlaylist = extraPlaylist
                        val selected = playlist
                        when {
                            selectedPlaylist != null -> {
                                operate { playUri(MusicOutput.ThisDevice, selectedPlaylist.uri, extraDevice?.id) }
                            }
                            selected != null -> {
                                operate { play(MusicOutput.ThisDevice, selected, extraDevice?.id) }
                            }
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
                enabled = !busy && canNext,
                onClick = {
                    if (external != null) {
                        onTapSound()
                        systemMedia.next(external.id)
                    } else {
                        operate { next(MusicOutput.ThisDevice, extraDevice?.id) }
                    }
                },
                modifier = Modifier
                    .weight(0.8f)
                    .fillMaxHeight(),
            )
            ModeButton(
                icon = R.drawable.ic_player_repeat,
                description = "全曲ループ",
                selected = repeatShown,
                enabled = !busy && canRepeat,
                onClick = {
                    if (external != null) {
                        onTapSound()
                        systemMedia.setRepeatAll(external.id, !repeatShown)
                    } else {
                        switchMode(
                        next = !repeatShown,
                        save = { enabled ->
                            repeatAll = enabled
                            SpotifySession.saveRepeatAll(appContext, enabled)
                            playback = playback?.copy(repeatAll = enabled)
                        },
                            send = { deviceId, enabled -> setRepeatAll(deviceId, enabled) },
                        )
                    }
                },
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight(),
            )
        }
    }
        if (showDevices) {
            val choices = otherDeviceChoices
            val labels = listOf(MusicOutput.ThisDevice.label) + choices.map { device ->
                device.name.ifBlank { "名称のない端末" }
            }
            val selected = listOf(thisDeviceSelected) + choices.map { device ->
                device.id == extraDevice?.id
            }
            PickerDialog(
                title = "再生先",
                items = labels.zip(selected),
                onSelect = { index ->
                    if (index == 0) {
                        selectThisDevice()
                    } else {
                        selectOtherDevice(choices[index - 1])
                    }
                },
                onDismiss = { showDevices = false },
            )
        }
        if (showPlaylists) {
            val listed = catalog.filter { item ->
                item.name != MusicPlaylist.SideA.label && item.name != MusicPlaylist.SideB.label
            }
            val current = extraPlaylist
            val choices = if (
                current != null &&
                listed.none { it.uri == current.uri } &&
                current.name != MusicPlaylist.SideA.label &&
                current.name != MusicPlaylist.SideB.label
            ) {
                listed + current
            } else {
                listed
            }
            PickerDialog(
                title = "その他のプレイリスト",
                items = choices.map { item ->
                    item.name to (item.uri == extraPlaylist?.uri)
                },
                onSelect = { index ->
                    onTapSound()
                    val item = choices[index]
                    showPlaylists = false
                    extraPlaylist = item
                    playlist = null
                    operate(sound = false) { playUri(MusicOutput.ThisDevice, item.uri, extraDevice?.id) }
                },
                onDismiss = { showPlaylists = false },
            )
        }
}

@Composable
private fun PlaybackPanel(
    playback: MusicPlayback?,
    artwork: ImageBitmap?,
    outputLabel: String,
    playlistLabel: String?,
    connected: Boolean,
    system: SystemMedia? = null,
) {
    val stateLabel = when {
        system != null -> if (system.playing) "再生中" else "一時停止"
        !spotifyConfigured -> "未設定"
        !connected -> "未接続"
        playback?.playing == true -> "再生中"
        playback != null -> "一時停止"
        else -> "停止中"
    }
    val headline = when {
        system != null -> system.title.ifBlank { system.label }
        else -> playback?.title?.takeIf { it.isNotBlank() } ?: when {
            !spotifyConfigured -> "認証情報がありません"
            !connected -> "Spotifyに接続してください"
            else -> "停止中"
        }
    }
    val detail = when {
        system != null -> system.artist.ifBlank { system.label }
            else -> playback?.artist?.takeIf { it.isNotBlank() } ?: when {
            !spotifyConfigured || !connected -> "接続すると、選んだ再生先で再生できます"
            playlistLabel != null -> "${outputLabel}で${playlistLabel}を再生します"
            else -> "プレイリストを選ぶと再生します"
        }
    }
    Panel(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ArtworkFrame(
                artwork = artwork,
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
                playback?.deviceName?.takeIf { system == null && it.isNotBlank() }?.let { deviceName ->
                    Text(
                        text = deviceName,
                        color = Color.White.copy(alpha = 0.45f),
                        fontSize = 16.sp,
                        modifier = Modifier.padding(top = 6.dp),
                    )
                }
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
    onSelect: (String?) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(72.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        ServiceTab(
            label = "Spotify",
            selected = selectedId == null,
            onClick = { onSelect(null) },
            modifier = Modifier
                .weight(1f)
                .fillMaxHeight(),
        )
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
private fun SectionLabel(text: String) {
    Text(
        text = text,
        color = Color.White.copy(alpha = 0.55f),
        fontSize = 16.sp,
    )
}

@Composable
private fun ChoiceButton(
    label: String,
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
        Text(
            text = label,
            color = if (selected) Color.Black else Color.White,
            fontSize = 20.sp,
            fontWeight = FontWeight.Medium,
            textAlign = TextAlign.Center,
            maxLines = 2,
            modifier = Modifier.padding(horizontal = 8.dp),
        )
    }
}

@Composable
private fun PickerDialog(
    title: String,
    items: List<Pair<String, Boolean>>,
    onSelect: (Int) -> Unit,
    onDismiss: () -> Unit,
) {
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth(0.72f)
                .widthIn(max = 720.dp)
                .heightIn(max = 560.dp)
                .clip(RoundedCornerShape(28.dp))
                .background(Color(0xFF161616))
                .border(1.dp, Color.White.copy(alpha = 0.14f), RoundedCornerShape(28.dp))
                .padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = title,
                color = Color.White,
                fontSize = 24.sp,
                fontWeight = FontWeight.Medium,
            )
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                items.forEachIndexed { index, (label, selected) ->
                    ChoiceButton(
                        label = label,
                        selected = selected,
                        enabled = true,
                        onClick = { onSelect(index) },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(72.dp),
                    )
                }
            }
        }
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

@Composable
private fun HeaderAction(
    label: String,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    Text(
        text = label,
        color = Color.White.copy(alpha = if (enabled) 0.85f else 0.4f),
        fontSize = 20.sp,
        modifier = Modifier
            .clip(CircleShape)
            .background(Color.White.copy(alpha = 0.12f))
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 10.dp),
    )
}

private suspend fun <T> catching(block: suspend () -> T): Result<T> {
    return try {
        Result.success(block())
    } catch (error: CancellationException) {
        throw error
    } catch (error: Exception) {
        Result.failure(error)
    }
}

private fun loginError(error: Throwable): String {
    if (error is ActivityNotFoundException) {
        return "ブラウザを開けませんでした"
    }
    return error.message?.takeIf { it.isNotBlank() } ?: "Spotifyに接続できませんでした"
}
