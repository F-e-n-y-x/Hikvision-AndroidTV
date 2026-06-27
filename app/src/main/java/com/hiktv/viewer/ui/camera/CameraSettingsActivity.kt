package com.hiktv.viewer.ui.camera

import android.content.Intent
import android.os.Bundle
import android.text.InputType
import android.view.LayoutInflater
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.hiktv.viewer.core.Session
import com.hiktv.viewer.data.isapi.IsapiClient
import com.hiktv.viewer.data.model.Camera
import com.hiktv.viewer.data.model.Nvr
import com.hiktv.viewer.data.onvif.OnvifPtz
import com.hiktv.viewer.data.store.NvrStore
import com.hiktv.viewer.databinding.ActivityCameraSettingsBinding
import com.hiktv.viewer.databinding.RowSettingBinding
import com.hiktv.viewer.util.DialogIme
import com.hiktv.viewer.ui.control.ControlActivity
import com.hiktv.viewer.ui.playback.PlaybackActivity
import kotlinx.coroutines.launch
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Per-camera settings & control page, opened from fullscreen with OK. Scopes every action to
 * one camera: live PTZ/zoom control, a *direct* connection to the camera's own IP (so PTZ works
 * on units the NVR won't proxy, e.g. EZVIZ), playback, snapshot, rename and info.
 */
class CameraSettingsActivity : AppCompatActivity() {

    private lateinit var binding: ActivityCameraSettingsBinding
    private lateinit var store: NvrStore
    private lateinit var camera: Camera

