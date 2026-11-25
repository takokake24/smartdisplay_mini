package app.smartdisplay.away

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.pm.PackageManager
import android.graphics.BitmapFactory
import android.os.Build
import android.os.SystemClock
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import app.smartdisplay.ui.Panel
import app.smartdisplay.util.showOsAlert
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.max

private val AlertTimeFormat = DateTimeFormatter.ofPattern("M/d H:mm", Locale.JAPANESE)
private val PhotoTimeFormat = DateTimeFormatter.ofPattern("M/d H:mm:ss", Locale.JAPANESE)
private val ActionShape = RoundedCornerShape(14.dp)

@Composable
fun AwayAlertListPage(
    archive: AwayAlertArchive,
    onOpenPhotos: (String) -> Unit,
    onOpenSettings: () -> Unit,
    onTapSound: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val records by archive.records.collectAsState()
    val revision by archive.revision.collectAsState()
    var hasCamera by remember { mutableStateOf(hasCameraPermission(context)) }
    var cameraDenied by remember { mutableStateOf(false) }
    var pendingDeleteId by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val shotsByAlert = remember(records, revision) {
        records.associate { it.id to archive.photos(it.id) }
    }
    val cameraLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        hasCamera = granted
        cameraDenied = !granted
    }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                hasCamera = hasCameraPermission(context)
            }
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(top = 28.dp)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(
            text = "アラート",
            color = Color.White,
            fontSize = 30.sp,
            fontWeight = FontWeight.Medium,
        )
        if (!hasCamera) {
            val activity = context.findActivity()
            val openSettings = cameraDenied &&
                activity != null &&
                !ActivityCompat.shouldShowRequestPermissionRationale(
                    activity,
                    Manifest.permission.CAMERA,
                )
            Panel(modifier = Modifier.fillMaxWidth()) {
                Text(
                    text = "インカメラが許可されていないため、発動中の撮影は行われません",
                    color = Color.White.copy(alpha = 0.75f),
                    fontSize = 18.sp,
                )
                Row(
                    modifier = Modifier.padding(top = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    AlertAction(
                        label = if (openSettings) "設定を開く" else "カメラを許可",
                        onClick = {
                            onTapSound()
                            if (openSettings) {
                                onOpenSettings()
                            } else {
                                cameraLauncher.launch(Manifest.permission.CAMERA)
                            }
                        },
                    )
                }
            }
        }
        if (records.isEmpty()) {
            Text(
                text = "発動したアラートはありません",
                color = Color.White.copy(alpha = 0.65f),
                fontSize = 20.sp,
            )
        }
        records.forEach { record ->
            val photos = shotsByAlert[record.id].orEmpty()
            Panel(modifier = Modifier.fillMaxWidth()) {
                if (record.failedUnlock) {
                    Text(
                        text = "解除に失敗",
                        color = Color.White,
                        fontSize = 22.sp,
                        fontWeight = FontWeight.Medium,
                    )
                    Text(
                        text = "時刻  ${record.startedAt.format(AlertTimeFormat)}",
                        color = Color.White,
                        fontSize = 22.sp,
                        modifier = Modifier.padding(top = 6.dp),
                    )
                } else {
                    Text(
                        text = "発報時間  ${record.startedAt.format(AlertTimeFormat)}",
                        color = Color.White,
                        fontSize = 22.sp,
                    )
                    Text(
                        text = "解除時間  ${record.endedAt?.format(AlertTimeFormat) ?: "発動中"}",
                        color = Color.White,
                        fontSize = 22.sp,
                        modifier = Modifier.padding(top = 6.dp),
                    )
                }
                Text(
                    text = "画像 ${photos.size}枚",
                    color = Color.White.copy(alpha = 0.65f),
                    fontSize = 18.sp,
                    modifier = Modifier.padding(top = 6.dp),
                )
                Row(
                    modifier = Modifier.padding(top = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    AlertAction(
                        label = "写真を見る",
                        onClick = {
                            onTapSound()
                            onOpenPhotos(record.id)
                        },
                    )
                    AlertAction(
                        label = "削除",
                        onClick = {
                            onTapSound()
                            pendingDeleteId = record.id
                        },
                    )
                }
            }
        }
    }
    val pending = records.firstOrNull { it.id == pendingDeleteId }
    if (pending != null) {
        val count = shotsByAlert[pending.id].orEmpty().size
        val message = when {
            count == 0 && pending.failedUnlock -> "この記録を削除します"
            count == 0 -> "このアラートを削除します"
            pending.failedUnlock -> "画像だけ消すか、この記録ごと消すかを選べます"
            else -> "画像だけ消すか、アラートごと消すかを選べます"
        }
        val choices = buildList {
            if (count > 0) {
                add(
                    DialogChoice("画像だけ削除") {
                        onTapSound()
                        pendingDeleteId = null
                        scope.launch(Dispatchers.IO) { archive.deletePhotos(pending.id) }
                    },
                )
            }
            add(
                DialogChoice(if (pending.failedUnlock) "記録を削除" else "アラートを削除") {
                    onTapSound()
                    pendingDeleteId = null
                    scope.launch(Dispatchers.IO) { archive.deleteRecord(pending.id) }
                },
            )
            add(DialogChoice("やめる") { onTapSound(); pendingDeleteId = null })
        }
        ConfirmDialog(
            message = message,
            onDismiss = {
                onTapSound()
                pendingDeleteId = null
            },
            choices = choices,
        )
    }
}

