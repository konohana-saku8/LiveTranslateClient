package com.arkueid.livetranslateclient

import android.os.Handler
import android.os.Looper
import org.json.JSONObject
import java.util.concurrent.CopyOnWriteArraySet

/**
 * Process-wide bridge between the capture/websocket threads and the overlay.
 * All callbacks are delivered on the main thread.
 */
object SubtitleBus {
    interface Listener {
        fun onServerEvent(event: JSONObject)
        fun onConnectionChanged(connected: Boolean, detail: String?)
        fun onCaptureStateChanged(running: Boolean, paused: Boolean) {}
    }

    private val listeners = CopyOnWriteArraySet<Listener>()
    private val mainHandler = Handler(Looper.getMainLooper())

    /** Last known capture state, so late-started views can render correctly. */
    @Volatile
    var captureRunning: Boolean = false
        private set

    @Volatile
    var capturePaused: Boolean = false
        private set

    fun register(listener: Listener) {
        listeners.add(listener)
    }

    fun unregister(listener: Listener) {
        listeners.remove(listener)
    }

    fun postEvent(event: JSONObject) {
        mainHandler.post {
            for (listener in listeners) {
                listener.onServerEvent(event)
            }
        }
    }

    fun postConnection(connected: Boolean, detail: String?) {
        mainHandler.post {
            for (listener in listeners) {
                listener.onConnectionChanged(connected, detail)
            }
        }
    }

    fun postCaptureState(running: Boolean, paused: Boolean) {
        captureRunning = running
        capturePaused = paused
        mainHandler.post {
            for (listener in listeners) {
                listener.onCaptureStateChanged(running, paused)
            }
        }
    }
}
