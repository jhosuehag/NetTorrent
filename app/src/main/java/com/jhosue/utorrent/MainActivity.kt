package com.jhosue.utorrent

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.Settings
import android.text.Editable
import android.text.TextWatcher
import android.util.Log
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.ActionBarDrawerToggle
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SearchView
import androidx.core.view.GravityCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.navigation.NavigationView
import com.google.android.material.tabs.TabLayout
import com.google.android.material.textfield.TextInputEditText
import com.jhosue.utorrent.databinding.ActivityMainBinding
import kotlinx.coroutines.delay
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import org.libtorrent4j.TorrentInfo

class MainActivity : AppCompatActivity(), NavigationView.OnNavigationItemSelectedListener {

    private lateinit var binding: ActivityMainBinding
    private val torrentViewModel: TorrentViewModel by viewModels()
    private lateinit var downloadsAdapter: DownloadsAdapter
    private val downloadsList = mutableListOf<TorrentUiModel>()
    private lateinit var recyclerView: RecyclerView
    private lateinit var emptyStateView: View

    // Callback invoked when the user picks a .torrent file via the file picker.
    // Updated every time showAddTorrentSheet() creates a new bottom sheet.
    private var onTorrentFilePicked: ((Uri) -> Unit)? = null

    /** File picker launcher — must be registered before onCreate returns. */
    private val torrentFileLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        if (uri != null) {
            onTorrentFilePicked?.invoke(uri)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        androidx.core.view.WindowCompat.setDecorFitsSystemWindows(window, false)

        // ── Request MANAGE_EXTERNAL_STORAGE ────────────────────────────────────
        ensureStoragePermission()

        // ── Request POST_NOTIFICATIONS (Android 13+) ───────────────────────────
        if (Build.VERSION.SDK_INT >= 33) {
            if (androidx.core.content.ContextCompat.checkSelfPermission(this, android.Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                androidx.core.app.ActivityCompat.requestPermissions(this, arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 102)
            }
        }

        // ── Start Background Service (Engine) ──────────────────────────────────
        val serviceIntent = Intent(this, TorrentService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(serviceIntent)
        } else {
            startService(serviceIntent)
        }

        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupToolbar()
        setupDrawer()
        setupListsAndState()
        setupTabs()
        setupFab()
        startUiUpdates()

    androidx.core.view.ViewCompat.setOnApplyWindowInsetsListener(binding.root) { _, insets ->
        val systemBars = insets.getInsets(androidx.core.view.WindowInsetsCompat.Type.systemBars() or androidx.core.view.WindowInsetsCompat.Type.ime())

        val appBarLayout = binding.root.findViewById<com.google.android.material.appbar.AppBarLayout>(R.id.app_bar_layout)
        appBarLayout?.setPadding(0, systemBars.top, 0, 0)

        recyclerView.setPadding(0, 0, 0, systemBars.bottom)
        emptyStateView.setPadding(0, 0, 0, systemBars.bottom)

        val fabLp = binding.fabAdd.layoutParams as android.view.ViewGroup.MarginLayoutParams
        fabLp.bottomMargin = systemBars.bottom + (16 * resources.displayMetrics.density).toInt()
        binding.fabAdd.layoutParams = fabLp

        insets
    }

    checkForPreviousCrash()
    
    // Handle Deep Link if launched via magnet/file
        handleIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent?) {
        if (intent?.action != Intent.ACTION_VIEW) return
        val data = intent.data ?: return

        try {
            if (intent.scheme == "magnet") {
               showAddTorrentSheet(prefillMagnet = data.toString())
            } else {
                // Determine if file (content:// or file://)
                val cachedFile = copyUriToCache(data)
                if (cachedFile != null) {
                    showAddTorrentSheet(prefillFile = cachedFile)
                }
            }
        } catch (e: Exception) {
            Toast.makeText(this, "Error opening link: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    /**
     * On Android 11+, MANAGE_EXTERNAL_STORAGE is required for libtorrent to write
     * to the public Downloads directory via native POSIX I/O (bypasses MediaStore).
     * If not granted, open the system settings page so the user can toggle it on.
     */
    private fun ensureStoragePermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            if (!Environment.isExternalStorageManager()) {
                AlertDialog.Builder(this)
                    .setTitle("Storage Permission Required")
                    .setMessage(
                        "This app needs \"All files access\" to save downloaded torrents " +
                        "to your Downloads folder.\n\nPlease enable it in the next screen."
                    )
                    .setCancelable(false)
                    .setPositiveButton("Open Settings") { _, _ ->
                        val intent = Intent(
                            Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                            Uri.parse("package:$packageName")
                        )
                        startActivity(intent)
                    }
                    .setNegativeButton("Skip") { dialog, _ ->
                        Log.w("MainActivity", "MANAGE_EXTERNAL_STORAGE denied — downloads may fail")
                        dialog.dismiss()
                    }
                    .show()
            }
        } else {
            // Legacy Storage Permission (Android 10 and below)
            if (androidx.core.content.ContextCompat.checkSelfPermission(this, android.Manifest.permission.WRITE_EXTERNAL_STORAGE) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                androidx.core.app.ActivityCompat.requestPermissions(this, arrayOf(android.Manifest.permission.WRITE_EXTERNAL_STORAGE), 101)
            }
        }
    }

    private fun checkForPreviousCrash() {
        val crashFile = java.io.File(filesDir, "last_crash.txt")
        if (crashFile.exists()) {
            val log = crashFile.readText()
            crashFile.delete() // Clear so it doesn't show again
            AlertDialog.Builder(this)
                .setTitle("⚠️ Previous Crash Log")
                .setMessage(log)
                .setPositiveButton("Copy & Close") { dialog, _ ->
                    val clipboard = getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                    clipboard.setPrimaryClip(android.content.ClipData.newPlainText("crash_log", log))
                    android.widget.Toast.makeText(this, "Log copied to clipboard!", android.widget.Toast.LENGTH_SHORT).show()
                    dialog.dismiss()
                }
                .setNegativeButton("Dismiss") { dialog, _ -> dialog.dismiss() }
                .show()
        }
    }

    


    private fun setupToolbar() {
        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayShowTitleEnabled(true)
        supportActionBar?.title = "Torrents"
    }

    private fun setupDrawer() {
        val toggle = ActionBarDrawerToggle(
            this,
            binding.drawerLayout,
            binding.toolbar,
            R.string.navigation_drawer_open,
            R.string.navigation_drawer_close
        )
        binding.drawerLayout.addDrawerListener(toggle)
        toggle.syncState()

        binding.navView.setNavigationItemSelectedListener(this)

        val headerView = binding.navView.getHeaderView(0)
        
        // Settings Button
        val btnSettings = headerView.findViewById<View>(R.id.btn_settings)
        btnSettings?.setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
            binding.drawerLayout.closeDrawer(androidx.core.view.GravityCompat.START)
        }

        // Storage Info
        val tvStorage = headerView.findViewById<TextView>(R.id.tv_storage_info)
        val progressStorage = headerView.findViewById<android.widget.ProgressBar>(R.id.progress_storage)
        
        if (tvStorage != null && progressStorage != null) {
            updateStorageInfo(tvStorage, progressStorage)
            
            // Re-check storage whenever drawer is opened
            binding.drawerLayout.addDrawerListener(object : androidx.drawerlayout.widget.DrawerLayout.SimpleDrawerListener() {
                override fun onDrawerOpened(drawerView: View) {
                    updateStorageInfo(tvStorage, progressStorage)
                }
            })
        }
    }

    private fun updateStorageInfo(tv: TextView, pg: android.widget.ProgressBar) {
        try {
            val path = Environment.getExternalStorageDirectory()
            val stat = android.os.StatFs(path.path)
            val blockSize = stat.blockSizeLong
            val totalBlocks = stat.blockCountLong
            val availableBlocks = stat.availableBlocksLong
            
            val totalBytes = totalBlocks * blockSize
            val availableBytes = availableBlocks * blockSize
            val usedBytes = totalBytes - availableBytes
            
            val percent = if (totalBytes > 0) ((usedBytes * 100) / totalBytes).toInt() else 0
            
            pg.progress = percent
            
            val usedGB = String.format("%.1f", usedBytes / (1024f * 1024f * 1024f))
            val totalGB = String.format("%.1f", totalBytes / (1024f * 1024f * 1024f))
            
            tv.text = "Storage: $usedGB GB / $totalGB GB used"
            
            // Color logic: Red if > 90%
            if (percent > 90) {
                pg.progressTintList = android.content.res.ColorStateList.valueOf(android.graphics.Color.RED)
            } else {
                pg.progressTintList = android.content.res.ColorStateList.valueOf(androidx.core.content.ContextCompat.getColor(this, R.color.primary_green))
            }
        } catch (e: Exception) {
            tv.text = "Storage info unavailable"
        }
    }

    private fun setupListsAndState() {
        recyclerView = RecyclerView(this).apply {
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
            layoutManager = LinearLayoutManager(this@MainActivity)
            visibility = View.GONE
            clipToPadding = false
        }
        binding.contentFrame.addView(recyclerView)

        downloadsAdapter = DownloadsAdapter(
            downloadsList,
            onItemClick = { item -> showDetailsDialog(item) },
            onItemLongClick = { item -> showDeleteDialog(item) },
            onActionClick = { torrent -> torrentViewModel.togglePause(torrent.id, torrent.isPaused) }
        )
        recyclerView.adapter = downloadsAdapter

        val btnStart = findViewById<Button>(R.id.btn_start_downloading)
        emptyStateView = btnStart.parent as View
        
        btnStart.setOnClickListener {
             showAddTorrentSheet()
        }
    }

    private fun setupTabs() {
        binding.tabLayout.addOnTabSelectedListener(object : TabLayout.OnTabSelectedListener {
            override fun onTabSelected(tab: TabLayout.Tab?) {
                currentTabPosition = tab?.position ?: 0
                applyFilters()
            }
            override fun onTabUnselected(tab: TabLayout.Tab?) {}
            override fun onTabReselected(tab: TabLayout.Tab?) {}
        })
    }
    
    private fun setupFab() {
        binding.fabAdd.setOnClickListener {
             showAddTorrentSheet()
        }
    }

    // --- Dialogs & Sheets ---

    private fun showAddTorrentSheet(prefillMagnet: String? = null, prefillFile: File? = null) {
        val sheet = com.google.android.material.bottomsheet.BottomSheetDialog(this)
        val view = layoutInflater.inflate(R.layout.dialog_add_torrent, null)
        sheet.setContentView(view)

        sheet.window?.setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)

        sheet.setOnShowListener { dialog ->
            val bottomSheet = (dialog as com.google.android.material.bottomsheet.BottomSheetDialog).findViewById<android.view.View>(com.google.android.material.R.id.design_bottom_sheet)
            if (bottomSheet != null) {
                val behavior = com.google.android.material.bottomsheet.BottomSheetBehavior.from(bottomSheet)
                behavior.state = com.google.android.material.bottomsheet.BottomSheetBehavior.STATE_EXPANDED
                behavior.skipCollapsed = true
            }
        }

        val etMagnet        = view.findViewById<TextInputEditText>(R.id.et_magnet_link)
        val btnDownload     = view.findViewById<Button>(R.id.btn_start_download)
        val btnBrowse       = view.findViewById<com.google.android.material.button.MaterialButton>(R.id.btn_browse_torrent)
        val tvSelectedFile  = view.findViewById<TextView>(R.id.tv_selected_file)
        val tvFilesLabel    = view.findViewById<TextView>(R.id.tv_files_label)
        val rvFiles         = view.findViewById<RecyclerView>(R.id.rv_files)

        btnDownload.isEnabled = false

        rvFiles.layoutManager = LinearLayoutManager(this)
        val filesList    = mutableListOf<TorrentFileItem>()
        val filesAdapter = FilesAdapter(filesList) { index, isSelected -> filesList[index].isSelected = isSelected }
        rvFiles.adapter  = filesAdapter

        // Stored magnet or torrent info for when Download is clicked
        var selectedMagnet: String? = prefillMagnet
        var selectedTorrentFile: File? = prefillFile

        // Helper defined ahead to be used in prefill block
        fun populateFiles(info: TorrentInfo) {
            filesList.clear()
            val storage = info.files()
            for (i in 0 until storage.numFiles()) {
                filesList.add(TorrentFileItem(i, storage.filePath(i), storage.fileSize(i)))
            }
            filesAdapter.notifyDataSetChanged()
            tvFilesLabel.visibility = View.VISIBLE
            rvFiles.visibility      = View.VISIBLE
            btnDownload.isEnabled   = true
        }

        // Prefill Logic
        if (prefillMagnet != null) {
            etMagnet.setText(prefillMagnet) // Will trigger TextWatcher below
        }
        if (prefillFile != null) {
             CoroutineScope(Dispatchers.IO).launch {
                 try {
                     val info = TorrentInfo(prefillFile)
                     withContext(Dispatchers.Main) {
                         tvSelectedFile.text = "📄 ${prefillFile.name}"
                         tvSelectedFile.visibility = View.VISIBLE
                         populateFiles(info)
                     }
                 } catch (_: Exception) {}
             }
        }



        // ── 1. Magnet link watcher ────────────────────────────────────────────
        etMagnet.addTextChangedListener(object : TextWatcher {
            override fun afterTextChanged(s: Editable?) {
                val magnet = s.toString()
                if (magnet.startsWith("magnet:?")) {
                    selectedMagnet = magnet
                    selectedTorrentFile = null          // Clear file selection
                    tvSelectedFile.visibility = View.GONE
                    Toast.makeText(this@MainActivity, "Fetching metadata…", Toast.LENGTH_SHORT).show()
                    CoroutineScope(Dispatchers.IO).launch {
                        val tempFile = File(cacheDir, "magnet_meta_${System.currentTimeMillis()}.torrent")
                        val info = TorrentRepository.fetchMetadata(magnet, tempFile)
                        withContext(Dispatchers.Main) {
                            if (info != null) {
                                Toast.makeText(this@MainActivity, "✓ ${info.name()}", Toast.LENGTH_SHORT).show()
                                populateFiles(info)
                            } else {
                                Toast.makeText(this@MainActivity, "Could not fetch metadata", Toast.LENGTH_SHORT).show()
                            }
                        }
                    }
                }
            }
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
        })

        // ── 2. Browse .torrent file ───────────────────────────────────────────
        // Register what to do AFTER the user picks a file
        onTorrentFilePicked = { uri ->
            CoroutineScope(Dispatchers.IO).launch {
                // Copy URI bytes to cacheDir so libtorrent can access it (Scoped Storage)
                val cachedFile = copyUriToCache(uri)
                if (cachedFile != null) {
                    try {
                        val info = TorrentInfo(cachedFile)
                        withContext(Dispatchers.Main) {
                            selectedTorrentFile = cachedFile
                            selectedMagnet      = null   // Clear magnet
                            etMagnet.setText("")

                            // Show selected file name
                            tvSelectedFile.text       = "📄 ${cachedFile.name}"
                            tvSelectedFile.visibility = View.VISIBLE

                            Toast.makeText(this@MainActivity, "✓ ${info.name()}", Toast.LENGTH_SHORT).show()
                            populateFiles(info)
                        }
                    } catch (e: Exception) {
                        withContext(Dispatchers.Main) {
                            Toast.makeText(this@MainActivity, "Invalid .torrent file", Toast.LENGTH_SHORT).show()
                        }
                    }
                } else {
                    withContext(Dispatchers.Main) {
                        Toast.makeText(this@MainActivity, "Could not read file", Toast.LENGTH_SHORT).show()
                    }
                }
            }
        }

        btnBrowse.setOnClickListener {
            // Launch the system file picker, filter for .torrent MIME then fallback to */*
            torrentFileLauncher.launch(arrayOf("application/x-bittorrent", "*/*"))
        }

        // ── 3. Cancel ────────────────────────────────────────────────────────
        view.findViewById<Button>(R.id.btn_cancel).setOnClickListener { sheet.dismiss() }

        // ── 4. Download ───────────────────────────────────────────────────────
        btnDownload.setOnClickListener {
            val torrentFile = selectedTorrentFile
            val magnet      = selectedMagnet
            val priorities  = if (filesList.isNotEmpty()) filesList.map { it.isSelected } else null

            when {
                torrentFile != null -> startDownloadFromFile(torrentFile, priorities)
                magnet != null      -> startDownload(magnet, priorities)
                else                -> Toast.makeText(this, "Provide a magnet link or .torrent file", Toast.LENGTH_SHORT).show()
            }
            sheet.dismiss()
        }

        sheet.show()
    }

