package com.arkueid.livetranslateclient

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import org.json.JSONObject

/**
 * Settings screen: connection fields plus a single Start button. The system
 * screen-capture dialog that follows also lets the user pick which app to
 * share, so no extra chooser is needed here.
 */
class MainActivity : AppCompatActivity(), SubtitleBus.Listener {

    private lateinit var hostEdit: EditText
    private lateinit var portEdit: EditText
    private lateinit var tokenEdit: EditText
    private lateinit var statusView: TextView
    private lateinit var connectButton: Button

    private var pendingAction = ACTION_NONE

    private val permissionsLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { grants ->
        val action = pendingAction
        pendingAction = ACTION_NONE
        val localGranted = grants[LOCAL_NETWORK_PERMISSION] ?: true
        val audioGranted = grants[Manifest.permission.RECORD_AUDIO] ?: true
        when {
            action == ACTION_CONNECT && !localGranted ->
                toast(getString(R.string.toast_local_network_denied))
            action == ACTION_START && !audioGranted ->
                toast(getString(R.string.toast_permission_denied))
            action == ACTION_CONNECT -> doConnect()
            action == ACTION_START -> continueStart()
        }
    }

    private val projectionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val data = result.data
        if (result.resultCode == Activity.RESULT_OK && data != null) {
            CaptureService.start(this, result.resultCode, data)
            OverlayService.show(this)
            statusView.setText(R.string.status_connecting)
            statusView.setTextColor(ContextCompat.getColor(this, R.color.status_off))
        } else {
            toast(getString(R.string.toast_projection_denied))
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        // targetSdk 35+ draws edge to edge; keep content clear of system bars.
        val root = findViewById<View>(R.id.main_root)
        ViewCompat.setOnApplyWindowInsetsListener(root) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }

        hostEdit = findViewById(R.id.edit_host)
        portEdit = findViewById(R.id.edit_port)
        tokenEdit = findViewById(R.id.edit_token)
        statusView = findViewById(R.id.text_status)
        connectButton = findViewById(R.id.btn_connect)

        val prefs = Prefs.get(this)
        hostEdit.setText(prefs.getString(Prefs.KEY_HOST, ""))
        portEdit.setText(prefs.getInt(Prefs.KEY_PORT, 8766).toString())
        tokenEdit.setText(prefs.getString(Prefs.KEY_TOKEN, ""))

