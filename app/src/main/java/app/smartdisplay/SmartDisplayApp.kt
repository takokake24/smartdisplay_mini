package app.smartdisplay

import android.app.Application

class SmartDisplayApp : Application() {
    val keepAlive = KeepAliveRuntime(this)
}
