package com.hiktv.viewer.ui.settings

import android.content.Intent
import android.os.Bundle
import android.text.InputType
import android.view.LayoutInflater
import android.widget.EditText
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import com.hiktv.viewer.core.Session
import com.hiktv.viewer.data.model.Camera
import com.hiktv.viewer.data.store.NvrStore
import com.hiktv.viewer.databinding.ActivitySettingsBinding
import com.hiktv.viewer.databinding.RowSettingBinding
import com.hiktv.viewer.ui.playback.PlaybackActivity
import com.hiktv.viewer.ui.setup.SetupActivity
import kotlinx.coroutines.launch

/**
 * Full-screen settings page (replaces the old pop-up menu). Each row performs an action;
 * actions that change the camera list / layout set [Session.gridDirty] and finish, so the
 * grid re-applies on resume.
 */
class SettingsActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySettingsBinding
    private lateinit var store: NvrStore

    private data class Row(val title: String, val subtitle: String, val action: () -> Unit)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)
        store = NvrStore(this)

        val nvr = Session.nvr
        binding.settingsSub.text = nvr?.let { "${it.host}  •  ${Session.cameras.size} cameras" } ?: ""

        val rows = listOfNotNull(
            Row("Connection", nvr?.host ?: "Set up the NVR connection") { openConnection() },
            Row("Refresh cameras", "Re-scan the NVR (uses camera names)") { refresh() },
            Row("Scan channels", "Force per-channel detection") { scan() },
            Row("Set number of cameras", "Manually define channels 1..N") { manualCount() },
            Row("Grid layout", layoutLabel()) { chooseLayout() },
            Row("Video decoding", decoderLabel()) { chooseDecoder() },
            Row("Video rendering", renderLabel()) { chooseRender() },
            Row("Optimize live (smooth)", "Set sub-streams to H.264 for a lighter, smoother wall") { optimizeStreams() },
            if (store.hasSubStreamBackup())
                Row("Restore sub-streams", "Undo Optimize — put grid sub-streams back") { restoreSubStreams() }
            else null,
            Row("Alerts", alertsLabel()) { chooseAlerts() },
            Row("Diagnostics", "Check what the NVR returns") { diagnostics() },
            Row("Last crash", crashLabel()) { showLastCrash() },
            Row("Playback", "View recorded footage") { playback() },
            Row("Backup settings", "Save everything to Downloads") { backup() },
            Row("Restore settings", "Re-import a saved backup") { restore() },
            Row("About", appVersion()) { /* info only */ }
        )

        val inflater = LayoutInflater.from(this)
        rows.forEachIndexed { i, row ->
            val rb = RowSettingBinding.inflate(inflater, binding.rowContainer, false)
            rb.rowTitle.text = row.title
            rb.rowSubtitle.text = row.subtitle
            rb.root.setOnClickListener { row.action() }
            binding.rowContainer.addView(rb.root)
            if (i == 0) rb.root.post { rb.root.requestFocus() }
        }
    }

    private fun openConnection() {
        startActivity(Intent(this, SetupActivity::class.java).putExtra(SetupActivity.EXTRA_EDIT, true))
    }

    private fun refresh() {
        Session.cameras = emptyList()
        Session.gridDirty = true
        finish()
    }

    private fun scan() {
        val isapi = Session.isapi ?: return
        val dlg = MaterialAlertDialogBuilder(this)
            .setTitle("Scanning channels").setMessage("Probing the NVR…").setCancelable(false).show()
        lifecycleScope.launch {
            val cams = isapi.autoDetectByProbe()
            dlg.dismiss()
            if (cams.isEmpty()) {
                MaterialAlertDialogBuilder(this@SettingsActivity)
                    .setTitle("Nothing detected")
                    .setMessage("No channels responded. Try Diagnostics, or set the count manually.")
                    .setPositiveButton("OK", null).show()
            } else {
                Session.cameras = cams
                store.saveCameras(cams)
                Session.gridDirty = true
                finish()
            }
        }
    }

    private fun manualCount() {
        val input = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_NUMBER
            hint = "Number of cameras"
            setText(store.cachedChannelCount.takeIf { it > 0 }?.toString() ?: "")
        }
        val dlg = MaterialAlertDialogBuilder(this)
            .setTitle("How many cameras?")
            .setView(input)
            .setPositiveButton("OK") { _, _ ->
                input.text.toString().toIntOrNull()?.coerceIn(1, 64)?.let { n ->
                    val cams = (1..n).map {
                        Camera(it, "Camera ${it.toString().padStart(2, '0')}", online = true)
                    }
                    Session.cameras = cams
                    store.saveCameras(cams)
                    store.cachedChannelCount = n
                    Session.gridDirty = true
                    finish()
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
        com.hiktv.viewer.util.DialogIme.attach(dlg, this)
    }

    private fun layoutLabel(): String = when (store.gridColumns) {
        0 -> "Automatic"; 1 -> "Single"; else -> "${store.gridColumns} columns"
    }

    private fun chooseLayout() {
        val labels = arrayOf("Automatic", "Single (1)", "2 columns", "3 columns", "4 columns")
        val cols = intArrayOf(0, 1, 2, 3, 4)
        MaterialAlertDialogBuilder(this)
            .setTitle("Grid layout")
            .setItems(labels) { _, which ->
                store.gridColumns = cols[which]
                Session.gridDirty = true
                finish()
            }
            .show()
    }

    private fun decoderLabel(): String = when (store.decoderMode) {
        1 -> "Hardware (may reboot weak TVs)"
        2 -> "Software (most compatible)"
        else -> "Balanced — safe (recommended)"
    }

    private fun chooseDecoder() {
        val labels = arrayOf(
            "Balanced — grid software, fullscreen hardware (recommended, safe)",
            "Hardware — grid hardware too (faster, but can crash/reboot weak H.265 TVs)",
            "Software — everything software (most compatible, higher CPU)"
        )
        MaterialAlertDialogBuilder(this)
            .setTitle("Video decoding")
            .setItems(labels) { _, which ->
                store.decoderMode = which
                Session.gridDirty = true
                finish()
            }
            .show()
    }

    private fun renderLabel(): String =
        if (store.directRender) "Direct / fast (Amlogic, Mi Box)" else "Compatible (recommended)"

    private fun chooseRender() {
        val labels = arrayOf(
            "Compatible — fixes green H.265 video (most TVs, e.g. MiTV)",
            "Direct / fast — use if fullscreen is laggy, green or crashes (e.g. Mi Box)"
        )
        MaterialAlertDialogBuilder(this)
            .setTitle("Video rendering")
            .setMessage("If full-screen video lags, turns green or crashes on this device, switch " +
                "to Direct. Set per-TV — it doesn't affect your other TVs.")
            .setItems(labels) { _, which ->
                store.directRender = which == 1
                Session.gridDirty = true
                finish()
            }
            .show()
    }

    private fun alertsLabel(): String {
        val n = store.alertChannels.size
        return if (n == 0) "Off — pick cameras to be alerted about" else "On for $n camera(s)"
    }

    private fun chooseAlerts() {
        val cams = Session.cameras
        if (cams.isEmpty()) return
        val names = cams.map { it.name }.toTypedArray()
        val checked = cams.map { it.channel in store.alertChannels }.toBooleanArray()
        MaterialAlertDialogBuilder(this)
            .setTitle("Alert me about motion / area events on…")
            .setMultiChoiceItems(names, checked) { _, which, isChecked -> checked[which] = isChecked }
            .setPositiveButton("Save") { _, _ ->
                store.alertChannels = cams.filterIndexed { i, _ -> checked[i] }.map { it.channel }.toSet()
                com.hiktv.viewer.util.Notifications.ensureChannel(this)
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    /** One-tap: switch every camera's grid sub-stream to lightweight H.264 @ 15 fps. */
    private fun optimizeStreams() {
        val isapi = Session.isapi ?: return
        val cams = Session.cameras
        if (cams.isEmpty()) return
        MaterialAlertDialogBuilder(this)
            .setTitle("Optimize live wall?")
            .setMessage("Sets each camera's grid sub-stream to plain H.264 @ 15 fps — smart codec " +
                "(H.264+/H.265+) off, steady bitrate — so the wall stays smooth and artifact-free " +
                "and won't overload the TV's decoder. Fullscreen / main-stream quality is unchanged.")
            .setPositiveButton("Optimize") { _, _ ->
                val dlg = MaterialAlertDialogBuilder(this)
                    .setTitle("Optimizing…").setMessage("Updating sub-streams…")
                    .setCancelable(false).show()
                lifecycleScope.launch {
                    // Capture the current sub-stream config first so "Restore sub-streams" can revert
                    // (keep the earliest original per channel across repeated Optimize runs).
                    val originals = store.loadSubStreamOriginals().toMutableMap()
                    for (c in cams) if (!originals.containsKey(c.channel)) {
                        isapi.readSubStreamXml(c.channel)?.let { originals[c.channel] = it }
                    }
                    if (originals.isNotEmpty()) store.saveSubStreamOriginals(originals)

                    var ok = 0
                    for (c in cams) if (isapi.optimizeSubStream(c.channel)) ok++
                    dlg.dismiss()
                    Session.gridDirty = true
                    MaterialAlertDialogBuilder(this@SettingsActivity)
                        .setTitle("Done")
                        .setMessage("Updated $ok of ${cams.size} cameras. The wall now uses the " +
                            "lighter H.264 sub-stream — reopen the grid to see it. You can undo this " +
                            "later with “Restore sub-streams”.")
                        .setPositiveButton("OK") { _, _ -> finish() }
                        .show()
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    /** Undo Optimize: PUT each saved original grid sub-stream config back to the NVR. */
    private fun restoreSubStreams() {
        val isapi = Session.isapi ?: return
        val originals = store.loadSubStreamOriginals()
        if (originals.isEmpty()) { toast("Nothing to restore"); return }
        MaterialAlertDialogBuilder(this)
            .setTitle("Restore sub-streams?")
            .setMessage("Puts each camera's grid sub-stream back to exactly how it was before you " +
                "ran Optimize (${originals.size} camera(s)).")
            .setPositiveButton("Restore") { _, _ ->
                val dlg = MaterialAlertDialogBuilder(this)
                    .setTitle("Restoring…").setMessage("Reverting sub-streams…").setCancelable(false).show()
                lifecycleScope.launch {
                    var ok = 0
                    for ((ch, xml) in originals) if (isapi.writeSubStreamXml(ch, xml)) ok++
                    if (ok > 0) store.clearSubStreamBackup()
                    dlg.dismiss()
                    Session.gridDirty = true
                    MaterialAlertDialogBuilder(this@SettingsActivity)
                        .setTitle("Done")
                        .setMessage("Restored $ok of ${originals.size} cameras. Reopen the grid to see it.")
                        .setPositiveButton("OK") { _, _ -> finish() }
                        .show()
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun diagnostics() {
        val isapi = Session.isapi ?: return
        val dlg = MaterialAlertDialogBuilder(this)
            .setTitle("Diagnostics").setMessage("Querying NVR…")
            .setPositiveButton("Close", null).show()
        lifecycleScope.launch { dlg.setMessage(isapi.diagnose()) }
    }

    private fun crashLabel(): String =
        if (com.hiktv.viewer.util.CrashLog.last(this) != null) "Tap to view the last recorded crash"
        else "None recorded"

    private fun showLastCrash() {
        val text = com.hiktv.viewer.util.CrashLog.last(this)
        if (text == null) {
            MaterialAlertDialogBuilder(this).setTitle("Last crash")
                .setMessage("No crash has been recorded on this TV.\n\nNote: native video-driver " +
                    "crashes aren't captured here — for those use `adb logcat -b crash -d`.")
                .setPositiveButton("OK", null).show()
            return
        }
        MaterialAlertDialogBuilder(this).setTitle("Last crash")
            .setMessage(text)
            .setPositiveButton("Close", null)
            .setNegativeButton("Clear") { _, _ -> com.hiktv.viewer.util.CrashLog.clear(this) }
            .show()
    }

    private fun playback() {
        val cams = Session.cameras.filter { it.online }
        if (cams.isEmpty()) return
        MaterialAlertDialogBuilder(this)
            .setTitle("Playback — pick a camera")
            .setItems(cams.map { it.name }.toTypedArray()) { _, which ->
                startActivity(Intent(this, PlaybackActivity::class.java)
                    .putExtra(PlaybackActivity.EXTRA_CHANNEL, cams[which].channel))
            }
            .show()
    }

    private fun backup() {
        MaterialAlertDialogBuilder(this)
            .setTitle("Backup")
            .setMessage("The backup contains your NVR and EZVIZ passwords. Protect it with a PIN?")
            .setPositiveButton("Set a PIN") { _, _ -> askPinThenBackup() }
            .setNegativeButton("No encryption") { _, _ -> doBackup(null) }
            .setNeutralButton("Cancel", null)
            .show()
    }

    private fun askPinThenBackup() {
        val input = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            hint = "PIN or passphrase (4+ characters)"
        }
        val dlg = MaterialAlertDialogBuilder(this)
            .setTitle("Backup PIN")
            .setMessage("You'll enter this to restore. A longer passphrase (letters + numbers) is " +
                "far safer than a short numeric PIN. Don't forget it — it can't be recovered.")
            .setView(input)
            .setPositiveButton("Encrypt & save") { _, _ ->
                val pin = input.text.toString()
                if (pin.length < 4) toast("Use at least 4 characters") else doBackup(pin)
            }
            .setNegativeButton("Cancel", null)
            .show()
        com.hiktv.viewer.util.DialogIme.attach(dlg, this)
    }

    private fun doBackup(pin: String?) {
        val progress = MaterialAlertDialogBuilder(this)
            .setTitle("Backing up…").setMessage("Encrypting and saving…").setCancelable(false).show()
        lifecycleScope.launch {
            // Off the main thread: PBKDF2 (now ~210k rounds) + AES + Downloads file write can exceed
            // the ANR budget on a weak TV CPU.
            val msg = withContext(Dispatchers.IO) {
                val raw = store.exportJson()
                val payload = if (pin == null) raw
                    else com.hiktv.viewer.util.BackupCrypto.encrypt(raw, pin)
                runCatching { com.hiktv.viewer.util.BackupManager.export(this@SettingsActivity, payload) }.fold(
                    {
                        "Saved to $it" + (if (pin != null) "  (PIN-encrypted)" else "") +
                            "\n\nKeep this file — restore it after a reinstall to avoid re-entering everything."
                    },
                    { "Backup failed: ${it.message}" }
                )
            }
            progress.dismiss()
            MaterialAlertDialogBuilder(this@SettingsActivity).setTitle("Backup").setMessage(msg)
                .setPositiveButton("OK", null).show()
        }
    }

    private fun restore() {
        val text = runCatching { com.hiktv.viewer.util.BackupManager.import(this) }.getOrNull()
        if (text == null) {
            MaterialAlertDialogBuilder(this).setTitle("Restore")
                .setMessage("No backup found (Downloads/${com.hiktv.viewer.util.BackupManager.FILE_NAME}).")
                .setPositiveButton("OK", null).show()
            return
        }
        if (com.hiktv.viewer.util.BackupCrypto.isEncrypted(text)) askPinThenRestore(text)
        else confirmRestore(text)
    }

    private fun askPinThenRestore(encrypted: String) {
        val input = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            hint = "Backup PIN / passphrase"
        }
        val dlg = MaterialAlertDialogBuilder(this)
            .setTitle("Encrypted backup")
            .setView(input)
            .setPositiveButton("Unlock") { _, _ ->
                val pin = input.text.toString()
                val progress = MaterialAlertDialogBuilder(this)
                    .setTitle("Unlocking…").setMessage("Decrypting backup…").setCancelable(false).show()
                lifecycleScope.launch {
                    val plain = withContext(Dispatchers.Default) {
                        com.hiktv.viewer.util.BackupCrypto.decrypt(encrypted, pin)
                    }
                    progress.dismiss()
                    if (plain == null) toast("Wrong PIN") else confirmRestore(plain)
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
        com.hiktv.viewer.util.DialogIme.attach(dlg, this)
    }

    private fun confirmRestore(json: String) {
        MaterialAlertDialogBuilder(this)
            .setTitle("Restore settings?")
            .setMessage("This replaces current settings with the saved backup and reconnects.")
            .setPositiveButton("Restore") { _, _ ->
                val progress = MaterialAlertDialogBuilder(this)
                    .setTitle("Restoring…").setMessage("Applying settings…").setCancelable(false).show()
                lifecycleScope.launch {
                    // EncryptedSharedPreferences rewrite (crypto + disk) off the main thread.
                    val nvr = withContext(Dispatchers.IO) {
                        runCatching { store.importJson(json) }
                        store.load()
                    }
                    nvr?.let { Session.connect(it) }
                    Session.cameras = withContext(Dispatchers.IO) { store.loadCameras() }
                    Session.gridDirty = true
                    progress.dismiss()
                    startActivity(Intent(this@SettingsActivity, SetupActivity::class.java)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
                    finish()
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun toast(m: String) =
        android.widget.Toast.makeText(this, m, android.widget.Toast.LENGTH_SHORT).show()

    private fun appVersion(): String =
        runCatching { "Hik TV Viewer ${packageManager.getPackageInfo(packageName, 0).versionName}" }
            .getOrDefault("Hik TV Viewer")
}
