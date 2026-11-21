package com.arkueid.livetranslateclient

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioPlaybackCaptureConfiguration
import android.media.AudioRecord
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.widget.Toast
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import org.json.JSONObject
import kotlin.math.max

/**
 * Captures other apps' playback via AudioPlaybackCapture and streams raw
 * PCM16LE (16 kHz mono) to the PC over a websocket.
 */
class CaptureService : Service() {

    companion object {
        private const val ACTION_START = "com.arkueid.livetranslateclient.action.START_CAPTURE"
        private const val ACTION_STOP = "com.arkueid.livetranslateclient.action.STOP_CAPTURE"
        private const val ACTION_PAUSE = "com.arkueid.livetranslateclient.action.PAUSE_CAPTURE"
        private const val ACTION_RESUME = "com.arkueid.livetranslateclient.action.RESUME_CAPTURE"
        private const val EXTRA_RESULT_CODE = "result_code"
        private const val EXTRA_RESULT_DATA = "result_data"

        private const val CHANNEL_ID = "capture"
        private const val NOTIFICATION_ID = 42
        private const val SAMPLE_RATE = 16000
        private const val RECONNECT_DELAY_MS = 3000L

        fun start(context: Context, resultCode: Int, data: Intent) {
            val intent = Intent(context, CaptureService::class.java)
                .setAction(ACTION_START)
                .putExtra(EXTRA_RESULT_CODE, resultCode)
                .putExtra(EXTRA_RESULT_DATA, data)
            ContextCompat.startForegroundService(context, intent)
        }

        fun stop(context: Context) {
            context.startService(
                Intent(context, CaptureService::class.java).setAction(ACTION_STOP)
            )
        }

        fun pause(context: Context) {
            context.startService(
                Intent(context, CaptureService::class.java).setAction(ACTION_PAUSE)
            )
        }

        fun resume(context: Context) {
            context.startService(
                Intent(context, CaptureService::class.java).setAction(ACTION_RESUME)
            )
        }
    }

    private val handler = Handler(Looper.getMainLooper())
    private var projection: MediaProjection? = null
    private var record: AudioRecord? = null
    private var captureThread: Thread? = null

    @Volatile
    private var running = false

    @Volatile
    private var paused = false
    private var reconnectScheduled = false

    private val projectionCallback = object : MediaProjection.Callback() {
        override fun onStop() {
            handler.post {
                stopCapture()
                stopSelf()
            }
        }
    }