        connectButton.setOnClickListener { onConnectClicked() }
        findViewById<Button>(R.id.btn_start).setOnClickListener { onStartClicked() }
    }

    override fun onResume() {
        super.onResume()
        SubtitleBus.register(this)
        refreshStatus()
    }

    override fun onPause() {
        SubtitleBus.unregister(this)
        super.onPause()
    }

    /** Validates the URL fields and stores them; returns true when usable. */
    private fun saveConnectionInput(): Boolean {
        // Tolerate pasted input like "ws://192.168.1.10:8766/ws/subtitle".
        val host = hostEdit.text.toString().trim()
            .removePrefix("ws://")
            .removePrefix("http://")
            .substringBefore('/')
            .substringBefore(':')
        if (host.isEmpty()) {
            toast(getString(R.string.toast_need_host))
            return false
        }
        val port = portEdit.text.toString().toIntOrNull() ?: 8766
        if (port !in 1..65535) {
            toast(getString(R.string.toast_invalid_port))
            return false
        }
        Prefs.get(this).edit()
            .putString(Prefs.KEY_HOST, host)
            .putInt(Prefs.KEY_PORT, port)
            .putString(Prefs.KEY_TOKEN, tokenEdit.text.toString().trim())
            .apply()
        return true
    }

    private fun onConnectClicked() {
        if (ConnectionManager.isConnected) {
            if (SubtitleBus.captureRunning) {
                CaptureService.stop(this)
            }
            ConnectionManager.disconnect()
            updateConnectButton(false)
            refreshStatus()
            return
        }
        if (!saveConnectionInput()) return
        if (requiresLocalNetworkPermission()) {
            pendingAction = ACTION_CONNECT
            permissionsLauncher.launch(arrayOf(LOCAL_NETWORK_PERMISSION))
            return
        }
        doConnect()
    }

    private fun doConnect() {
        ConnectionManager.connect(this)
        updateConnectButton(true)
        refreshStatus()
    }

    private fun onStartClicked() {
        if (SubtitleBus.captureRunning) {
            toast(getString(R.string.toast_capture_running))
            return
        }
        if (!ConnectionManager.isConnected) {
            toast(getString(R.string.toast_need_connect))
            return
        }

        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            toast(getString(R.string.toast_need_android10))
            return
        }

        val required = mutableListOf(Manifest.permission.RECORD_AUDIO)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            required.add(Manifest.permission.POST_NOTIFICATIONS)
        }
        val missing = required.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isNotEmpty()) {
            pendingAction = ACTION_START
            permissionsLauncher.launch(missing.toTypedArray())
            return
        }
        continueStart()
    }

    /** Android 17 blocks local-network sockets until this permission is granted. */
    private fun requiresLocalNetworkPermission(): Boolean =
        Build.VERSION.SDK_INT >= 37 &&
            ContextCompat.checkSelfPermission(this, LOCAL_NETWORK_PERMISSION) !=
            PackageManager.PERMISSION_GRANTED

    private fun continueStart() {
        if (!Settings.canDrawOverlays(this)) {
            toast(getString(R.string.toast_overlay_permission))
            startActivity(
                Intent(
                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:$packageName")
                )
            )
            return
        }
        val manager = getSystemService(MediaProjectionManager::class.java)
        try {
            projectionLauncher.launch(manager.createScreenCaptureIntent())
        } catch (e: Exception) {
            toast(e.message ?: getString(R.string.toast_projection_denied))
        }
    }

    private fun toast(text: String) {
        Toast.makeText(this, text, Toast.LENGTH_SHORT).show()
    }

    private fun refreshStatus() {
        updateConnectButton(ConnectionManager.isConnected)
        when {
            SubtitleBus.capturePaused ->
                setStatus(getString(R.string.status_paused), R.color.status_off)
            SubtitleBus.captureRunning && ConnectionManager.isConnected ->
                setStatus(connectedText(), R.color.status_ok)
            SubtitleBus.captureRunning ->
                setStatus(getString(R.string.status_connecting), R.color.status_off)
            ConnectionManager.isConnected ->
                setStatus(connectedText(), R.color.status_ok)
            else ->
                setStatus(getString(R.string.status_idle), R.color.status_off)
        }
    }

    private fun connectedText(): String {
        val target = ConnectionManager.targetLanguage
        return if (target.isEmpty()) {
            getString(R.string.status_connected)
        } else {
            getString(R.string.status_connected) + " · " + target
        }
    }

    private fun updateConnectButton(connected: Boolean) {
        if (!::connectButton.isInitialized) return
        connectButton.setText(
            if (connected) R.string.btn_disconnect else R.string.btn_connect
        )
    }

    private fun setStatus(text: String, colorRes: Int) {
        statusView.text = text
        statusView.setTextColor(ContextCompat.getColor(this, colorRes))
    }

    override fun onServerEvent(event: JSONObject) {
        if (event.optString("type") == "ready") refreshStatus()
    }

    override fun onConnectionChanged(connected: Boolean, detail: String?) {
        refreshStatus()
    }

    override fun onCaptureStateChanged(running: Boolean, paused: Boolean) {
        refreshStatus()
    }

    private companion object {
        const val LOCAL_NETWORK_PERMISSION = "android.permission.ACCESS_LOCAL_NETWORK"
        const val ACTION_NONE = 0
        const val ACTION_CONNECT = 1
        const val ACTION_START = 2
    }
}
