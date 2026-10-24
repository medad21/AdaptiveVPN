package com.adaptivevpn.app.vpn

import android.content.Context

/** Boundary between Android VPN lifecycle and the native sing-box runtime. */
interface SingBoxEngine {
    /** Starts sing-box or throws. A successful return does not itself prove Internet connectivity. */
    fun start(context: Context, configPath: String)
    fun stop()
}

class EngineNotIntegratedException(message: String) : IllegalStateException(message)