    /**
     * Copies the content of a content:// URI to the app's cacheDir.
     * This is required for Scoped Storage (Android 11+) since libtorrent
     * needs a real filesystem path, not a content URI.
     */
    private fun copyUriToCache(uri: Uri): File? {
        return try {
            val fileName = "selected_${System.currentTimeMillis()}.torrent"
            val destFile = File(cacheDir, fileName)
            contentResolver.openInputStream(uri)?.use { input ->
                destFile.outputStream().use { output ->
                    input.copyTo(output)
                }
            }
            destFile
        } catch (e: Exception) {
            Log.e("MainActivity", "Failed to copy URI to cache", e)
            null
        }
    }

    private fun startDownloadFromFile(torrentFile: File, priorities: List<Boolean>? = null) {
        emptyStateView.visibility = View.GONE
        recyclerView.visibility   = View.VISIBLE

        // Use the public Downloads folder — MANAGE_EXTERNAL_STORAGE must be granted
        val saveDir = TorrentRepository.resolveDownloadsDir(this)

        CoroutineScope(Dispatchers.IO).launch {
            val info = TorrentRepository.downloadFromFile(torrentFile, saveDir, priorities)
            withContext(Dispatchers.Main) {
                if (info != null) {
                    downloadsList.add(TorrentUiModel(
                        id       = System.currentTimeMillis().toString(),
                        title    = info.name(),
                        size     = "Calculating…",
                        speed    = "0 KB/s",
                        progress = 0,
                        status   = "Connecting",
                        timeLeft = "Unknown",
                        seeds    = 0,
                        peers    = 0,
                        ratio    = "0.0"
                    ))
                    downloadsAdapter.notifyDataSetChanged()
                } else {
                    Toast.makeText(this@MainActivity, "Failed to start download", Toast.LENGTH_SHORT).show()
                }
            }
        }
        startUiUpdates()
    }

