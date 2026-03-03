package com.jhosue.utorrent

import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.view.MenuItem
import android.widget.EditText
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import com.jhosue.utorrent.databinding.ActivitySettingsBinding

object Prefs {
    private const val PREFS_NAME = "utorrent_prefs"
    const val KEY_DARK_MODE = "dark_mode"
    const val KEY_WIFI_ONLY = "wifi_only"
    const val KEY_DL_LIMIT = "dl_limit"
    const val KEY_UL_LIMIT = "ul_limit"

    fun get(context: Context): SharedPreferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun isDarkMode(ctx: Context) = get(ctx).getBoolean(KEY_DARK_MODE, false)
    fun setDarkMode(ctx: Context, v: Boolean) = get(ctx).edit().putBoolean(KEY_DARK_MODE, v).apply()

    fun isWifiOnly(ctx: Context) = get(ctx).getBoolean(KEY_WIFI_ONLY, false)
    fun setWifiOnly(ctx: Context, v: Boolean) = get(ctx).edit().putBoolean(KEY_WIFI_ONLY, v).apply()

    fun getDlLimit(ctx: Context) = get(ctx).getInt(KEY_DL_LIMIT, 0)
    fun setDlLimit(ctx: Context, v: Int) = get(ctx).edit().putInt(KEY_DL_LIMIT, v).apply()

    fun getUlLimit(ctx: Context) = get(ctx).getInt(KEY_UL_LIMIT, 0)
    fun setUlLimit(ctx: Context, v: Int) = get(ctx).edit().putInt(KEY_UL_LIMIT, v).apply()

    const val KEY_STORAGE_PATH = "custom_save_path"
    const val KEY_STORAGE_URI_STRING = "custom_save_path_uri"
    fun getStorageUri(ctx: Context): String? = get(ctx).getString(KEY_STORAGE_URI_STRING, null)
    fun setStorageUri(ctx: Context, uri: String) = get(ctx).edit().putString(KEY_STORAGE_URI_STRING, uri).apply()
}

class SettingsActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySettingsBinding

    private val selectDirectoryLauncher = registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) {
            val takeFlags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            contentResolver.takePersistableUriPermission(uri, takeFlags)
            
            Prefs.setStorageUri(this, uri.toString())
            updateStorageUI()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setSupportActionBar(binding.toolbarSettings)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.setDisplayShowHomeEnabled(true)

        // Init UI from Prefs
        binding.switchDarkMode.isChecked = Prefs.isDarkMode(this)
        binding.switchWifiOnly.isChecked = Prefs.isWifiOnly(this)
        updateLimitSummaries()

        // 1. Dark Mode
        binding.switchDarkMode.setOnCheckedChangeListener { buttonView, isChecked ->
            if (buttonView.isPressed) { // Only apply if user actually clicked it
                Prefs.setDarkMode(this, isChecked)
                AppCompatDelegate.setDefaultNightMode(
                    if (isChecked) AppCompatDelegate.MODE_NIGHT_YES else AppCompatDelegate.MODE_NIGHT_NO
                )
            }
        }

        // 2. Wi-Fi Only
        binding.switchWifiOnly.setOnCheckedChangeListener { _, isChecked ->
            Prefs.setWifiOnly(this, isChecked)
            // Trigger a check in Repository/Service?
            // For now, if we toggle while on mobile, the service logic (to be added) handles it.
            // If already on mobile and we enable "WifiOnly", we should pause immediately.
            // But Service is the one checking network.
            // We can restart service to force a check, but that's heavy.
            // We'll let the NetworkCallback handle it (Next step).
        }
        
        // 3. Limits
        binding.llDownloadLimit.setOnClickListener { showLimitDialog(true) }
        binding.llUploadLimit.setOnClickListener { showLimitDialog(false) }

        // 4. Storage Path
        updateStorageUI()
        binding.llStoragePath.setOnClickListener {
            selectDirectoryLauncher.launch(null)
        }
    }
    
    private fun updateStorageUI() {
        val customUriStr = Prefs.getStorageUri(this)
        if (customUriStr.isNullOrEmpty()) {
            val defaultPath = android.os.Environment.getExternalStoragePublicDirectory(android.os.Environment.DIRECTORY_DOWNLOADS).absolutePath
            binding.tvStoragePathSummary.text = defaultPath
        } else {
            val uri = Uri.parse(customUriStr)
            val path = uri.path?.replace("/tree/primary:", "/storage/emulated/0/") ?: customUriStr
            binding.tvStoragePathSummary.text = path
        }
    }

    private fun updateLimitSummaries() {
        val dl = Prefs.getDlLimit(this)
        val ul = Prefs.getUlLimit(this)
        binding.tvDownloadLimitSummary.text = if (dl == 0) "Unlimited" else "$dl KB/s"
        binding.tvUploadLimitSummary.text = if (ul == 0) "Unlimited" else "$ul KB/s"
    }

    private fun showLimitDialog(isDownload: Boolean) {
        val title = if (isDownload) "Download Limit (KB/s)" else "Upload Limit (KB/s)"
        val current = if (isDownload) Prefs.getDlLimit(this) else Prefs.getUlLimit(this)
        
        val input = EditText(this)
        input.inputType = android.text.InputType.TYPE_CLASS_NUMBER
        input.hint = "0 for unlimited"
        input.setText(if (current > 0) current.toString() else "")

        AlertDialog.Builder(this)
            .setTitle(title)
            .setView(input)
            .setPositiveButton("Save") { _, _ ->
                val str = input.text.toString()
                val limit = str.toIntOrNull() ?: 0
                if (isDownload) Prefs.setDlLimit(this, limit) else Prefs.setUlLimit(this, limit)
                updateLimitSummaries()
                
                // Apply immediately to Repository
                // Note: Repo is singleton object, so we can call it directly
                // But ensure it's started.
                TorrentRepository.setLimits(
                    Prefs.getDlLimit(this) * 1024, 
                    Prefs.getUlLimit(this) * 1024
                )
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (item.itemId == android.R.id.home) {
            finish()
            return true
        }
        return super.onOptionsItemSelected(item)
    }
}
