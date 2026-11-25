package app.smartdisplay.expiration

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import app.smartdisplay.notification.NotificationCategoryExpiration
import app.smartdisplay.notification.NotificationCenter
import app.smartdisplay.notification.NotificationSoundEffect
import app.smartdisplay.util.InternetConnectivity
import app.smartdisplay.util.NoInternetException
import app.smartdisplay.util.PreparedFetch
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.LocalDate

/**
 * 期限が近い食品を通知する。一件もなければ何も出さない。
 * 朝のまとめ、昼のまとめ、夜の通知から続ける。
 */
class ExpirationAnnouncer(
    private val notifications: NotificationCenter,
) {
    private val prepared = PreparedFetch<ExpirationPrepared>()

    /** 時報の鳴り始めに呼ぶ。案内の時点では、ここで始めた取得を使う。 */
    fun prepare(today: LocalDate) {
        prepared.start(today) { loadPrepared() }
    }

    suspend fun postCloser(today: LocalDate = LocalDate.now()) {
        val loaded = prepared.take(today)?.takeUnless { it is ExpirationPrepared.Offline }
            ?: loadPrepared()
        val items = when (loaded) {
            ExpirationPrepared.Offline -> return
            ExpirationPrepared.AuthMissing -> {
                notifications.postReadoutFailure(
                    title = "賞味期限管理",
                    message = "賞味期限の接続情報がありません。",
                )
                return
            }
            ExpirationPrepared.Failed -> {
                notifications.postReadoutFailure(
                    title = "賞味期限管理",
                    message = "賞味期限の取得に失敗しました。",
                )
                return
            }
            is ExpirationPrepared.Ready -> loaded.items
        }
        val announcement = buildExpirationAnnouncement(items, today) ?: return
        notifications.post(
            category = NotificationCategoryExpiration,
            title = "賞味期限管理",
            body = announcement.body,
            speechText = announcement.speechText,
            expirationItems = announcement.items,
            alert = true,
            addToHistory = false,
            soundEffect = NotificationSoundEffect.None,
            priority = true,
        )
    }

    private suspend fun loadPrepared(): ExpirationPrepared {
        if (!InternetConnectivity.hasInternet()) {
            return ExpirationPrepared.Offline
        }
        return try {
            ExpirationPrepared.Ready(withContext(Dispatchers.IO) { fetchCloserExpirations() })
        } catch (error: CancellationException) {
            throw error
        } catch (_: NoInternetException) {
            ExpirationPrepared.Offline
        } catch (_: ExpirationAuthMissingException) {
            ExpirationPrepared.AuthMissing
        } catch (_: Exception) {
            ExpirationPrepared.Failed
        }
    }
}

private sealed class ExpirationPrepared {
    data class Ready(val items: List<ExpirationItem>) : ExpirationPrepared()
    data object Offline : ExpirationPrepared()
    data object AuthMissing : ExpirationPrepared()
    data object Failed : ExpirationPrepared()
}

@Composable
fun rememberExpirationAnnouncer(
    notifications: NotificationCenter,
): ExpirationAnnouncer {
    return remember(notifications) {
        ExpirationAnnouncer(notifications)
    }
}