@Composable
fun AwayAlertTimelinePage(
    archive: AwayAlertArchive,
    alertId: String?,
    onTapSound: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val records by archive.records.collectAsState()
    val revision by archive.revision.collectAsState()
    val record = records.firstOrNull { it.id == alertId }
    val photos = remember(alertId, revision) {
        if (alertId == null) emptyList() else archive.photos(alertId)
    }
    var confirmAll by remember(alertId) { mutableStateOf(false) }
    var confirmPhoto by remember(alertId) { mutableStateOf<AwayAlertPhoto?>(null) }
    var openedPath by remember(alertId) { mutableStateOf<String?>(null) }
    var savingPath by remember { mutableStateOf<String?>(null) }
    var pendingCopy by remember { mutableStateOf<AwayAlertPhoto?>(null) }
    val scope = rememberCoroutineScope()
    val storageLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        val photo = pendingCopy
        pendingCopy = null
        if (granted && photo != null) {
            scope.launch { copyPhoto(archive, photo) { savingPath = it } }
        } else if (!granted) {
            showOsAlert("画像フォルダへ保存するには許可が必要です")
        }
    }
    val opened = photos.firstOrNull { it.file.absolutePath == openedPath }
    fun requestCopy(photo: AwayAlertPhoto) {
        if (savingPath == photo.file.absolutePath) {
            return
        }
        if (
            Build.VERSION.SDK_INT <= Build.VERSION_CODES.P &&
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.WRITE_EXTERNAL_STORAGE,
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            pendingCopy = photo
            storageLauncher.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
        } else {
            scope.launch { copyPhoto(archive, photo) { savingPath = it } }
        }
    }
    fun deleteOne(photo: AwayAlertPhoto) {
        val path = photo.file.absolutePath
        confirmPhoto = null
        if (openedPath == path) {
            openedPath = null
        }
        scope.launch(Dispatchers.IO) { archive.deletePhoto(photo) }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(top = 28.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        if (record == null) {
            Text(
                text = "アラート",
                color = Color.White,
                fontSize = 30.sp,
                fontWeight = FontWeight.Medium,
            )
            Text(
                text = "アラートが見つかりません",
                color = Color.White.copy(alpha = 0.65f),
                fontSize = 20.sp,
            )
            return@Column
        }
        if (record.failedUnlock) {
            Text(
                text = "解除に失敗",
                color = Color.White,
                fontSize = 30.sp,
                fontWeight = FontWeight.Medium,
            )
            Text(
                text = "時刻  ${record.startedAt.format(AlertTimeFormat)}",
                color = Color.White,
                fontSize = 22.sp,
            )
        } else {
            Text(
                text = "アラート",
                color = Color.White,
                fontSize = 30.sp,
                fontWeight = FontWeight.Medium,
            )
            Text(
                text = "発報時間  ${record.startedAt.format(AlertTimeFormat)}",
                color = Color.White,
                fontSize = 22.sp,
            )
            Text(
                text = "解除時間  ${record.endedAt?.format(AlertTimeFormat) ?: "発動中"}",
                color = Color.White,
                fontSize = 22.sp,
            )
        }
        if (photos.isEmpty()) {
            Text(
                text = "このアラートの画像はありません",
                color = Color.White.copy(alpha = 0.65f),
                fontSize = 20.sp,
            )
        } else {
            AlertAction(
                label = "画像を削除",
                onClick = {
                    onTapSound()
                    confirmAll = true
                },
            )
        }
        LazyColumn(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            items(photos, key = { it.file.absolutePath }) { photo ->
                val path = photo.file.absolutePath
                val saving = savingPath == path
                Panel(modifier = Modifier.fillMaxWidth()) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(16.dp),
                    ) {
                        Text(
                            text = photo.takenAt.format(PhotoTimeFormat),
                            color = Color.White,
                            fontSize = 20.sp,
                            modifier = Modifier.width(168.dp),
                        )
                        AlertPhotoImage(
                            photo = photo,
                            onClick = {
                                onTapSound()
                                openedPath = path
                            },
                            modifier = Modifier
                                .weight(1f)
                                .height(200.dp),
                        )
                    }
                    Row(
                        modifier = Modifier.padding(top = 12.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        AlertAction(
                            label = if (saving) "保存しています" else "画像フォルダへ保存",
                            onClick = {
                                onTapSound()
                                requestCopy(photo)
                            },
                        )
                        AlertAction(
                            label = "この画像を削除",
                            onClick = {
                                onTapSound()
                                confirmPhoto = photo
                            },
                        )
                    }
                }
            }
        }
    }
    if (opened != null) {
        AlertPhotoViewer(
            photo = opened,
            saving = savingPath == opened.file.absolutePath,
            onSave = {
                onTapSound()
                requestCopy(opened)
            },
            onDelete = {
                onTapSound()
                confirmPhoto = opened
            },
            onClose = {
                onTapSound()
                openedPath = null
            },
        )
    }
    if (confirmAll && record != null) {
        ConfirmDialog(
            message = if (record.failedUnlock) {
                "このときの画像を削除します"
            } else {
                "このアラートの画像を削除します"
            },
            onDismiss = {
                onTapSound()
                confirmAll = false
            },
            choices = listOf(
                DialogChoice("削除する") {
                    onTapSound()
                    confirmAll = false
                    scope.launch(Dispatchers.IO) { archive.deletePhotos(record.id) }
                },
                DialogChoice("やめる") {
                    onTapSound()
                    confirmAll = false
                },
            ),
        )
    }
    val selected = confirmPhoto
    if (selected != null) {
        ConfirmDialog(
            message = "この画像を削除します",
            onDismiss = {
                onTapSound()
                confirmPhoto = null
            },
            choices = listOf(
                DialogChoice("削除する") {
                    onTapSound()
                    deleteOne(selected)
                },
                DialogChoice("やめる") {
                    onTapSound()
                    confirmPhoto = null
                },
            ),
        )
    }
}

