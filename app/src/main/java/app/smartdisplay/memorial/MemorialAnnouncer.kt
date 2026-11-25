package app.smartdisplay.memorial

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import app.smartdisplay.notification.NotificationCategoryMemorial
import app.smartdisplay.notification.NotificationCenter
import app.smartdisplay.notification.NotificationSoundEffect
import app.smartdisplay.util.InternetConnectivity
import app.smartdisplay.util.NoInternetException
import app.smartdisplay.util.PreparedFetch
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import java.time.LocalDate
import java.time.LocalDateTime

class MemorialAnnouncer(
    private val repository: MemorialRepository,
    private val notifications: NotificationCenter,
) {
    private val prepared = PreparedFetch<MemorialPrepared>()

    suspend fun onChimeFinished(time: LocalDateTime) {
        if (!memorialsForChime(time.hour, time.minute)) {
            return
        }
        postAnnouncement(time.toLocalDate())
    }

    /** 時報の鳴り始めに呼ぶ。案内の時点では、ここで始めた取得を使う。 */
    fun prepare(date: LocalDate) {
        prepared.start(date) { loadPrepared(date) }
    }

    suspend fun postAnnouncement(date: LocalDate = LocalDate.now()) {
        val loaded = prepared.take(date)?.takeUnless { it is MemorialPrepared.Offline }
            ?: loadPrepared(date)
        val ready = when (loaded) {
            MemorialPrepared.Offline -> return
            MemorialPrepared.Failed -> {
                notifications.postReadoutFailure(
                    title = "今日の記念日",
                    message = "記念日の取得に失敗しました。",
                )
                return
            }
            is MemorialPrepared.Ready -> loaded
        }
        val announce = announcementMemorials(date, ready.days, ready.previous)
        if (announce.isEmpty()) {
            return
        }
        val labels = announce.map { it.listLabel }
        notifications.post(
            category = NotificationCategoryMemorial,
            title = "今日の記念日",
            body = labels.joinToString("\n"),
            speechText = memorialSpeech(announce),
            memorialNames = labels,
            alert = true,
            addToHistory = false,
            soundEffect = NotificationSoundEffect.None,
            priority = true,
        )
    }

    private suspend fun loadPrepared(date: LocalDate): MemorialPrepared {
        if (!InternetConnectivity.hasInternet()) {
            return MemorialPrepared.Offline
        }
        return try {
            val loaded = withContext(Dispatchers.IO) {
                coroutineScope {
                    val today = async { repository.load(date) }
                    val previous = async { loadPrevious(date) }
                    today.await() to previous.await()
                }
            }
            MemorialPrepared.Ready(days = loaded.first, previous = loaded.second)
        } catch (error: CancellationException) {
            throw error
        } catch (_: NoInternetException) {
            MemorialPrepared.Offline
        } catch (_: Exception) {
            MemorialPrepared.Failed
        }
    }

    /** 前日が取れなくても、今日の案内は止めない。 */
    private fun loadPrevious(date: LocalDate): List<MemorialDay> {
        return try {
            repository.load(date.minusDays(1))
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            emptyList()
        }
    }
}

private sealed class MemorialPrepared {
    data class Ready(
        val days: List<MemorialDay>,
        val previous: List<MemorialDay>,
    ) : MemorialPrepared()
    data object Offline : MemorialPrepared()
    data object Failed : MemorialPrepared()
}

@Composable
fun rememberMemorialAnnouncer(
    repository: MemorialRepository,
    notifications: NotificationCenter,
): MemorialAnnouncer {
    return remember(repository, notifications) {
        MemorialAnnouncer(
            repository = repository,
            notifications = notifications,
        )
    }
}
