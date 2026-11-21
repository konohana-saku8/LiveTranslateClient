package com.arkueid.livetranslateclient

import android.content.Context

/**
 * Owns the single websocket connection so the main screen (Connect button)
 * and CaptureService share one socket instead of racing for the server slot.
 */
object ConnectionManager {
    @Volatile
    private var client: WsClient? = null

    @Volatile
    private var currentUrl: String = ""

    /** True after an explicit disconnect; suppresses automatic reconnects. */
    @Volatile
    var manualStop: Boolean = false
        private set

    val isConnected: Boolean
        get() = client?.isOpen == true

    /** Target language reported by the PC in its "ready" event. */
    @Volatile
    var targetLanguage: String = ""
        private set

    fun setTargetLanguage(language: String) {
        targetLanguage = language
    }

    fun connect(context: Context) {
        manualStop = false
        val url = Prefs.serverUrl(context)
        if (client != null && currentUrl == url && isConnected) return
        client?.close()
        currentUrl = url
        client = WsClient(url).also { it.connect() }
    }

    fun disconnect() {
        manualStop = true
        client?.close()
        client = null
        currentUrl = ""
    }

    fun sendAudio(data: ByteArray) {
        client?.sendAudio(data)
    }
}
