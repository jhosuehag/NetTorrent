package com.jhosue.utorrent

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collectLatest

class TorrentService : Service() {

    private val serviceJob = SupervisorJob()
    private val serviceScope = CoroutineScope(Dispatchers.IO + serviceJob)
    private var isServiceRunning = false

    private lateinit var connectivityManager: ConnectivityManager
    
    // Wi-Fi Only Logic
    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) { checkConnection() }
        override fun onLost(network: Network) { checkConnection() }
        override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) { checkConnection() }
    }
    
    // Prefs Logic
    private val prefsListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key == Prefs.KEY_WIFI_ONLY) {
            checkConnection()
        }
    }

    companion object {
        const val CHANNEL_ID = "torrent_service_channel"
        const val NOTIFICATION_ID = 1337
        const val ACTION_STOP = "com.jhosue.utorrent.ACTION_STOP"
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        
        connectivityManager = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        
        // Register Network Callback (Android N+)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            connectivityManager.registerDefaultNetworkCallback(networkCallback)
        }
        
        // Register Prefs Listener
        Prefs.get(this).registerOnSharedPreferenceChangeListener(prefsListener)
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopForeground(true)
            stopSelf()
            return START_NOT_STICKY
        }
        
        startForeground(NOTIFICATION_ID, buildNotification("Initializing engine...", "Please wait"))

        if (!isServiceRunning) {
            isServiceRunning = true
            serviceScope.launch {
                // Ensure engine is started
                TorrentRepository.start(applicationContext)
                
                // Initial limits sync
                val dl = Prefs.getDlLimit(applicationContext) * 1024
                val ul = Prefs.getUlLimit(applicationContext) * 1024
                TorrentRepository.setLimits(dl, ul)
                
                // Initial Wi-Fi check
                checkConnection()
                
                // Observe flow to update notification
                TorrentRepository.torrentListFlow.collectLatest { list ->
                    updateNotificationFromList(list)
                }
            }
        }

        return START_STICKY
    }
    
    private fun checkConnection() {
        val wifiOnly = Prefs.isWifiOnly(this)
        if (!wifiOnly) {
            TorrentRepository.resumeIncompleteOnly()
            return
        }
        
        // Check if Wi-Fi is active
        val activeNetwork = connectivityManager.activeNetwork
        val caps = connectivityManager.getNetworkCapabilities(activeNetwork)
        val onWifi = caps?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true
        
        if (onWifi) {
            TorrentRepository.resumeIncompleteOnly()
        } else {
            TorrentRepository.pauseAll()
            updateNotification("Downloads paused", "Waiting for Wi-Fi...")
        }
    }

    private fun updateNotificationFromList(list: List<TorrentUiModel>) {
        if (list.isEmpty()) {
            updateNotification("No active torrents", "Engine running")
            return
        }

        val downloading = list.filter { it.status == "Downloading" }
        val seeding = list.filter { it.status == "Seeding" }
        val totalActive = downloading.size + seeding.size
        val paused = list.count { it.status == "Paused" || it.status == "Stopped" }

        val title = when {
            totalActive > 0 -> "Downloading ${downloading.size} | Seeding ${seeding.size}"
            paused > 0 -> "Torrents paused/finished"
            else -> "Engine idle"
        }

        val content = if (downloading.isNotEmpty()) {
            val top = downloading.first()
            "${top.title.take(15)}.. | ${top.speed} | ${top.progress}%"
        } else if (seeding.isNotEmpty()) {
            val top = seeding.first()
            "${top.title.take(15)}.. | Seeding | ${top.progress}%"
        } else if (paused > 0) {
            "Paused: $paused"
        } else {
            "Background service active"
        }

        updateNotification(title, content)
    }
    
    private fun updateNotification(title: String, content: String) {
        val notification = buildNotification(title, content)
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(NOTIFICATION_ID, notification)
    }

    private fun buildNotification(title: String, content: String): Notification {
        val openIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pendingOpen = PendingIntent.getActivity(
            this, 0, openIntent,
            if (Build.VERSION.SDK_INT >= 23) PendingIntent.FLAG_IMMUTABLE else 0
        )

        val stopIntent = Intent(this, TorrentService::class.java).apply {
            action = ACTION_STOP
        }
        val pendingStop = PendingIntent.getService(
            this, 1, stopIntent,
            if (Build.VERSION.SDK_INT >= 23) PendingIntent.FLAG_IMMUTABLE else 0
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(content)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentIntent(pendingOpen)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Exit", pendingStop)
            .setOnlyAlertOnce(true)
            .setOngoing(true)
            .build()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Torrent Service",
                NotificationManager.IMPORTANCE_LOW 
            ).apply {
                description = "Shows torrent download progress"
            }
            val nm = getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(channel)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        isServiceRunning = false
        serviceJob.cancel()
        
        try {
            connectivityManager.unregisterNetworkCallback(networkCallback)
        } catch (_: Exception) {}
        
        try {
            Prefs.get(this).unregisterOnSharedPreferenceChangeListener(prefsListener)
        } catch (_: Exception) {}

        TorrentRepository.stop()
    }
}
