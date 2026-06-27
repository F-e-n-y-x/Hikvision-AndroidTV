package com.hiktv.viewer.ui.setup

import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.hiktv.viewer.R
import com.hiktv.viewer.core.Session
import com.hiktv.viewer.data.model.Nvr
import com.hiktv.viewer.data.store.NvrStore
import com.hiktv.viewer.databinding.ActivitySetupBinding
import com.hiktv.viewer.ui.grid.GridActivity
import kotlinx.coroutines.launch

/**
 * Launcher screen. If an NVR is already saved it connects and jumps straight to the live
 * grid (the "open and see my cameras" requirement). Otherwise it shows the connection form.
 *
 * Pass EXTRA_EDIT = true to force the form (used by the "Connection settings" action).
 */
class SetupActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySetupBinding
    private lateinit var store: NvrStore
    private var forceEdit = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySetupBinding.inflate(layoutInflater)
        setContentView(binding.root)
        store = NvrStore(this)

        forceEdit = intent.getBooleanExtra(EXTRA_EDIT, false)
        if (store.hasNvr() && !forceEdit) {
            store.load()?.let {
                Session.connect(it)
                openGrid()
                return
            }
        }

        store.load()?.let { prefill(it) }
        binding.btnConnect.setOnClickListener { onConnect() }
        binding.hostEdit.requestFocus()
        ensurePermThenOfferRestore()
    }

    /** A fresh install needs READ permission to see the backup file a previous install wrote. */
    private fun ensurePermThenOfferRestore() {
        val needsPerm = Build.VERSION.SDK_INT <= 32 &&
            ContextCompat.checkSelfPermission(this, android.Manifest.permission.READ_EXTERNAL_STORAGE) !=
            PackageManager.PERMISSION_GRANTED
        if (needsPerm) {
            ActivityCompat.requestPermissions(
                this, arrayOf(android.Manifest.permission.READ_EXTERNAL_STORAGE), PERM_RESTORE)
        } else {
            offerRestoreIfAvailable()
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int, permissions: Array<out String>, grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == PERM_RESTORE) offerRestoreIfAvailable()
    }

    /** On a fresh install, if a settings backup exists in Downloads, offer one-tap restore. */
    private fun offerRestoreIfAvailable() {
        lifecycleScope.launch {
            val json = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                runCatching { com.hiktv.viewer.util.BackupManager.import(this@SetupActivity) }.getOrNull()
            } ?: return@launch
            binding.btnRestore.visibility = View.VISIBLE
            binding.btnRestore.setOnClickListener { doRestore(json) }
            if (!store.hasNvr()) {
                setStatus("Backup found — tap “Restore from backup” to bring everything back.", error = false)
            }
        }
    }

    private fun doRestore(json: String) {
        runCatching { store.importJson(json) }
        val nvr = store.load()
        if (nvr == null) { setStatus("Backup has no NVR connection.", error = true); return }
        Session.connect(nvr)
        Session.cameras = store.loadCameras()
        Session.gridDirty = true
        setStatus("Restored. Loading cameras…", error = false)
        openGrid()
    }

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
            val err = Session.isapi!!.testConnection()
            if (err != null) {
                setBusy(false)
                setStatus(err, error = true)
                return@launch
            }
            store.save(nvr)
            // Force a re-discovery against the (possibly new) NVR on the grid.
            Session.cameras = emptyList()
            Session.gridDirty = true
            if (forceEdit) {
                // Launched from the grid as a child — just return to it.
                finish()
            } else {
                setStatus("Connected. Loading cameras…", error = false)
                openGrid()
            }
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
        binding.statusText.setTextColor(
            getColor(if (error) R.color.motion else R.color.accent)
        )
        binding.statusText.visibility = View.VISIBLE
    }

    companion object {
        const val EXTRA_EDIT = "edit"
        private const val PERM_RESTORE = 71
    }
}