@Composable
fun AwayAlertMenuLock(
    controller: AwayAlertController,
    onUnlocked: () -> Unit,
    onTapSound: () -> Unit,
    onBeep: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val until by controller.menuLockoutUntilElapsed.collectAsState()
    var nowElapsed by remember { mutableLongStateOf(SystemClock.elapsedRealtime()) }
    var entry by remember { mutableStateOf("") }
    LaunchedEffect(until) {
        while (SystemClock.elapsedRealtime() < until) {
            nowElapsed = SystemClock.elapsedRealtime()
            delay(500)
        }
        nowElapsed = SystemClock.elapsedRealtime()
    }
    val remaining = (until - nowElapsed).coerceAtLeast(0L)
    val locked = remaining > 0L
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(top = 28.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        if (locked) {
            Text(
                text = "2分間、入力できません",
                color = Color.White,
                fontSize = 40.sp,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center,
            )
            Text(
                text = "あと ${formatLockRemaining(remaining)}",
                color = Color.White,
                fontSize = 32.sp,
                fontWeight = FontWeight.Medium,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(top = 20.dp),
            )
        } else {
            Text(
                text = "暗証番号",
                color = Color.White,
                fontSize = 30.sp,
                fontWeight = FontWeight.Medium,
            )
            Text(
                text = menuPinDots(entry),
                color = Color.White,
                fontSize = 48.sp,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center,
                letterSpacing = 8.sp,
                modifier = Modifier.padding(top = 24.dp),
            )
            MenuPinRows(
                onKey = { label ->
                    onTapSound()
                    when (label) {
                        "消去" -> entry = entry.dropLast(1)
                        "OK" -> {
                            if (entry.length != MenuPinLength) {
                                onBeep()
                            } else {
                                when (controller.submitMenuPin(entry)) {
                                    AwayPinResult.Wrong,
                                    AwayPinResult.Locked,
                                    -> {
                                        entry = ""
                                        onBeep()
                                    }
                                    AwayPinResult.Accepted -> onUnlocked()
                                }
                            }
                        }
                        else -> if (entry.length < MenuPinLength) {
                            entry += label
                        }
                    }
                },
                modifier = Modifier.padding(top = 28.dp),
            )
        }
    }
}

