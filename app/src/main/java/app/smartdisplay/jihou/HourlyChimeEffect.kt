package app.smartdisplay.jihou

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import app.smartdisplay.audio.SoundPresenceGate
import app.smartdisplay.speech.AnnouncementGate
import app.smartdisplay.speech.SpeechSpeaker

@Composable
fun rememberHourlyChimeController(
    speaker: SpeechSpeaker,
    announcementGate: AnnouncementGate,
    presenceGate: SoundPresenceGate? = null,
    enabled: Boolean,
): HourlyChimeController {
    val context = LocalContext.current
    val controller = remember(speaker, announcementGate, presenceGate) {
        HourlyChimeController(
            context = context.applicationContext,
            speaker = speaker,
            announcementGate = announcementGate,
            presenceGate = presenceGate,
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