    private data class Row(val title: String, val subtitle: String, val action: () -> Unit)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityCameraSettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)
        store = NvrStore(this)

        val channel = intent.getIntExtra(EXTRA_CHANNEL, -1)
        val cam = Session.cameraByChannel(channel)
        if (cam == null) { finish(); return }
        camera = cam

        render()
    }

    override fun onResume() {
        super.onResume()
        if (::camera.isInitialized) render()
    }

    private fun render() {
        binding.camTitle.text = camera.name
        binding.camSub.text = buildString {
            append("Channel ${camera.channel}")
            append(if (camera.online) "  •  Online" else "  •  Offline")
        }

        val rows = listOf(
            Row("PTZ & zoom control", controlSubtitle()) { openControl() },
            Row("Direct camera connection", directSubtitle()) { editDirect() },
            Row("Test PTZ connection", "Check why PTZ does / doesn't work") { testPtz() },
            Row("Playback", "View this camera's recordings") { openPlayback() },
            Row("Snapshot", "Save a still to the device") { takeSnapshot() },
            Row("Rename camera", "Change the display name") { rename() },
            Row("Camera info", "Streams, PTZ and connection") { showInfo() }
        )

        val container: LinearLayout = binding.rowContainer
        container.removeAllViews()
        val inflater = LayoutInflater.from(this)
        rows.forEachIndexed { i, row ->
            val rb = RowSettingBinding.inflate(inflater, container, false)
            rb.rowTitle.text = row.title
            rb.rowSubtitle.text = row.subtitle
            rb.root.setOnClickListener { row.action() }
            container.addView(rb.root)
            if (i == 0) rb.root.post { rb.root.requestFocus() }
        }
    }

    // ---- Control -----------------------------------------------------------

    private fun controlSubtitle(): String =
        if (store.loadDirect(camera.channel) != null)
            "Live control via direct camera connection"
        else
            "Live PTZ (if supported) or digital zoom, via the NVR"

    private fun openControl() {
        startActivity(Intent(this, ControlActivity::class.java)
            .putExtra(ControlActivity.EXTRA_CHANNEL, camera.channel))
    }

    // ---- Direct connection -------------------------------------------------

    private fun directSubtitle(): String {
        val d = store.loadDirect(camera.channel)
        if (d == null) return "Not set — control goes through the NVR"
        val proto = if (store.loadDirectOnvif(camera.channel)) "ONVIF" else "ISAPI"
        return "Direct $proto: ${d.host}:${d.httpPort}  (tap to edit / clear)"
    }

    /**
     * Lets the user point straight at the camera (its own LAN IP + account). Useful for PTZ
     * cameras the NVR refuses to drive — the app then sends PTZ to the camera over ONVIF (best
     * for EZVIZ) or ISAPI. The IP is auto-filled from the NVR when it knows it.
     */
    private fun editDirect() {
        val existing = store.loadDirect(camera.channel)
        val onvifWas = store.loadDirectOnvif(camera.channel)
        val view = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(8), dp(24), 0)
        }
        val host = field("Camera IP (e.g. 192.168.11.201)", existing?.host ?: "", InputType.TYPE_CLASS_TEXT)
        val port = field("Port (ONVIF/HTTP, usually 80)", (existing?.httpPort ?: 80).toString(), InputType.TYPE_CLASS_NUMBER)
        val user = field("Username", existing?.username ?: "admin", InputType.TYPE_CLASS_TEXT)
        val pass = field("Password", existing?.password ?: "",
            InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD)
        val onvif = android.widget.CheckBox(this).apply {
            text = "Use ONVIF (recommended for EZVIZ PTZ)"
            isChecked = if (existing != null) onvifWas else true
        }
        listOf(
            label("Connect directly to the camera for PTZ / zoom"),
            host, port, user, pass, onvif
        ).forEach { view.addView(it) }

        // Auto-fill the camera's IP from the NVR if we don't already have one.
        if (host.text.isNullOrBlank()) {
            Session.isapi?.let { isapi ->
                lifecycleScope.launch {
                    runCatching { isapi.cameraIp(camera.channel) }.getOrNull()
                        ?.let { if (host.text.isNullOrBlank()) host.setText(it) }
                }
            }
        }

        val dlg = MaterialAlertDialogBuilder(this)
            .setTitle("Direct camera connection")
            .setView(view)
            .setPositiveButton("Save") { _, _ ->
                val h = host.text.toString().trim()
                if (h.isEmpty()) { store.saveDirect(camera.channel, null) }
                else store.saveDirect(camera.channel, Nvr(
                    host = h,
                    httpPort = port.text.toString().toIntOrNull() ?: 80,
                    rtspPort = 554,
                    username = user.text.toString().trim(),
                    password = pass.text.toString()
                ), onvif = onvif.isChecked)
                render()
            }
            .setNeutralButton("Clear") { _, _ -> store.saveDirect(camera.channel, null); render() }
            .setNegativeButton("Cancel", null)
            .show()
        DialogIme.attach(dlg, this)
    }

    /** Reports exactly why PTZ works or not for this camera (ONVIF step-by-step, or ISAPI). */
    private fun testPtz() {
        val direct = store.loadDirect(camera.channel)
        val onvif = store.loadDirectOnvif(camera.channel)
        val dlg = MaterialAlertDialogBuilder(this)
            .setTitle("Testing PTZ…")
            .setMessage("Contacting camera…")
            .setPositiveButton("Close", null)
            .show()
        lifecycleScope.launch {
            val msg = when {
                direct != null && onvif ->
                    OnvifPtz(direct.host, direct.httpPort, direct.username, direct.password).diagnose()
                direct != null -> {
                    val ok = runCatching { IsapiClient(direct).supportsPtz(1) }.getOrDefault(false)
                    if (ok) "Direct ISAPI: camera reports PTZ. D-pad should move it."
                    else "Direct ISAPI: camera did not report PTZ. For EZVIZ, switch the direct " +
                        "connection to ONVIF instead."
                }
                else -> {
                    val ok = runCatching { Session.isapi?.supportsPtz(camera.channel) ?: false }.getOrDefault(false)
                    if (ok) "NVR reports this channel supports PTZ. D-pad should move it."
                    else "NVR does not expose PTZ for this channel. Set a direct ONVIF connection " +
                        "to the camera (button above) to control it."
                }
            }
            dlg.setMessage(msg)
        }
    }

    // ---- Playback / snapshot / rename / info -------------------------------

    private fun openPlayback() {
        startActivity(Intent(this, PlaybackActivity::class.java)
            .putExtra(PlaybackActivity.EXTRA_CHANNEL, camera.channel))
    }

    private fun takeSnapshot() {
        val isapi = Session.isapi ?: return
        lifecycleScope.launch {
            val bytes = isapi.snapshot(camera.channel)
            if (bytes == null) { toast("Snapshot failed"); return@launch }
            val dir = getExternalFilesDir("Snapshots") ?: filesDir
            val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
            val file = File(dir, "${camera.name}_$stamp.jpg".replace(' ', '_'))
            runCatching { file.writeBytes(bytes) }
                .onSuccess { toast("Saved: ${file.name}") }
                .onFailure { toast("Could not save snapshot") }
        }
    }

    private fun rename() {
        val input = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_TEXT
            setText(camera.name)
            setSelection(text.length)
        }
        val dlg = MaterialAlertDialogBuilder(this)
            .setTitle("Rename camera")
            .setView(input)
            .setPositiveButton("Save") { _, _ ->
                val name = input.text.toString().trim().ifEmpty { camera.name }
                camera = camera.copy(name = name)
                Session.cameras = Session.cameras.map {
                    if (it.channel == camera.channel) camera else it
                }
                store.saveCameras(Session.cameras)
                Session.gridDirty = true
                render()
            }
            .setNegativeButton("Cancel", null)
            .show()
        DialogIme.attach(dlg, this)
    }

    private fun showInfo() {
        val d = store.loadDirect(camera.channel)
        val msg = buildString {
            append("Name: ${camera.name}\n")
            append("Channel: ${camera.channel}\n")
            append("Main stream: ${camera.mainStreamCode}\n")
            append("Sub stream: ${camera.subStreamCode}\n")
            append("Status: ${if (camera.online) "Online" else "Offline"}\n")
            append("PTZ (per NVR list): ${if (camera.ptzSupported) "Reported" else "Not reported"}\n")
            append("Direct connection: ${if (d == null) "Not configured" else "${d.host}:${d.httpPort}"}")
        }
        MaterialAlertDialogBuilder(this)
            .setTitle("Camera info")
            .setMessage(msg)
            .setPositiveButton("Close", null)
            .show()
    }

    // ---- helpers -----------------------------------------------------------

    private fun field(hintText: String, value: String, type: Int) = EditText(this).apply {
        hint = hintText
        inputType = type
        setText(value)
    }

    private fun label(text: String) = TextView(this).apply {
        this.text = text
        setPadding(0, dp(4), 0, dp(8))
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun toast(msg: String) =
        android.widget.Toast.makeText(this, msg, android.widget.Toast.LENGTH_SHORT).show()

    companion object {
        const val EXTRA_CHANNEL = "channel"
    }
}
