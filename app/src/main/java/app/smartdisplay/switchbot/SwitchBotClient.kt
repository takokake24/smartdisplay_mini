package app.smartdisplay.switchbot

import android.net.Uri
import android.util.Base64
import app.smartdisplay.BuildConfig
import app.smartdisplay.util.NetworkLog
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID
import java.util.concurrent.TimeUnit
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import kotlin.math.roundToInt

enum class SwitchBotClimatePlace(val label: String) {
    Living("リビング"),
    Bedroom("寝室"),
    Bath("浴室"),
    Outside("外"),
}

data class SwitchBotClimateReading(
    val place: SwitchBotClimatePlace,
    val temperatureC: Double?,
    val humidityPercent: Int?,
    val unavailableReason: String?,
)

internal data class SwitchBotMeterSample(
    val temperatureC: Double,
    val humidityPercent: Int,
)

data class SwitchBotInventory(
    val deviceIds: Map<String, String>,
    val sceneIds: Map<String, String>,
)

/** カラー電球の電源・明るさ・色。白色は色温度が入り、color は 0:0:0 になる。 */
data class SwitchBotBulbStatus(
    val powerOn: Boolean,
    val brightness: Int,
    val red: Int,
    val green: Int,
    val blue: Int,
    val colorTemperature: Int,
)

/**
 * SwitchBot Open API v1.1。機器一覧から対象を名前で選び、状態の温度・湿度を読む。
 */
class SwitchBotClient {
    fun fetchClimate(token: String, secret: String): List<SwitchBotClimateReading> {
        val devices = listDevices(token, secret)
        val used = mutableSetOf<String>()
        return SwitchBotClimatePlace.entries.map { place ->
            val device = pickDevice(place, devices, used)
            if (device == null) {
                SwitchBotClimateReading(
                    place = place,
                    temperatureC = null,
                    humidityPercent = null,
                    unavailableReason = "未検出",
                )
            } else {
                used += device.id
                readClimate(token, secret, place, device.id)
            }
        }
    }

    /**
     * 天気案内用。室内は「リビング温度計」、室外は「外温湿度計」。
     * どちらかの温度か湿度が無ければ null。
     */
    internal fun fetchLivingAndOutdoor(
        token: String,
        secret: String,
    ): Pair<SwitchBotMeterSample, SwitchBotMeterSample>? {
        val devices = listDevices(token, secret)
        val indoor = devices.firstOrNull { it.name == LivingMeterName } ?: return null
        val outdoor = devices.firstOrNull { it.name == OutdoorMeterName } ?: return null
        val indoorSample = readMeter(token, secret, indoor.id) ?: return null
        val outdoorSample = readMeter(token, secret, outdoor.id) ?: return null
        return indoorSample to outdoorSample
    }

    fun fetchInventory(token: String, secret: String): SwitchBotInventory {
        val devicesRoot = getJson(DevicesUrl, token, secret, "機器一覧を取得できませんでした")
        val devicesBody = devicesRoot.getJSONObject("body")
        val deviceIds = linkedMapOf<String, String>()
        readNames(devicesBody.optJSONArray("deviceList"), "deviceId", "deviceName", deviceIds)
        readNames(devicesBody.optJSONArray("infraredRemoteList"), "deviceId", "deviceName", deviceIds)
        val scenesRoot = getJson(ScenesUrl, token, secret, "シーン一覧を取得できませんでした")
        val sceneIds = linkedMapOf<String, String>()
        readNames(scenesRoot.optJSONArray("body"), "sceneId", "sceneName", sceneIds)
        return SwitchBotInventory(deviceIds, sceneIds)
    }

    fun sendCommand(
        token: String,
        secret: String,
        deviceId: String,
        command: String,
        parameter: String,
        commandType: String,
    ) {
        val body = JSONObject()
            .put("command", command)
            .put("parameter", parameter)
            .put("commandType", commandType)
        postJson(commandUrl(deviceId), token, secret, body, "操作に失敗しました")
    }

    fun executeScene(token: String, secret: String, sceneId: String) {
        postJson(sceneUrl(sceneId), token, secret, JSONObject(), "操作に失敗しました")
    }

    /**
     * カーテンの開き位置。0 が全開、100 が全閉。
     * 位置が無ければ null。
     */
    fun fetchSlidePosition(token: String, secret: String, deviceId: String): Int? {
        val body = getJson(statusUrl(deviceId), token, secret, "状態を取得できませんでした")
            .optJSONObject("body")
            ?: return null
        if (!body.has("slidePosition") || body.isNull("slidePosition")) {
            return null
        }
        return when (val raw = body.opt("slidePosition")) {
            is Number -> raw.toInt()
            is String -> raw.trim().toIntOrNull()
            else -> null
        }
    }

