package app.smartdisplay.away

import android.content.ContentValues
import android.content.Context
import android.os.Build
import android.os.Environment
import android.os.StatFs
import android.provider.MediaStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.util.UUID

data class AwayAlertRecord(
    val id: String,
    val startedAt: LocalDateTime,
    val endedAt: LocalDateTime?,
    val failedUnlock: Boolean = false,
)

data class AwayAlertPhoto(
    val file: File,
    val takenAt: LocalDateTime,
)

/**
 * 外出アラートの発報・解除時刻と、発動中に撮った画像。
 * 画像はアプリ専用領域に置き、空き容量が少ないときは古いものから消す。
 */
class AwayAlertArchive(context: Context) {
    private val prefs = context.applicationContext
        .getSharedPreferences(PrefsName, Context.MODE_PRIVATE)
    private val resolver = context.applicationContext.contentResolver
    private val root = File(context.applicationContext.filesDir, DirectoryName).apply { mkdirs() }
    private val gate = Any()

    private val _records = MutableStateFlow(load())
    val records: StateFlow<List<AwayAlertRecord>> = _records.asStateFlow()

    private val _revision = MutableStateFlow(0)
    val revision: StateFlow<Int> = _revision.asStateFlow()

    fun begin(): String = synchronized(gate) {
        val record = AwayAlertRecord(
            id = UUID.randomUUID().toString(),
            startedAt = LocalDateTime.now(),
            endedAt = null,
        )
        val next = listOf(record) + _records.value
        _records.value = next
        save(next)
        record.id
    }

    /** メニューの暗証番号を3回間違えたときの記録。画像はその直後に1枚添える。 */
    fun beginFailedUnlock(): String = synchronized(gate) {
        val now = LocalDateTime.now()
        val record = AwayAlertRecord(
            id = UUID.randomUUID().toString(),
            startedAt = now,
            endedAt = now,
            failedUnlock = true,
        )
        val next = listOf(record) + _records.value
        _records.value = next
        save(next)
        record.id
    }

    fun finish(id: String) {
        synchronized(gate) {
            val now = LocalDateTime.now()
            val current = _records.value
            if (current.none { it.id == id && it.endedAt == null }) {
                return
            }
            val next = current.map { record ->
                if (record.id == id && record.endedAt == null) {
                    record.copy(endedAt = now)
                } else {
                    record
                }
            }
            _records.value = next
            save(next)
        }
    }

    /** 前回のプロセスが発動中に落ちた記録へ、起動時点の解除時刻を入れる。 */
    fun closeOpenRecords() {
        synchronized(gate) {
            val now = LocalDateTime.now()
            val current = _records.value
            if (current.none { it.endedAt == null }) {
                return
            }
            val next = current.map { record ->
                if (record.endedAt == null) record.copy(endedAt = now) else record
            }
            _records.value = next
            save(next)
        }
    }

    fun photos(id: String): List<AwayAlertPhoto> = synchronized(gate) {
        photoFiles(id).map { file ->
            val millis = file.nameWithoutExtension.toLongOrNull() ?: file.lastModified()
            AwayAlertPhoto(
                file = file,
                takenAt = Instant.ofEpochMilli(millis)
                    .atZone(ZoneId.systemDefault())
                    .toLocalDateTime(),
            )
        }
    }

    fun deletePhotos(id: String) {
        synchronized(gate) {
            val dir = File(root, id)
            photoFiles(id).forEach { it.delete() }
            dir.delete()
            _revision.value = _revision.value + 1
        }
    }

    /** 発報・解除の記録と、その画像を消す。 */
    fun deleteRecord(id: String) {
        synchronized(gate) {
            val current = _records.value
            val next = current.filterNot { it.id == id }
            if (next.size != current.size) {
                _records.value = next
                save(next)
            }
            val dir = File(root, id)
            photoFiles(id).forEach { it.delete() }
            dir.delete()
            _revision.value = _revision.value + 1
        }
    }

    fun contains(id: String): Boolean = synchronized(gate) {
        _records.value.any { it.id == id }
    }

    /** 1枚だけ消す。発報・解除の記録は残す。 */
    fun deletePhoto(photo: AwayAlertPhoto) {
        synchronized(gate) {
            val file = photo.file
            val parent = file.parentFile
            if (parent?.parentFile?.absolutePath != root.absolutePath || !file.isFile) {
                return
            }
            if (!file.delete()) {
                return
            }
            if (parent.listFiles()?.isEmpty() == true) {
                parent.delete()
            }
            _revision.value = _revision.value + 1
        }
    }

    fun newPhotoFile(id: String): File {
        val dir = File(root, id)
        dir.mkdirs()
        return File(dir, "${System.currentTimeMillis()}.jpg")
    }

    fun bump() {
        synchronized(gate) {
            _revision.value = _revision.value + 1
        }
    }

