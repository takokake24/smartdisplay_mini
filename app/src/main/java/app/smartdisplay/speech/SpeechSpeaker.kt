package app.smartdisplay.speech

import android.content.Context
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.util.Locale
import java.util.concurrent.atomic.AtomicInteger

/**
 * アプリ全体で再利用する日本語向けの読み上げ。
 * 初期化完了前に呼ばれた内容は、準備ができてから話す。
 */
class SpeechSpeaker(context: Context) {
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val ready: CompletableDeferred<Boolean> = CompletableDeferred()
    private val utteranceSeq: AtomicInteger = AtomicInteger(0)
    private val appContext: Context = context.applicationContext
    private lateinit var tts: TextToSpeech
    private var currentDone: CompletableDeferred<Boolean>? = null

    init {
        tts = TextToSpeech(appContext) { status: Int ->
            if (status != TextToSpeech.SUCCESS) {
                ready.complete(false)
                return@TextToSpeech
            }
            val languageResult: Int = tts.setLanguage(Locale.JAPANESE)
            val languageOk: Boolean = languageResult != TextToSpeech.LANG_MISSING_DATA &&
                languageResult != TextToSpeech.LANG_NOT_SUPPORTED
            if (languageOk) {
                tts.setSpeechRate(DefaultSpeechRate)
                tts.setPitch(1.0f)
            }
            ready.complete(languageOk)
        }
    }

    fun setSpeechRate(rate: Float) {
        if (!::tts.isInitialized) {
            return
        }
        runCatching { tts.setSpeechRate(rate.coerceIn(0.5f, 2.0f)) }
    }

    fun speak(text: String, flush: Boolean = true) {
        scope.launch {
            speakNow(text = text, flush = flush)
        }
    }

    suspend fun speakNow(
        text: String,
        flush: Boolean = true,
        volume: Float = 1f,
    ): Boolean {
        val ok: Boolean = withTimeoutOrNull(5_000L) { ready.await() } == true
        if (!ok || text.isBlank()) {
            return false
        }
        val done: CompletableDeferred<Boolean> = CompletableDeferred()
        currentDone = done
        val utteranceId: String = "speech-${utteranceSeq.incrementAndGet()}"
        tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(id: String?) = Unit

            override fun onDone(id: String?) {
                if (id == utteranceId) {
                    done.complete(true)
                }
            }

            @Deprecated("Deprecated in Java")
            override fun onError(id: String?) {
                if (id == utteranceId) {
                    done.complete(false)
                }
            }

            override fun onError(id: String?, errorCode: Int) {
                if (id == utteranceId) {
                    done.complete(false)
                }
            }
        })
        val queueMode: Int = if (flush) {
            TextToSpeech.QUEUE_FLUSH
        } else {
            TextToSpeech.QUEUE_ADD
        }
        val params = Bundle().apply {
            putFloat(
                TextToSpeech.Engine.KEY_PARAM_VOLUME,
                volume.coerceIn(0f, 1f),
            )
        }
        val spokenText = SpeechDictionary.apply(text)
        val result: Int = tts.speak(spokenText, queueMode, params, utteranceId)
        if (result != TextToSpeech.SUCCESS) {
            if (currentDone === done) {
                currentDone = null
            }
            return false
        }
        return try {
            withTimeoutOrNull(30_000L) { done.await() } == true
        } finally {
            if (currentDone === done) {
                currentDone = null
            }
        }
    }

    fun stop() {
        currentDone?.complete(false)
        currentDone = null
        tts.stop()
    }

    fun release() {
        scope.cancel()
        currentDone?.complete(false)
        currentDone = null
        tts.stop()
        tts.shutdown()
    }
}

@Composable
fun rememberSpeechSpeaker(): SpeechSpeaker {
    val context = LocalContext.current
    val speaker = remember { SpeechSpeaker(context.applicationContext) }
    DisposableEffect(speaker) {
        onDispose { speaker.release() }
    }
    return speaker
}

const val DefaultSpeechRate = 1.15f