    private fun startDownload(magnet: String, priorities: List<Boolean>? = null) {
        emptyStateView.visibility = View.GONE
        recyclerView.visibility   = View.VISIBLE

        // Use the public Downloads folder — MANAGE_EXTERNAL_STORAGE must be granted
        val saveDir = TorrentRepository.resolveDownloadsDir(this)
        TorrentRepository.download(magnet, saveDir, priorities)

        downloadsList.add(TorrentUiModel(
            id       = System.currentTimeMillis().toString(),
            title    = "Downloading…",
            size     = "Calculating…",
            speed    = "0 KB/s",
            progress = 0,
            status   = "Connecting",
            timeLeft = "Unknown",
            seeds    = 0,
            peers    = 0,
            ratio    = "0.0"
        ))
        downloadsAdapter.notifyDataSetChanged()
        startUiUpdates()
    }

    

    // -- UI Updates (Reconstructed) --
    private var isUiObserving = false
    
    // Filter State
    private var allTorrents: List<TorrentUiModel> = emptyList()
    private var currentTabPosition = 0
    private var currentSearchQuery = ""

    private fun applyFilters() {
        var filtered = allTorrents
        
        // 1. Tab Filter: 0=All, 1=Downloading, 2=Complete
        filtered = when (currentTabPosition) {
            1 -> filtered.filter { !it.isComplete }
            2 -> filtered.filter { it.isComplete }
            else -> filtered
        }

        // 2. Search Filter
        if (currentSearchQuery.isNotEmpty()) {
            filtered = filtered.filter { 
                it.title.contains(currentSearchQuery, ignoreCase = true) 
            }
        }
        
        downloadsAdapter.updateList(filtered)
        
        if (filtered.isEmpty()) {
            recyclerView.visibility = android.view.View.GONE
            emptyStateView.visibility = android.view.View.VISIBLE
        } else {
            emptyStateView.visibility = android.view.View.GONE
            recyclerView.visibility = android.view.View.VISIBLE
        }
    }