    private val connectionListener = object : SubtitleBus.Listener {
        override fun onServerEvent(event: JSONObject) = Unit

        override fun onConnectionChanged(connected: Boolean, detail: String?) {
            if (!connected && running && !paused && !ConnectionManager.manualStop) {
                scheduleReconnect()
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        createChannel()
        SubtitleBus.register(connectionListener)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopCapture()
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_PAUSE -> {
                pauseCapture()
                return START_NOT_STICKY
            }
            ACTION_RESUME -> {
                resumeCapture()
                return START_NOT_STICKY
            }
            ACTION_START -> {
                startAsForeground()
                val resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, 0)
                @Suppress("DEPRECATION")
                val resultData: Intent? = intent.getParcelableExtra(EXTRA_RESULT_DATA)
                if (resultData == null) {
                    toast(getString(R.string.toast_projection_denied))
                    stopSelf()
                    return START_NOT_STICKY
                }
                if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
                    toast(getString(R.string.toast_need_android10))
                    stopSelf()
                    return START_NOT_STICKY
                }
                beginCapture(resultCode, resultData)
            }
        }
        return START_NOT_STICKY
    }

    private fun startAsForeground() {
        val notification = buildNotification()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID, notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun buildNotification(): Notification {
        val intent = Intent(this, MainActivity::class.java)
        val openApp = PendingIntent.getActivity(
            this, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val stopIntent = PendingIntent.getService(
            this, 1,
            Intent(this, CaptureService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_translate)
            .setContentTitle(
                getString(
                    if (paused) R.string.notif_capture_paused
                    else R.string.notif_capture_title
                )
            )
            .setContentText(getString(R.string.notif_capture_text))
            .setOngoing(true)
            .setContentIntent(openApp)
            .addAction(0, getString(R.string.notif_action_stop), stopIntent)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    private fun updateNotification() {
        if (!running) return
        val manager = getSystemService(NotificationManager::class.java)
        manager.notify(NOTIFICATION_ID, buildNotification())
    }

    private fun pauseCapture() {
        if (!running || paused) return
        paused = true
        ConnectionManager.disconnect()
        updateNotification()
        SubtitleBus.postCaptureState(true, true)
    }

    private fun resumeCapture() {
        if (!running || !paused) return
        paused = false
        ConnectionManager.connect(this)
        updateNotification()
        SubtitleBus.postCaptureState(true, false)
    }

    private fun beginCapture(resultCode: Int, resultData: Intent) {
        if (running) return
        val manager = getSystemService(MediaProjectionManager::class.java)
        val mp = try {
            manager.getMediaProjection(resultCode, resultData)
        } catch (e: Exception) {
            null
        }
        if (mp == null) {
            toast(getString(R.string.toast_projection_denied))
            stopSelf()
            return
        }
        projection = mp
        mp.registerCallback(projectionCallback, handler)

        ConnectionManager.connect(this)
        startRecording(mp)
    }

    private fun startRecording(mp: MediaProjection) {
        val captureConfig = AudioPlaybackCaptureConfiguration.Builder(mp)
            .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
            .addMatchingUsage(AudioAttributes.USAGE_GAME)
            .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN)
            .build()

        val format = AudioFormat.Builder()
            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
            .setSampleRate(SAMPLE_RATE)
            .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
            .build()

        val minBuffer = AudioRecord.getMinBufferSize(
            SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT
        )
        val bufferSize = max(minBuffer * 2, 8192)

        val recorder = try {
            AudioRecord.Builder()
                .setAudioFormat(format)
                .setBufferSizeInBytes(bufferSize)
                .setAudioPlaybackCaptureConfig(captureConfig)
                .build()
        } catch (e: Exception) {
            null
        }
        if (recorder == null || recorder.state != AudioRecord.STATE_INITIALIZED) {
            recorder?.release()
            toast(getString(R.string.toast_permission_denied))
            stopSelf()
            return
        }

        record = recorder
        running = true
        paused = false
        recorder.startRecording()
        SubtitleBus.postCaptureState(true, false)

        captureThread = Thread({
            val buffer = ByteArray(bufferSize)
            while (running) {
                val read = try {
                    recorder.read(buffer, 0, buffer.size)
                } catch (e: Exception) {
                    break
                }
                if (read > 0) {
                    if (!paused) {
                        val payload = if (read == buffer.size) buffer else buffer.copyOf(read)
                        ConnectionManager.sendAudio(payload)
                    }
                } else if (read < 0) {
                    break
                }
            }
        }, "AudioCapture").apply {
            priority = Thread.MAX_PRIORITY
            start()
        }
    }

    private fun scheduleReconnect() {
        if (reconnectScheduled) return
        reconnectScheduled = true
        handler.postDelayed({
            reconnectScheduled = false
            if (!running) return@postDelayed
            ConnectionManager.connect(this)
        }, RECONNECT_DELAY_MS)
    }

    private fun stopCapture() {
        running = false
        paused = false
        SubtitleBus.postCaptureState(false, false)
        try {
            record?.stop()
        } catch (_: Exception) {
        }
        record?.release()
        record = null
        captureThread?.join(500)
        captureThread = null
        projection?.let {
            try {
                it.unregisterCallback(projectionCallback)
            } catch (_: Exception) {
            }
            try {
                it.stop()
            } catch (_: Exception) {
            }
        }
        projection = null
        ConnectionManager.disconnect()
        reconnectScheduled = false
        handler.removeCallbacksAndMessages(null)
    }

    private fun createChannel() {
        val manager = getSystemService(NotificationManager::class.java)
        if (manager.getNotificationChannel(CHANNEL_ID) == null) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                getString(R.string.notif_channel_capture),
                NotificationManager.IMPORTANCE_LOW
            )
            channel.setShowBadge(false)
            manager.createNotificationChannel(channel)
        }
    }

    private fun toast(text: String) {
        Toast.makeText(this, text, Toast.LENGTH_SHORT).show()
    }

    override fun onDestroy() {
        SubtitleBus.unregister(connectionListener)
        stopCapture()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
