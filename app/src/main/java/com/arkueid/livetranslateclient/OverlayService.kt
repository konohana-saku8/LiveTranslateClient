package com.arkueid.livetranslateclient

import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import org.json.JSONObject
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Floating panel that mirrors the PC overlay: draggable header, scrollable
 * message list, bottom-right resize grip.
 */
class OverlayService : Service(), SubtitleBus.Listener {

    companion object {
        fun show(context: Context) {
            context.startService(Intent(context, OverlayService::class.java))
        }

        fun hide(context: Context) {
            context.stopService(Intent(context, OverlayService::class.java))
        }
    }

    private lateinit var windowManager: WindowManager
    private lateinit var rootView: View
    private lateinit var layoutParams: WindowManager.LayoutParams
    private lateinit var adapter: MessageAdapter
    private lateinit var recycler: RecyclerView
    private lateinit var statusView: TextView
    private lateinit var stopButton: TextView

    private var lastMessage: MessageAdapter.Message? = null
    private var streamUpdatePending = false
    private var maxHeight = 0
    private var manualHeight = false
    private var followBottom = true
    private val mainHandler = Handler(Looper.getMainLooper())

    private val streamUpdate = Runnable {
        streamUpdatePending = false
        val message = lastMessage ?: return@Runnable
        val index = adapter.indexOf(message)
        if (index >= 0) {
            adapter.notifyItemChanged(index)
            refreshLayout()
        }
    }

    override fun onCreate() {
        super.onCreate()
        if (!Settings.canDrawOverlays(this)) {
            Toast.makeText(this, R.string.toast_overlay_permission, Toast.LENGTH_LONG).show()
            stopSelf()
            return
        }
        try {
            windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
            rootView = LayoutInflater.from(this).inflate(R.layout.overlay_panel, null)
            addOverlayWindow()
            bindViews()
            SubtitleBus.register(this)
        } catch (e: Exception) {
            Toast.makeText(this, e.message ?: "overlay failed", Toast.LENGTH_LONG).show()
            stopSelf()
        }
    }

    // ── window ──

    private fun addOverlayWindow() {
        val prefs = Prefs.get(this)
        val metrics = resources.displayMetrics
        val defaultWidth = min((metrics.widthPixels * 0.94f).toInt(), dp(420))
        val defaultHeight = dp(300)

        var width = prefs.getInt(Prefs.KEY_OVERLAY_W, defaultWidth).coerceAtLeast(dp(220))
        width = width.coerceAtMost(metrics.widthPixels)
        var height = prefs.getInt(Prefs.KEY_OVERLAY_H, defaultHeight).coerceAtLeast(dp(120))
        height = height.coerceAtMost(metrics.heightPixels - dp(60))
        // Saved height is the maximum: the window hugs its content so the empty
        // part of the panel does not cover the video underneath, unless the user
        // dragged the resize grip and pinned a fixed height.
        maxHeight = height
        manualHeight = prefs.getBoolean(Prefs.KEY_OVERLAY_MANUAL, false)
        val initialHeight = if (manualHeight) height else min(height, dp(64))

        var x = prefs.getInt(Prefs.KEY_OVERLAY_X, metrics.widthPixels - width - dp(8))
        var y = prefs.getInt(Prefs.KEY_OVERLAY_Y, max(dp(80), metrics.heightPixels / 3))
        x = x.coerceIn(-width / 2, max(0, metrics.widthPixels - dp(60)))
        y = y.coerceIn(0, max(0, metrics.heightPixels - dp(60)))

        layoutParams = WindowManager.LayoutParams(
            width,
            initialHeight,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        )
        layoutParams.gravity = Gravity.TOP or Gravity.START
        layoutParams.x = x
        layoutParams.y = y
        windowManager.addView(rootView, layoutParams)
    }