    private fun startUiUpdates() {
        if (isUiObserving) return
        isUiObserving = true

        lifecycleScope.launch {
            TorrentRepository.torrentListFlow.collectLatest { items ->
                allTorrents = items
                applyFilters()
            }
        }
    }

    private fun showDetailsDialog(item: TorrentUiModel) {
        val dialog = android.app.AlertDialog.Builder(this)
            .setView(R.layout.dialog_torrent_details)
            .create()
        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)
        dialog.show()

        dialog.findViewById<android.widget.TextView>(R.id.tv_details_filename)?.text = item.title
        dialog.findViewById<android.widget.TextView>(R.id.tv_seeds)?.text = item.seeds.toString()
        dialog.findViewById<android.widget.TextView>(R.id.tv_peers)?.text = item.peers.toString()
        dialog.findViewById<android.widget.TextView>(R.id.tv_download_speed)?.text = item.speed
        dialog.findViewById<android.widget.TextView>(R.id.tv_share_ratio)?.text = item.ratio

        dialog.findViewById<android.view.View>(R.id.btn_done_details)?.setOnClickListener {
            dialog.dismiss()
        }
    }

    private fun showDeleteDialog(item: TorrentUiModel) {
        val dialog = android.app.AlertDialog.Builder(this)
            .setView(R.layout.dialog_delete_torrent)
            .create()
        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)
        dialog.show()

        dialog.findViewById<android.view.View>(R.id.btn_cancel_delete)?.setOnClickListener { dialog.dismiss() }
        dialog.findViewById<android.view.View>(R.id.btn_confirm_delete)?.setOnClickListener {
            TorrentRepository.removeTorrent(item.id, true)
            android.widget.Toast.makeText(this, "Torrent Removed", android.widget.Toast.LENGTH_SHORT).show()
            dialog.dismiss()
        }
    }
    
    

    override fun onBackPressed() {
        if (binding.drawerLayout.isDrawerOpen(androidx.core.view.GravityCompat.START)) {
            binding.drawerLayout.closeDrawer(androidx.core.view.GravityCompat.START)
        } else {
            super.onBackPressed()
        }
    }

    override fun onCreateOptionsMenu(menu: android.view.Menu?): Boolean {
        menuInflater.inflate(R.menu.menu_main, menu)
        val searchItem = menu?.findItem(R.id.action_search)
        val searchView = searchItem?.actionView as? androidx.appcompat.widget.SearchView

        searchView?.queryHint = "Search torrents..."

        searchView?.setOnQueryTextListener(object : androidx.appcompat.widget.SearchView.OnQueryTextListener {
            override fun onQueryTextSubmit(query: String?): Boolean = false
            override fun onQueryTextChange(newText: String?): Boolean {
                currentSearchQuery = newText ?: ""
                applyFilters()
                return true
            }
        })
        return true
    }

    override fun onOptionsItemSelected(item: android.view.MenuItem): Boolean {
        return when (item.itemId) {
            R.id.action_add -> {
                showAddTorrentSheet()
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }

    override fun onNavigationItemSelected(item: android.view.MenuItem): Boolean {
        when (item.itemId) {
            R.id.nav_exit -> finishAffinity()
            R.id.nav_settings -> startActivity(android.content.Intent(this, SettingsActivity::class.java))
            R.id.nav_debug_logs -> showLogConsole()
        }
        binding.drawerLayout.closeDrawer(androidx.core.view.GravityCompat.START)
        return true
    }

    private fun showLogConsole() {
        val sheet = com.google.android.material.bottomsheet.BottomSheetDialog(this)
        val view  = layoutInflater.inflate(R.layout.sheet_log_console, null)
        sheet.setContentView(view)

        val tvLogs    = view.findViewById<android.widget.TextView>(R.id.tv_logs)
        val tvCount   = view.findViewById<android.widget.TextView>(R.id.tv_log_count)
        val scrollLog = view.findViewById<android.widget.ScrollView>(R.id.scroll_logs)
        val btnCopy   = view.findViewById<android.view.View>(R.id.btn_copy_logs)
        val btnClear  = view.findViewById<android.view.View>(R.id.btn_clear_logs)
        val btnClose  = view.findViewById<android.view.View>(R.id.btn_close_logs)

        lifecycleScope.launch {
            try {
                AppLogger.logsFlow.collectLatest { lines ->
                    val text = if (lines.isEmpty()) "No events" else lines.joinToString("\n")
                    tvLogs.text  = text
                    tvCount.text = " lines"
                    scrollLog.post { scrollLog.fullScroll(android.widget.ScrollView.FOCUS_DOWN) }
                }
            } catch (_: Exception) {}
        }

        btnCopy.setOnClickListener {
            val snapshot = AppLogger.snapshot()
            val clip     = android.content.ClipData.newPlainText("engine_logs", snapshot)
            val cm       = getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
            cm.setPrimaryClip(clip)
            android.widget.Toast.makeText(this, "Copied!", android.widget.Toast.LENGTH_SHORT).show()
        }

        btnClear.setOnClickListener {
            AppLogger.clear()
            android.widget.Toast.makeText(this, "Cleared", android.widget.Toast.LENGTH_SHORT).show()
        }

        btnClose.setOnClickListener { sheet.dismiss() }
        sheet.show()
    }
}
