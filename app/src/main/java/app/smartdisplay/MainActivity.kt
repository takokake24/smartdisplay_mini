package app.smartdisplay

import android.content.Intent
import android.content.res.Configuration
import android.net.Uri
import android.os.Bundle
import android.view.KeyEvent
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import app.smartdisplay.audio.VolumeKeyGuard
import app.smartdisplay.spotify.SpotifyPlayer
import app.smartdisplay.spotify.SpotifyRedirectUri
import app.smartdisplay.spotify.SpotifySession

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        SpotifySession.attach(applicationContext)
        consumeSpotifyRedirect(intent)
        enableEdgeToEdge()
        setContent {
            ClockScreen()
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        consumeSpotifyRedirect(intent)
    }

    private fun consumeSpotifyRedirect(intent: Intent?) {
        val data = intent?.data ?: return
        val redirect = Uri.parse(SpotifyRedirectUri)
        if (data.scheme != redirect.scheme || data.host != redirect.host) {
            return
        }
        setIntent(Intent(this, MainActivity::class.java))
        SpotifySession.completeLogin(applicationContext, data)
    }

    override fun onResume() {
        super.onResume()
        SpotifyPlayer.noteForegroundActivity(this)
    }

    override fun onPause() {
        if (!isInPictureInPictureMode) {
            SpotifyPlayer.noteForegroundActivity(null)
        }
        super.onPause()
    }

    override fun onPictureInPictureModeChanged(
        isInPictureInPictureMode: Boolean,
        newConfig: Configuration,
    ) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
        if (isInPictureInPictureMode) {
            SpotifyPlayer.onEnteredPictureInPicture(this)
        }
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (VolumeKeyGuard.locked && VolumeKeyGuard.isVolumeKey(event.keyCode)) {
            return true
        }
        return super.dispatchKeyEvent(event)
    }

    override fun onStart() {
        super.onStart()
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }

    override fun onStop() {
        super.onStop()
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }
}