@Composable
private fun MenuPinRows(
    onKey: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        MenuPinLabels.forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                row.forEach { label ->
                    MenuPinKey(label = label, onClick = { onKey(label) })
                }
            }
        }
    }
}

@Composable
private fun MenuPinKey(
    label: String,
    onClick: () -> Unit,
) {
    Box(
        modifier = Modifier
            .size(width = 148.dp, height = 88.dp)
            .clip(RoundedCornerShape(20.dp))
            .background(Color.White.copy(alpha = 0.78f))
            .clickable(role = Role.Button, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            color = Color(0xFF3E2723),
            fontSize = if (label.length == 1) 36.sp else 24.sp,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center,
        )
    }
}

private fun menuPinDots(entry: String): String {
    return (0 until MenuPinLength).joinToString("  ") { index ->
        entry.getOrNull(index)?.toString() ?: "–"
    }
}

private fun formatLockRemaining(remainingMs: Long): String {
    val totalSec = ((remainingMs + 999L) / 1000L).toInt()
    val minutes = totalSec / 60
    val seconds = totalSec % 60
    return "$minutes:${seconds.toString().padStart(2, '0')}"
}

private const val MenuPinLength = 4

private val MenuPinLabels = listOf(
    listOf("1", "2", "3"),
    listOf("4", "5", "6"),
    listOf("7", "8", "9"),
    listOf("消去", "0", "OK"),
)

private data class DialogChoice(
    val label: String,
    val onClick: () -> Unit,
)

@Composable
private fun ConfirmDialog(
    message: String,
    onDismiss: () -> Unit,
    choices: List<DialogChoice>,
) {
    val shape = RoundedCornerShape(28.dp)
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            dismissOnClickOutside = false,
        ),
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.62f))
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = onDismiss,
                ),
            contentAlignment = Alignment.Center,
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth(0.62f)
                    .widthIn(max = 640.dp)
                    .clip(shape)
                    .background(Color(0xFF1A1A1A))
                    .border(1.dp, Color.White.copy(alpha = 0.16f), shape)
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = {},
                    )
                    .padding(horizontal = 28.dp, vertical = 24.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Text(
                    text = message,
                    color = Color.White,
                    fontSize = 22.sp,
                )
                choices.forEach { choice ->
                    AlertAction(
                        label = choice.label,
                        onClick = choice.onClick,
                    )
                }
            }
        }
    }
}

