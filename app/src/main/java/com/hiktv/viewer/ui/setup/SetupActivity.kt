package com.hiktv.viewer.ui.setup

import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.text.InputType
import android.text.format.Formatter
import android.view.LayoutInflater
import android.view.View
import android.widget.EditText
import androidx.activity.addCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.hiktv.viewer.util.BackupCrypto
import com.hiktv.viewer.util.DialogIme
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.hiktv.viewer.R
import com.hiktv.viewer.core.Session
import com.hiktv.viewer.data.model.Nvr
import com.hiktv.viewer.data.store.NvrStore
import com.hiktv.viewer.databinding.ActivitySetupBinding
import com.hiktv.viewer.databinding.RowSettingBinding
import com.hiktv.viewer.ui.grid.GridActivity
import com.hiktv.viewer.util.BackupManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * First-run wizard, built for a TV remote:
 *   Welcome → (Set up: Server → Sign in)  or  (Restore: pick a backup file)
 *
 * If an NVR is already saved it connects and jumps straight to the grid. Launched with
 * EXTRA_EDIT = true (from "Connection settings") it goes straight to the server step.
 */
class SetupActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySetupBinding
    private lateinit var store: NvrStore
    private var forceEdit = false

    // Legacy READ_EXTERNAL_STORAGE prompt for restoring a backup from Downloads on API <= 32.
    // We proceed to the restore picker regardless of the grant outcome, matching prior behavior.
    private val restorePermLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { openRestore() }

    private val pickFile = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri ?: return@registerForActivityResult
        lifecycleScope.launch {
            val json = withContext(Dispatchers.IO) {
                runCatching { contentResolver.openInputStream(uri)?.use { it.readBytes().toString(Charsets.UTF_8) } }
                    .getOrNull()
            }
            if (json != null) doRestore(json)
            else setRestoreStatus("Couldn't read that file.")
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySetupBinding.inflate(layoutInflater)
        setContentView(binding.root)
        store = NvrStore(this)

        forceEdit = intent.getBooleanExtra(EXTRA_EDIT, false)
        if (store.hasNvr() && !forceEdit) {
            store.load()?.let { Session.connect(it); openGrid(); return }
        }

        store.load()?.let { prefill(it) }
        wireButtons()

        if (forceEdit) showStep(STEP_SERVER) else showStep(STEP_WELCOME)

        // BACK steps the wizard back instead of exiting (unless we're on the welcome step).
        onBackPressedDispatcher.addCallback(this) {
            when (binding.flipper.displayedChild) {
                STEP_CREDS -> showStep(STEP_SERVER)
                STEP_RESTORE -> showStep(STEP_WELCOME)
                STEP_SERVER -> if (!forceEdit) showStep(STEP_WELCOME) else {
                    isEnabled = false; onBackPressedDispatcher.onBackPressed()
                }
                else -> { isEnabled = false; onBackPressedDispatcher.onBackPressed() }
            }
        }
    }

    private fun wireButtons() = with(binding) {
        btnNew.setOnClickListener { showStep(STEP_SERVER) }
        btnRestoreWelcome.setOnClickListener { ensurePermThenRestore() }
        btnBack0.setOnClickListener { showStep(STEP_WELCOME) }
        btnNext.setOnClickListener {
            if (hostEdit.text.isNullOrBlank()) setStatus("Enter the NVR IP address", error = true)
            else showStep(STEP_CREDS)
        }
        btnBack1.setOnClickListener { showStep(STEP_SERVER) }
        btnConnect.setOnClickListener { onConnect() }
        btnBackRestore.setOnClickListener { showStep(STEP_WELCOME) }
        btnBrowse.setOnClickListener {
            runCatching { pickFile.launch(arrayOf("application/json", "text/plain", "*/*")) }
                .onFailure { setRestoreStatus("No file browser on this TV — use a found backup above.") }
        }
    }

    private fun showStep(step: Int) {
        binding.flipper.displayedChild = step
        val focusView = when (step) {
            STEP_WELCOME -> binding.btnNew
            STEP_SERVER -> binding.hostEdit
            STEP_CREDS -> binding.userEdit
            else -> binding.btnBackRestore
        }
        focusView.post { focusView.requestFocus() }
    }

    // ---- Restore ----------------------------------------------------------

    private fun ensurePermThenRestore() {
        val needsPerm = Build.VERSION.SDK_INT <= 32 &&
            ContextCompat.checkSelfPermission(this, android.Manifest.permission.READ_EXTERNAL_STORAGE) !=
            PackageManager.PERMISSION_GRANTED
        if (needsPerm) {
            restorePermLauncher.launch(android.Manifest.permission.READ_EXTERNAL_STORAGE)
        } else {
            openRestore()
        }
    }

    private fun openRestore() {
        showStep(STEP_RESTORE)
        populateBackups()
    }

    /** List every *.json in Downloads (newest first); the app's own backup floats to the top. */
    private fun populateBackups() {
        binding.restoreStatus.visibility = View.GONE
        val container = binding.restoreList
        container.removeAllViews()
        val files = findBackupFiles()
        binding.restoreEmpty.visibility = if (files.isEmpty()) View.VISIBLE else View.GONE

        val inflater = LayoutInflater.from(this)
        files.forEachIndexed { i, f ->
            val row = RowSettingBinding.inflate(inflater, container, false)
            row.rowTitle.text = if (f.name == BackupManager.FILE_NAME) "Hik TV Viewer backup" else f.name
            row.rowSubtitle.text = "${f.name}  ·  ${Formatter.formatShortFileSize(this, f.length())}"
            row.root.setOnClickListener { confirmAndRestore(f) }
            container.addView(row.root)
            if (i == 0) row.root.post { row.root.requestFocus() }
        }
    }

    private fun findBackupFiles(): List<File> {
        val dir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
        val all = runCatching {
            dir?.listFiles { f -> f.isFile && f.canRead() && f.name.endsWith(".json", true) }?.toList()
        }.getOrNull().orEmpty()
        // App's own backup first, then newest.
        return all.sortedWith(
            compareByDescending<File> { it.name == BackupManager.FILE_NAME }
                .thenByDescending { it.lastModified() }
        )
    }

    private fun confirmAndRestore(file: File) {
        val json = runCatching { file.readText() }.getOrNull()
        if (json.isNullOrBlank()) { setRestoreStatus("Couldn't read ${file.name}."); return }
        doRestore(json)
    }

    private fun doRestore(text: String) {
        if (BackupCrypto.isEncrypted(text)) askPinThenRestore(text) else applyRestore(text)
    }

    private fun askPinThenRestore(encrypted: String) {
        val input = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD
            hint = "Backup PIN"
        }
        val dlg = MaterialAlertDialogBuilder(this)
            .setTitle("Encrypted backup")
            .setView(input)
            .setPositiveButton("Unlock") { _, _ ->
                val plain = BackupCrypto.decrypt(encrypted, input.text.toString())
                if (plain == null) setRestoreStatus("Wrong PIN — try again.") else applyRestore(plain)
            }
            .setNegativeButton("Cancel", null)
            .show()
        DialogIme.attach(dlg, this)
    }

    private fun applyRestore(json: String) {
        runCatching { store.importJson(json) }.onFailure {
            setRestoreStatus("That file isn't a valid backup."); return
        }
        val nvr = store.load()
        if (nvr == null) { setRestoreStatus("Backup has no NVR connection."); return }
        Session.connect(nvr)
        Session.cameras = store.loadCameras()
        Session.gridDirty = true
        openGrid()
    }

    private fun setRestoreStatus(msg: String) {
        binding.restoreStatus.text = msg
        binding.restoreStatus.visibility = View.VISIBLE
    }

    // ---- Connect (server + credentials) -----------------------------------

    private fun prefill(nvr: Nvr) = with(binding) {
        hostEdit.setText(nvr.host)
        httpPortEdit.setText(nvr.httpPort.toString())
        rtspPortEdit.setText(nvr.rtspPort.toString())
        userEdit.setText(nvr.username)
        passEdit.setText(nvr.password)
        httpsSwitch.isChecked = nvr.useHttps
    }

    private fun onConnect() {
        val nvr = readForm() ?: run {
            setStatus("Please fill host, username and password", error = true)
            return
        }
        setBusy(true)
        setStatus("Connecting to NVR…", error = false)

        Session.connect(nvr)
        lifecycleScope.launch {
            // testConnection() returns null on success; only substitute a message if connect()
            // failed to create the client at all (don't turn a null-success into a false error).
            val client = Session.isapi
            val err = if (client == null) "Could not initialise connection" else client.testConnection()
            if (err != null) {
                setBusy(false)
                setStatus(err, error = true)
                return@launch
            }
            store.save(nvr)
            Session.cameras = emptyList()
            Session.gridDirty = true
            if (forceEdit) finish()
            else { setStatus("Connected. Loading cameras…", error = false); openGrid() }
        }
    }

    private fun readForm(): Nvr? {
        val nvr = Nvr(
            host = binding.hostEdit.text.toString().trim(),
            httpPort = binding.httpPortEdit.text.toString().toIntOrNull() ?: 80,
            rtspPort = binding.rtspPortEdit.text.toString().toIntOrNull() ?: 554,
            username = binding.userEdit.text.toString().trim(),
            password = binding.passEdit.text.toString(),
            useHttps = binding.httpsSwitch.isChecked
        )
        return if (nvr.isValid() && nvr.password.isNotEmpty()) nvr else null
    }

    private fun openGrid() {
        startActivity(Intent(this, GridActivity::class.java))
        finish()
    }

    private fun setBusy(busy: Boolean) {
        binding.progress.visibility = if (busy) View.VISIBLE else View.GONE
        binding.btnConnect.isEnabled = !busy
    }

    private fun setStatus(msg: String, error: Boolean) {
        binding.statusText.text = msg
        binding.statusText.setTextColor(getColor(if (error) R.color.motion else R.color.accent))
        binding.statusText.visibility = View.VISIBLE
    }

    companion object {
        const val EXTRA_EDIT = "edit"
        private const val STEP_WELCOME = 0
        private const val STEP_SERVER = 1
        private const val STEP_CREDS = 2
        private const val STEP_RESTORE = 3
    }
}
