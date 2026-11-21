package com.arkueid.livetranslateclient

import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString.Companion.toByteString
import org.json.JSONObject
import java.util.concurrent.TimeUnit
import android.util.Log

/** Minimal OkHttp websocket client: JSON events in, raw PCM out. */
class WsClient(private val url: String) {

    private val safeUrl = url.substringBefore('?')

    private val http = OkHttpClient.Builder()
        .pingInterval(15, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()

    @Volatile
    private var socket: WebSocket? = null

    @Volatile
    var isOpen: Boolean = false
        private set

    fun connect() {
        if (socket != null) return
        val request = Request.Builder().url(url).build()
        val created = http.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                socket = webSocket
                isOpen = true
                Log.i(TAG, "connected to $safeUrl")
                SubtitleBus.postConnection(true, null)
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                val event = try {
                    JSONObject(text)
                } catch (_: Exception) {
                    return
                }
                if (event.optString("type") == "ready") {
                    ConnectionManager.setTargetLanguage(event.optString("target_language"))
                }
                SubtitleBus.postEvent(event)
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                socket = null
                isOpen = false
                Log.i(TAG, "closed to $safeUrl code=$code reason=$reason")
                SubtitleBus.postConnection(false, reason.ifBlank { null })
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                socket = null
                isOpen = false
                Log.w(TAG, "failed to $safeUrl", t)
                SubtitleBus.postConnection(false, t.message)
            }
        })
        socket = created
    }

    fun sendAudio(data: ByteArray) {
        val ws = socket ?: return
        if (!isOpen) return
        ws.send(data.toByteString())
    }

    fun close() {
        isOpen = false
        socket?.close(1000, "bye")
        socket = null
    }

    private companion object {
        const val TAG = "WsClient"
    }
}