    private fun bindViews() {
        recycler = rootView.findViewById(R.id.overlay_list)
        statusView = rootView.findViewById(R.id.overlay_status)
        stopButton = rootView.findViewById(R.id.btn_stop)

        adapter = MessageAdapter()
        recycler.layoutManager = LinearLayoutManager(this)
        recycler.adapter = adapter
        recycler.itemAnimator = null
        recycler.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrolled(rv: RecyclerView, dx: Int, dy: Int) {
                // Stop chasing new sentences once the user scrolls up to read
                // older ones; resume as soon as they are back at the bottom.
                followBottom = !rv.canScrollVertically(1)
            }
        })

        // The whole blank part of the title bar is the drag handle.
        attachDrag(rootView.findViewById(R.id.overlay_drag_area))
        attachResize(rootView.findViewById(R.id.overlay_grip))

        rootView.findViewById<TextView>(R.id.btn_clear).setOnClickListener {
            adapter.clear()
            lastMessage = null
            rootView.post { fitToContent() }
        }
        rootView.findViewById<TextView>(R.id.btn_settings).setOnClickListener {
            val intent = Intent(this, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            startActivity(intent)
        }
        stopButton.setOnClickListener { onStopClicked() }
        updateStopButton()
        refreshStatus()
        rootView.post { fitToContent() }
    }

    private fun onStopClicked() {
        when {
            !SubtitleBus.captureRunning -> {
                // No projection token any more: restarting needs the settings screen.
                startActivity(
                    Intent(this, MainActivity::class.java)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
            }
            SubtitleBus.capturePaused -> CaptureService.resume(this)
            else -> CaptureService.pause(this)
        }
    }

    private fun updateStopButton() {
        if (!::stopButton.isInitialized) return
        stopButton.setText(
            when {
                !SubtitleBus.captureRunning -> R.string.overlay_btn_start
                SubtitleBus.capturePaused -> R.string.overlay_btn_resume
                else -> R.string.overlay_btn_stop
            }
        )
    }

    private fun refreshStatus() {
        if (!::statusView.isInitialized) return
        when {
            SubtitleBus.capturePaused ->
                setStatus(getString(R.string.overlay_status_paused), R.color.status_off)
            ConnectionManager.isConnected -> {
                val target = ConnectionManager.targetLanguage
                val text = if (target.isEmpty()) {
                    getString(R.string.overlay_status_connected)
                } else {
                    getString(R.string.overlay_status_connected) + " · " + target
                }
                setStatus(text, R.color.status_ok)
            }
            SubtitleBus.captureRunning ->
                setStatus(getString(R.string.overlay_status_idle), R.color.status_off)
            else ->
                setStatus(getString(R.string.overlay_status_stopped), R.color.status_off)
        }
    }

    private fun attachDrag(view: View) {
        view.setOnTouchListener(object : View.OnTouchListener {
            private var startX = 0
            private var startY = 0
            private var touchX = 0f
            private var touchY = 0f
            private var moving = false

            override fun onTouch(v: View, event: MotionEvent): Boolean {
                when (event.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        startX = layoutParams.x
                        startY = layoutParams.y
                        touchX = event.rawX
                        touchY = event.rawY
                        moving = false
                        return true
                    }
                    MotionEvent.ACTION_MOVE -> {
                        val dx = (event.rawX - touchX).toInt()
                        val dy = (event.rawY - touchY).toInt()
                        if (abs(dx) > dp(3) || abs(dy) > dp(3)) moving = true
                        if (moving) {
                            layoutParams.x = startX + dx
                            layoutParams.y = startY + dy
                            windowManager.updateViewLayout(rootView, layoutParams)
                        }
                        return true
                    }
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                        if (moving) saveGeometry()
                        return true
                    }
                }
                return false
            }
        })
    }

    private fun attachResize(view: View) {
        view.setOnTouchListener(object : View.OnTouchListener {
            private var startW = 0
            private var startH = 0
            private var touchX = 0f
            private var touchY = 0f

            override fun onTouch(v: View, event: MotionEvent): Boolean {
                when (event.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        startW = layoutParams.width
                        startH = layoutParams.height
                        touchX = event.rawX
                        touchY = event.rawY
                        return true
                    }
                    MotionEvent.ACTION_MOVE -> {
                        val w = max(dp(220), startW + (event.rawX - touchX).toInt())
                        val h = max(dp(64), startH + (event.rawY - touchY).toInt())
                        if (w != layoutParams.width || h != layoutParams.height) {
                            layoutParams.width = w
                            layoutParams.height = h
                            windowManager.updateViewLayout(rootView, layoutParams)
                        }
                        return true
                    }
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                        // A finished drag pins the height: stop auto-fitting so it
                        // does not bounce back to the content height. A tap that
                        // never changed the height stays in auto mode.
                        if (layoutParams.height != startH) {
                            manualHeight = true
                            maxHeight = layoutParams.height.coerceAtLeast(dp(64))
                        }
                        saveGeometry()
                        fitToContent()
                        return true
                    }
                }
                return false
            }
        })
    }

    /**
     * Auto mode: shrink/grow the window to the message list, capped by [maxHeight].
     * Manual mode (after the user dragged the grip): keep the pinned height and
     * let the list scroll instead, so a resize gesture does not snap back.
     */
    private fun fitToContent() {
        if (!::rootView.isInitialized || !::recycler.isInitialized) return
        val screenHeight = resources.displayMetrics.heightPixels
        val cap = maxHeight.coerceAtMost(screenHeight - dp(60))

        if (manualHeight) {
            applyHeight(cap.coerceAtLeast(dp(60)), screenHeight)
            return
        }

        val header = rootView.findViewById<View>(R.id.overlay_header).height
        val footer = rootView.findViewById<View>(R.id.overlay_footer).height
        if (header == 0 || footer == 0) return

        var listContent = 0
        if (adapter.itemCount > 0) {
            val lp = recycler.layoutParams
            val oldHeight = lp.height
            lp.height = ViewGroup.LayoutParams.WRAP_CONTENT
            val width = if (recycler.width > 0) {
                recycler.width
            } else {
                rootView.width - rootView.paddingLeft - rootView.paddingRight
            }
            if (width <= 0) {
                lp.height = oldHeight
                return
            }
            recycler.measure(
                View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
            )
            listContent = recycler.measuredHeight
            lp.height = oldHeight
        }

        val content = rootView.paddingTop + rootView.paddingBottom +
            header + footer + listContent
        applyHeight(content.coerceIn(dp(60), cap), screenHeight)
    }

    /**
     * After a message is added or grows (a long sentence wraps to a second
     * line), keep the newest line visible. The scroll request is left pending
     * on purpose: it is applied by the next layout pass, after the new item has
     * been measured. A post() runs before that pass and would scroll by the old
     * content height, leaving the list short of the bottom.
     */
    private fun refreshLayout() {
        if (followBottom && adapter.itemCount > 0) {
            recycler.scrollToPosition(adapter.itemCount - 1)
        }
        rootView.post { fitToContent() }
    }

    private fun applyHeight(target: Int, screenHeight: Int) {
        if (target == layoutParams.height) return
        layoutParams.height = target
        if (layoutParams.y + target > screenHeight - dp(8)) {
            layoutParams.y = (screenHeight - dp(8) - target).coerceAtLeast(0)
        }
        windowManager.updateViewLayout(rootView, layoutParams)
    }

    private fun saveGeometry() {
        Prefs.get(this).edit()
            .putInt(Prefs.KEY_OVERLAY_X, layoutParams.x)
            .putInt(Prefs.KEY_OVERLAY_Y, layoutParams.y)
            .putInt(Prefs.KEY_OVERLAY_W, layoutParams.width)
            .putInt(Prefs.KEY_OVERLAY_H, maxHeight)
            .putBoolean(Prefs.KEY_OVERLAY_MANUAL, manualHeight)
            .apply()
    }

    // ── server events ──

    override fun onServerEvent(event: JSONObject) {
        when (event.optString("type")) {
            "ready" -> refreshStatus()
            "asr" -> {
                val message = MessageAdapter.Message(
                    timestamp = event.optString("timestamp"),
                    language = event.optString("language"),
                    original = event.optString("text"),
                    asrMs = event.optDouble("asr_ms", 0.0)
                )
                lastMessage = adapter.add(message)
                refreshLayout()
            }
            "translation_delta" -> {
                val message = lastMessage ?: return
                message.translation = event.optString("text")
                message.streaming = true
                if (!streamUpdatePending) {
                    streamUpdatePending = true
                    mainHandler.postDelayed(streamUpdate, 80)
                }
            }
            "translation" -> {
                val message = lastMessage ?: return
                message.translation = event.optString("text")
                message.tlMs = event.optDouble("tl_ms", 0.0)
                message.streaming = false
                val index = adapter.indexOf(message)
                if (index >= 0) {
                    adapter.notifyItemChanged(index)
                    refreshLayout()
                }
            }
            "error" -> {
                val text = getString(R.string.overlay_error, event.optString("message"))
                val message = MessageAdapter.Message(
                    timestamp = "",
                    language = "",
                    original = "",
                    asrMs = 0.0,
                    translation = text,
                    streaming = false,
                    isError = true
                )
                adapter.add(message)
                refreshLayout()
            }
        }
    }

    override fun onConnectionChanged(connected: Boolean, detail: String?) {
        refreshStatus()
    }

    override fun onCaptureStateChanged(running: Boolean, paused: Boolean) {
        updateStopButton()
        refreshStatus()
    }

    private fun setStatus(text: String, colorRes: Int) {
        if (!::statusView.isInitialized) return
        statusView.text = text
        statusView.setTextColor(ContextCompat.getColor(this, colorRes))
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        SubtitleBus.unregister(this)
        mainHandler.removeCallbacksAndMessages(null)
        if (::rootView.isInitialized && rootView.isAttachedToWindow) {
            try {
                windowManager.removeView(rootView)
            } catch (_: Exception) {
            }
        }
        super.onDestroy()
    }
}