    /** 電源状態を返す機器だけ true / false。取れなければ null。 */
    fun fetchPowerOn(token: String, secret: String, deviceId: String): Boolean? {
        val body = getJson(statusUrl(deviceId), token, secret, "状態を取得できませんでした")
            .optJSONObject("body")
            ?: return null
        return when (body.optString("power").trim().lowercase()) {
            "on" -> true
            "off" -> false
            else -> null
        }
    }

    /** 色と明るさまで読む。電源だけでは白色の常用と区別できない。 */
    fun fetchBulbStatus(token: String, secret: String, deviceId: String): SwitchBotBulbStatus? {
        val body = getJson(statusUrl(deviceId), token, secret, "状態を取得できませんでした")
            .optJSONObject("body")
            ?: return null
        val powerOn = when (body.optString("power").trim().lowercase()) {
            "on" -> true
            "off" -> false
            else -> return null
        }
        val parts = body.optString("color").split(':')
        if (parts.size != 3) {
            return null
        }
        val red = parts[0].trim().toIntOrNull() ?: return null
        val green = parts[1].trim().toIntOrNull() ?: return null
        val blue = parts[2].trim().toIntOrNull() ?: return null
        return SwitchBotBulbStatus(
            powerOn = powerOn,
            brightness = body.optInt("brightness", -1),
            red = red,
            green = green,
            blue = blue,
            colorTemperature = body.optInt("colorTemperature", -1),
        )
    }

    private fun readMeter(token: String, secret: String, deviceId: String): SwitchBotMeterSample? {
        return try {
            val body = getJson(statusUrl(deviceId), token, secret, "温湿度を取得できませんでした")
                .getJSONObject("body")
            val temperature = body.optNumber("temperature") ?: return null
            val humidity = body.optNumber("humidity")?.roundToInt() ?: return null
            SwitchBotMeterSample(temperature, humidity)
        } catch (_: Exception) {
            null
        }
    }

    private fun readClimate(
        token: String,
        secret: String,
        place: SwitchBotClimatePlace,
        deviceId: String,
    ): SwitchBotClimateReading {
        return try {
            val body = getJson(statusUrl(deviceId), token, secret, "温湿度を取得できませんでした").getJSONObject("body")
            val temperature = body.optNumber("temperature")
            val humidity = body.optNumber("humidity")?.roundToInt()
            SwitchBotClimateReading(
                place = place,
                temperatureC = temperature,
                humidityPercent = humidity,
                unavailableReason = if (temperature == null && humidity == null) "データなし" else null,
            )
        } catch (_: Exception) {
            SwitchBotClimateReading(
                place = place,
                temperatureC = null,
                humidityPercent = null,
                unavailableReason = "失敗",
            )
        }
    }

    private fun listDevices(token: String, secret: String): List<NamedDevice> {
        val body = getJson(DevicesUrl, token, secret, "温湿度を取得できませんでした").getJSONObject("body")
        val list = body.optJSONArray("deviceList") ?: return emptyList()
        return buildList {
            for (index in 0 until list.length()) {
                val item = list.optJSONObject(index) ?: continue
                val id = item.optString("deviceId").trim()
                val name = item.optString("deviceName").trim()
                if (id.isNotEmpty() && name.isNotEmpty()) {
                    add(NamedDevice(id, name))
                }
            }
        }
    }

    private fun getJson(url: String, token: String, secret: String, failure: String): JSONObject {
        return exchange(url, token, secret, failure, "GET", null, retry = true)
    }

    private fun postJson(
        url: String,
        token: String,
        secret: String,
        body: JSONObject,
        failure: String,
    ) {
        exchange(url, token, secret, failure, "POST", body, retry = false)
    }

