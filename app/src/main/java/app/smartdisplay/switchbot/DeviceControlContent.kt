package app.smartdisplay.switchbot

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.smartdisplay.util.InternetConnectivity
import app.smartdisplay.util.formatLastUpdatedLabel
import app.smartdisplay.util.showOsAlert
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.LocalDateTime
import java.util.Locale

@Composable
fun DeviceControlContent(
    now: LocalDateTime,
    onTapSound: () -> Unit,
    onErrorSound: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val client = remember { SwitchBotClient() }
    val scope = rememberCoroutineScope()
    var readings by remember { mutableStateOf<List<SwitchBotClimateReading>?>(null) }
    var inventory by remember { mutableStateOf<SwitchBotInventory?>(null) }
    var fetchedAt by remember { mutableStateOf<LocalDateTime?>(null) }
    var loading by remember { mutableStateOf(false) }
    var requestId by remember { mutableIntStateOf(0) }

    fun load() {
        if (!switchBotConfigured) {
            return
        }
        val id = requestId + 1
        requestId = id
        loading = true
        scope.launch {
            val result = withContext(Dispatchers.IO) {
                coroutineScope {
                    val climate = async {
                        runCatching { client.fetchClimate(switchBotToken, switchBotSecret) }
                    }
                    val index = async {
                        runCatching { client.fetchInventory(switchBotToken, switchBotSecret) }
                    }
                    climate.await() to index.await()
                }
            }
            if (id != requestId) {
                return@launch
            }
            val (climateResult, inventoryResult) = result
            inventoryResult.onSuccess { inventory = it }
            climateResult.onSuccess { value ->
                readings = value
                fetchedAt = LocalDateTime.now()
            }.onFailure { error ->
                showOsAlert(error.message?.takeIf { it.isNotBlank() } ?: "温湿度を取得できませんでした")
                if (InternetConnectivity.hasInternet()) {
                    onErrorSound()
                }
            }
            loading = false
        }
    }

    LaunchedEffect(Unit) {
        load()
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(top = 28.dp)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "機器操作",
                color = Color.White,
                fontSize = 30.sp,
                fontWeight = FontWeight.Medium,
            )
            HeaderChip(
                label = "更新",
                enabled = switchBotConfigured && !loading,
                onClick = {
                    onTapSound()
                    load()
                },
            )
        }
        Text(
            text = when {
                !switchBotConfigured -> "認証情報がありません"
                loading && readings == null -> "取得中"
                else -> formatLastUpdatedLabel(fetchedAt, now)
            },
            color = Color.White.copy(alpha = if (!switchBotConfigured) 0.75f else 0.4f),
            fontSize = 15.sp,
        )
        ClimateRow(
            readings = readings,
            loading = loading,
            configured = switchBotConfigured,
        )
        ControlPanel(
            client = client,
            inventory = inventory,
            onInventory = { inventory = it },
            onTapSound = onTapSound,
            onErrorSound = onErrorSound,
        )
    }
}

@Composable
private fun ClimateRow(
    readings: List<SwitchBotClimateReading>?,
    loading: Boolean,
    configured: Boolean,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(112.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        SwitchBotClimatePlace.entries.forEach { place ->
            val reading = readings?.firstOrNull { it.place == place }
            ClimateCard(
                place = place,
                reading = reading,
                loading = loading && reading == null,
                configured = configured,
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight(),
            )
        }
    }
}

@Composable
private fun ClimateCard(
    place: SwitchBotClimatePlace,
    reading: SwitchBotClimateReading?,
    loading: Boolean,
    configured: Boolean,
    modifier: Modifier = Modifier,
) {
    val shape = RoundedCornerShape(22.dp)
    val primary: String
    val secondary: String?
    when {
        reading != null && reading.unavailableReason == null -> {
            primary = reading.temperatureC?.let { formatTemperature(it) } ?: "—"
            secondary = reading.humidityPercent?.let { "$it%" } ?: "—"
        }
        reading?.unavailableReason != null -> {
            primary = reading.unavailableReason
            secondary = null
        }
        loading -> {
            primary = "取得中"
            secondary = null
        }
        !configured -> {
            primary = "未設定"
            secondary = null
        }
        else -> {
            primary = "—"
            secondary = null
        }
    }
    Column(
        modifier = modifier
            .clip(shape)
            .background(Color.White.copy(alpha = 0.08f))
            .padding(horizontal = 12.dp, vertical = 10.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            text = place.label,
            color = Color.White.copy(alpha = 0.7f),
            fontSize = 16.sp,
            textAlign = TextAlign.Center,
        )
        Text(
            text = primary,
            color = Color.White,
            fontSize = if (secondary == null) 22.sp else 28.sp,
            fontWeight = FontWeight.Medium,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 4.dp),
        )
        if (secondary != null) {
            Text(
                text = secondary,
                color = Color.White.copy(alpha = 0.75f),
                fontSize = 16.sp,
                textAlign = TextAlign.Center,
            )
        }
    }
}

@Composable
private fun HeaderChip(
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

private fun formatTemperature(celsius: Double): String {
    return String.format(Locale.JAPAN, "%.1f℃", celsius)
}
