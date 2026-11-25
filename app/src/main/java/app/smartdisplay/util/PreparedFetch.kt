package app.smartdisplay.util

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async

/**
 * 案内の前に始めておく取得。
 * 同じキーの結果だけ [take] で受け取り、無ければ呼び出し側が取り直す。
 */
class PreparedFetch<T> {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var key: Any? = null
    private var task: Deferred<T>? = null

    fun start(key: Any, block: suspend () -> T) {
        val previous = synchronized(this) {
            val old = task
            this.key = key
            task = scope.async { block() }
            old
        }
        previous?.cancel()
    }

    suspend fun take(key: Any): T? {
        val pending = synchronized(this) {
            if (this.key != key) {
                return@synchronized null
            }
            val current = task ?: return@synchronized null
            this.key = null
            task = null
            current
        } ?: return null
        return pending.await()
    }
}