    private fun exchange(
        url: String,
        token: String,
        secret: String,
        failure: String,
        method: String,
        body: JSONObject?,
        retry: Boolean,
    ): JSONObject = NetworkLog.trace(url, retry = retry) {
        val timestamp = System.currentTimeMillis().toString()
        val nonce = UUID.randomUUID().toString()
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = TimeoutMs.toInt()
            readTimeout = TimeoutMs.toInt()
            requestMethod = method
            doOutput = body != null
            setRequestProperty("Authorization", token)
            setRequestProperty("sign", sign(token, secret, timestamp, nonce))
            setRequestProperty("t", timestamp)
            setRequestProperty("nonce", nonce)
            setRequestProperty("User-Agent", "smartdisplay")
            if (body != null) {
                setRequestProperty("Content-Type", "application/json; charset=utf8")
            }
        }
        try {
            if (body != null) {
                connection.outputStream.use { stream ->
                    stream.write(body.toString().toByteArray(Charsets.UTF_8))
                }
            }
            val code = connection.responseCode
            val stream = if (code in 200..299) connection.inputStream else connection.errorStream
            val text = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
            if (code == 401 || text.contains("Unauthorized")) {
                error("認証に失敗しました")
            }
            if (code !in 200..299) {
                error(failure)
            }
            val json = JSONObject(text)
            val status = json.optInt("statusCode", -1)
            if (status != 100) {
                val message = json.optString("message")
                if (message.contains("Unauthorized", ignoreCase = true)) {
                    error("認証に失敗しました")
                }
                error(failure)
            }
            json
        } finally {
            connection.disconnect()
        }
    }

    private fun readNames(
        array: JSONArray?,
        idKey: String,
        nameKey: String,
        into: MutableMap<String, String>,
    ) {
        if (array == null) {
            return
        }
        for (index in 0 until array.length()) {
            val item = array.optJSONObject(index) ?: continue
            val id = item.optString(idKey).trim()
            val name = item.optString(nameKey).trim()
            if (id.isNotEmpty() && name.isNotEmpty() && name !in into) {
                into[name] = id
            }
        }
    }

    private fun sign(token: String, secret: String, timestamp: String, nonce: String): String {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(secret.toByteArray(Charsets.UTF_8), "HmacSHA256"))
        val raw = mac.doFinal((token + timestamp + nonce).toByteArray(Charsets.UTF_8))
        return Base64.encodeToString(raw, Base64.NO_WRAP)
    }

    private data class NamedDevice(val id: String, val name: String)

    companion object {
        private const val LivingMeterName = "リビング温度計"
        private const val OutdoorMeterName = "外温湿度計"
        private const val DevicesUrl = "https://api.switch-bot.com/v1.1/devices"
        private const val ScenesUrl = "https://api.switch-bot.com/v1.1/scenes"
        private val TimeoutMs = TimeUnit.SECONDS.toMillis(12)

        private fun statusUrl(deviceId: String): String {
            return "https://api.switch-bot.com/v1.1/devices/${Uri.encode(deviceId)}/status"
        }

        private fun commandUrl(deviceId: String): String {
            return "https://api.switch-bot.com/v1.1/devices/${Uri.encode(deviceId)}/commands"
        }

        private fun sceneUrl(sceneId: String): String {
            return "https://api.switch-bot.com/v1.1/scenes/${Uri.encode(sceneId)}/execute"
        }

        /**
         * 浴室は名前が「温湿度計Pro」。同種が複数あるときはトイレ・浴室を含む名前を優先する。
         */
        private fun pickDevice(
            place: SwitchBotClimatePlace,
            devices: List<NamedDevice>,
            used: Set<String>,
        ): NamedDevice? {
            val available = devices.filter { it.id !in used }
            return when (place) {
                SwitchBotClimatePlace.Living -> available.firstByName(LivingMeterName)
                SwitchBotClimatePlace.Bedroom -> available.firstByName("寝室ハブ")
                SwitchBotClimatePlace.Outside -> available.firstByName(OutdoorMeterName)
                SwitchBotClimatePlace.Bath -> pickBathMeter(available)
            }
        }

        private fun pickBathMeter(devices: List<NamedDevice>): NamedDevice? {
            val pros = devices.filter { it.name.contains("温湿度計Pro") }
            return pros.firstOrNull { it.name.contains("トイレ") || it.name.contains("浴室") }
                ?: pros.firstOrNull { it.name == "温湿度計Pro" }
                ?: pros.firstOrNull()
        }

        private fun List<NamedDevice>.firstByName(name: String): NamedDevice? {
            return firstOrNull { it.name == name }
        }
    }
}

internal val switchBotToken = BuildConfig.SWITCHBOT_TOKEN
internal val switchBotSecret = BuildConfig.SWITCHBOT_SECRET
internal val switchBotConfigured = switchBotToken.isNotBlank() && switchBotSecret.isNotBlank()

private fun JSONObject.optNumber(key: String): Double? {
    if (!has(key) || isNull(key)) {
        return null
    }
    return when (val raw = opt(key)) {
        is Number -> raw.toDouble()
        is String -> raw.toDoubleOrNull()
        else -> null
    }
}