    /**
     * 空きが 512MB を下回ったら、一番新しい1枚を残して古い画像から消す。
     */
    fun ensureSpace() {
        synchronized(gate) {
            if (availableBytes() >= FreeSpaceFloorBytes) {
                return
            }
            val files = allJpegs().sortedBy { it.name }
            val victims = if (files.size <= 1) emptyList() else files.dropLast(1)
            var changed = false
            for (file in victims) {
                if (availableBytes() >= FreeSpaceFloorBytes) {
                    break
                }
                if (file.delete()) {
                    changed = true
                    file.parentFile?.takeIf { dir ->
                        dir != root && dir.listFiles()?.isEmpty() == true
                    }?.delete()
                }
            }
            if (changed) {
                _revision.value = _revision.value + 1
            }
        }
    }

    fun hasRoomForPhoto(): Boolean = availableBytes() >= MinFreeBytesToCapture

    fun copyToPictures(photo: AwayAlertPhoto): Boolean {
        if (!photo.file.isFile) {
            return false
        }
        try {
            return insertIntoPictures(photo)
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            return false
        }
    }

    private fun insertIntoPictures(photo: AwayAlertPhoto): Boolean {
        val name = "alert_${photo.file.name}"
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, name)
            put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
            put(MediaStore.Images.Media.DATE_TAKEN, photo.file.nameWithoutExtension.toLongOrNull() ?: photo.file.lastModified())
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(
                    MediaStore.Images.Media.RELATIVE_PATH,
                    "${Environment.DIRECTORY_PICTURES}/SmartDisplay",
                )
                put(MediaStore.Images.Media.IS_PENDING, 1)
            } else {
                @Suppress("DEPRECATION")
                val dir = File(
                    Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES),
                    "SmartDisplay",
                )
                dir.mkdirs()
                put(MediaStore.Images.Media.DATA, File(dir, name).absolutePath)
            }
        }
        val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values) ?: return false
        try {
            resolver.openOutputStream(uri)?.use { output ->
                photo.file.inputStream().use { input -> input.copyTo(output) }
            } ?: return false
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val done = ContentValues().apply {
                    put(MediaStore.Images.Media.IS_PENDING, 0)
                }
                if (resolver.update(uri, done, null, null) != 1) {
                    resolver.delete(uri, null, null)
                    return false
                }
            }
            return true
        } catch (error: CancellationException) {
            resolver.delete(uri, null, null)
            throw error
        } catch (_: Exception) {
            resolver.delete(uri, null, null)
            return false
        }
    }

    private fun photoFiles(id: String): List<File> {
        val dir = File(root, id)
        return dir.listFiles { file ->
            file.isFile && file.extension.equals("jpg", ignoreCase = true) && file.length() > 0L
        }?.sortedBy { it.name }.orEmpty()
    }

    private fun allJpegs(): List<File> {
        if (!root.isDirectory) {
            return emptyList()
        }
        return root.listFiles().orEmpty().flatMap { child ->
            if (!child.isDirectory) {
                emptyList()
            } else {
                child.listFiles { file ->
                    file.isFile && file.extension.equals("jpg", ignoreCase = true)
                }?.toList().orEmpty()
            }
        }
    }

    private fun availableBytes(): Long {
        return runCatching { StatFs(root.absolutePath).availableBytes }.getOrDefault(0L)
    }

    private fun load(): List<AwayAlertRecord> {
        val raw = prefs.getString(KeyRecords, null) ?: return emptyList()
        val array = runCatching { JSONArray(raw) }.getOrNull() ?: return emptyList()
        val records = mutableListOf<AwayAlertRecord>()
        for (index in 0 until array.length()) {
            val item = array.optJSONObject(index) ?: continue
            val id = item.optString("id")
            val started = item.optString("startedAt")
            if (id.isBlank() || started.isBlank()) {
                continue
            }
            val startedAt = runCatching { LocalDateTime.parse(started) }.getOrNull() ?: continue
            val endedRaw = item.optString("endedAt")
            val endedAt = endedRaw.takeIf { it.isNotBlank() }?.let {
                runCatching { LocalDateTime.parse(it) }.getOrNull()
            }
            records.add(
                AwayAlertRecord(
                    id = id,
                    startedAt = startedAt,
                    endedAt = endedAt,
                    failedUnlock = item.optBoolean("failedUnlock", false),
                ),
            )
        }
        return records.sortedByDescending { it.startedAt }
    }

    private fun save(records: List<AwayAlertRecord>) {
        val array = JSONArray()
        records.forEach { record ->
            array.put(
                JSONObject()
                    .put("id", record.id)
                    .put("startedAt", record.startedAt.toString())
                    .put("endedAt", record.endedAt?.toString() ?: JSONObject.NULL)
                    .put("failedUnlock", record.failedUnlock),
            )
        }
        prefs.edit().putString(KeyRecords, array.toString()).commit()
    }

    companion object {
        private const val PrefsName = "away_alert_archive"
        private const val KeyRecords = "records"
        private const val DirectoryName = "away_alerts"
        private const val FreeSpaceFloorBytes = 512L * 1024L * 1024L
        private const val MinFreeBytesToCapture = 32L * 1024L * 1024L
    }
}