@Composable
private fun AlertPhotoViewer(
    photo: AwayAlertPhoto,
    saving: Boolean,
    onSave: () -> Unit,
    onDelete: () -> Unit,
    onClose: () -> Unit,
) {
    Dialog(
        onDismissRequest = onClose,
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            dismissOnBackPress = true,
            dismissOnClickOutside = false,
        ),
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.88f))
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = onClose,
                ),
            contentAlignment = Alignment.Center,
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 28.dp, vertical = 24.dp)
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = {},
                    ),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Text(
                    text = photo.takenAt.format(PhotoTimeFormat),
                    color = Color.White,
                    fontSize = 24.sp,
                    fontWeight = FontWeight.Medium,
                )
                AlertPhotoImage(
                    photo = photo,
                    maxEdge = 1600,
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                )
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    AlertAction(
                        label = if (saving) "保存しています" else "画像フォルダへ保存",
                        onClick = onSave,
                    )
                    AlertAction(label = "この画像を削除", onClick = onDelete)
                    AlertAction(label = "閉じる", onClick = onClose)
                }
            }
        }
    }
}

@Composable
private fun AlertPhotoImage(
    photo: AwayAlertPhoto,
    modifier: Modifier = Modifier,
    maxEdge: Int = 960,
    onClick: (() -> Unit)? = null,
) {
    val bitmap by produceState<androidx.compose.ui.graphics.ImageBitmap?>(
        null,
        photo.file.absolutePath,
        maxEdge,
    ) {
        value = withContext(Dispatchers.IO) {
            decodePreview(photo.file, maxEdge)?.asImageBitmap()
        }
    }
    val tap = if (onClick == null) {
        Modifier
    } else {
        Modifier.clickable(onClick = onClick)
    }
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(16.dp))
            .background(Color.Black.copy(alpha = 0.35f))
            .then(tap),
        contentAlignment = Alignment.Center,
    ) {
        val image = bitmap
        if (image != null) {
            Image(
                bitmap = image,
                contentDescription = "アラートの写真",
                contentScale = ContentScale.Fit,
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}

@Composable
private fun AlertAction(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Text(
        text = label,
        color = Color.White,
        fontSize = 20.sp,
        modifier = modifier
            .clip(ActionShape)
            .border(1.dp, Color.White.copy(alpha = 0.35f), ActionShape)
            .clickable(onClick = onClick)
            .padding(horizontal = 18.dp, vertical = 10.dp),
    )
}

private suspend fun copyPhoto(
    archive: AwayAlertArchive,
    photo: AwayAlertPhoto,
    setSaving: (String?) -> Unit,
) {
    setSaving(photo.file.absolutePath)
    val saved = try {
        withContext(Dispatchers.IO) { archive.copyToPictures(photo) }
    } finally {
        setSaving(null)
    }
    showOsAlert(if (saved) "画像フォルダに保存しました" else "保存できませんでした")
}

private fun hasCameraPermission(context: Context): Boolean {
    return ContextCompat.checkSelfPermission(
        context,
        Manifest.permission.CAMERA,
    ) == PackageManager.PERMISSION_GRANTED
}

private fun Context.findActivity(): Activity? {
    var current = this
    while (current is ContextWrapper) {
        if (current is Activity) {
            return current
        }
        current = current.baseContext
    }
    return null
}

private fun decodePreview(file: java.io.File, maxEdge: Int): android.graphics.Bitmap? {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeFile(file.absolutePath, bounds)
    if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
        return null
    }
    var sample = 1
    val edge = max(bounds.outWidth, bounds.outHeight)
    while (edge / (sample * 2) >= maxEdge) {
        sample *= 2
    }
    return BitmapFactory.decodeFile(
        file.absolutePath,
        BitmapFactory.Options().apply { inSampleSize = sample },
    )
}
