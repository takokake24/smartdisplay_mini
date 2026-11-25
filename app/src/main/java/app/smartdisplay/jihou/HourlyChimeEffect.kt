package app.smartdisplay.jihou

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import app.smartdisplay.speech.AnnouncementGate
import app.smartdisplay.speech.SpeechSpeaker
import java.time.LocalDateTime

@Composable
fun rememberHourlyChimeController(
    speaker: SpeechSpeaker,
    announcementGate: AnnouncementGate,
    enabled: Boolean,
    settings: JihouSettings,
    playbackScale: (LocalDateTime) -> Float = { _ -> 1f },
): HourlyChimeController {
    val context = LocalContext.current
    val controller = remember(speaker, announcementGate, settings) {
        HourlyChimeController(
            context = context.applicationContext,
            speaker = speaker,
            announcementGate = announcementGate,
            seasonFor = { at -> settings.effectiveSeason(at) },
            playbackScale = playbackScale,
            initiallyEnabled = enabled,
        )
    }
    LaunchedEffect(controller, enabled) {
        controller.setEnabled(enabled)
    }
    DisposableEffect(controller) {
        controller.start()
        onDispose { controller.release() }
    }
    return controller
}
