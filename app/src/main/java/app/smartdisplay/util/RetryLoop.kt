package app.smartdisplay.util

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/** 失敗したら待って同じ処理をやり直す。キャンセルはそのまま返す。 */
fun CoroutineScope.launchRetryLoop(
    intervalMs: Long,
    block: suspend () -> Unit,
): Job = launch {
    while (isActive) {
        try {
            block()
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            delay(intervalMs)
        }
    }
}
