package com.jhosue.utorrent

import android.content.Context
import android.util.Log
import android.os.Environment
import android.text.format.Formatter
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.libtorrent4j.AlertListener
import org.libtorrent4j.SessionManager
import org.libtorrent4j.SessionParams
import org.libtorrent4j.SettingsPack
import org.libtorrent4j.TorrentHandle
import org.libtorrent4j.TorrentInfo
import org.libtorrent4j.TorrentStatus
import org.libtorrent4j.alerts.AddTorrentAlert
import org.libtorrent4j.alerts.Alert
import org.libtorrent4j.alerts.AlertType
import org.libtorrent4j.alerts.SaveResumeDataAlert
import org.libtorrent4j.alerts.SaveResumeDataFailedAlert
import org.libtorrent4j.alerts.TorrentCheckedAlert
import org.libtorrent4j.alerts.TorrentFinishedAlert
import org.libtorrent4j.swig.add_torrent_params
import org.libtorrent4j.swig.error_code
import org.libtorrent4j.swig.libtorrent
import org.libtorrent4j.swig.session_handle
import org.libtorrent4j.swig.settings_pack
import org.libtorrent4j.swig.torrent_handle_vector
import java.io.File
import java.util.concurrent.ConcurrentHashMap

// ── Logging shim — uses println to avoid Log generic-overload ambiguity ─────────
private fun logD(tag: String, msg: String) { android.util.Log.println(android.util.Log.DEBUG,   tag, msg) }
private fun logI(tag: String, msg: String) { android.util.Log.println(android.util.Log.INFO,    tag, msg) }
private fun logW(tag: String, msg: String) { android.util.Log.println(android.util.Log.WARN,    tag, msg) }
private fun logE(tag: String, msg: String) { android.util.Log.println(android.util.Log.ERROR,   tag, msg) }
private fun logE(tag: String, msg: String, t: Throwable) {
    android.util.Log.println(android.util.Log.ERROR, tag, "$msg\n${t.stackTraceToString()}")
}

    // ── Safe name extractor ─────────────────────────────────────────────────────────────
    @Suppress("UNNECESSARY_SAFE_CALL")
    private fun safeName(handle: TorrentHandle, stat: TorrentStatus? = null): String {
        // 1. Try native SWIG status (Direct C++ field access)
        try {
            val swigH = handle.swig()
            val swigS = swigH.status() // returns torrent_status
            // Try 'name' property or method via reflection on the SWIG object
            try { return (swigS.javaClass.getMethod("name").invoke(swigS) as String).takeIf { it.isNotEmpty() } ?: "" } catch (_: Exception) {}
            try { return (swigS.javaClass.getMethod("getName").invoke(swigS) as String).takeIf { it.isNotEmpty() } ?: "" } catch (_: Exception) {}
        } catch (_: Exception) {}

        // 2. Try Java Wrapper Status (Reflection)
        if (stat != null) {
            try { return (stat.javaClass.getMethod("name").invoke(stat) as String).takeIf { it.isNotEmpty() } ?: "" } catch (_: Exception) {}
        }

        // 3. Try Handle directly (Reflection)
        try { return (handle.javaClass.getMethod("name").invoke(handle) as String).takeIf { it.isNotEmpty() } ?: "" } catch (_: Exception) {}

        // 4. Try TorrentInfo (Source of Truth)
        try {
            val tiMethod = handle.javaClass.methods.find { it.name == "torrentFile" || it.name == "torrent_file" }
            if (tiMethod != null) {
                val info = tiMethod.invoke(handle)
                if (info != null) {
                    val n = info.javaClass.getMethod("name").invoke(info) as? String
                    if (!n.isNullOrEmpty()) return n
                }
            }
        } catch (_: Exception) {}

        // 5. Fallback to InfoHash (Prevent "Unknown" at all costs)
        try {
            val hash = handle.infoHash().toString() // toString() of Sha1Hash is hex
            if (hash.isNotEmpty()) return "Hash: ${hash.take(8)}..."
        } catch (_: Exception) {
            try {
               // Try swig info_hash
               return handle.swig().info_hash().to_hex().take(8)
            } catch (__: Exception) {}
        }

        return "Unknown Name"
    }

// ── High-level ErrorCode (org.libtorrent4j.ErrorCode) ──────────────────────────
// .value is a PROPERTY (Int), not a function, in the high-level wrapper
private fun errorValue(ec: org.libtorrent4j.ErrorCode?): Int = ec?.value ?: 0

// ── Low-level swig error_code (org.libtorrent4j.swig.error_code) ────────────────
// Compiler is confused about .value vs .value(), so we use Reflection to find the method
private fun errorValueSwig(ec: error_code): Int = try {
    val method = ec.javaClass.getMethod("value")
    (method.invoke(ec) as? Int) ?: 0
} catch (_: Exception) { 0 }

object TorrentRepository {

    private const val TAG = "TorrentRepo"

    private var sessionManager = SessionManager()
    private var isStarted = false
    private var appCtx: Context? = null
    private val manuallyPausedHashes = ConcurrentHashMap.newKeySet<String>()

    // ── StateFlow that MainActivity collects ──────────────────────────────────
    private val _torrentListFlow = MutableStateFlow<List<TorrentUiModel>>(emptyList())
    val torrentListFlow: StateFlow<List<TorrentUiModel>> = _torrentListFlow.asStateFlow()

    private fun emitSnapshotNow() {
        val ctx = appCtx ?: return
        _torrentListFlow.value = getTorrentList(ctx)
    }

    // ── Alert listener ────────────────────────────────────────────────────────
    private val alertListener = object : AlertListener {
        override fun types(): IntArray? = null

        // Parameter renamed 'ev' to avoid shadowing issues
        override fun alert(ev: Alert<*>) {
            handleAlert(ev)
        }
    }

    // Separated into its own function to avoid SWIG type-inference interference
    private fun handleAlert(ev: Alert<*>) {
        try {
            val aType = ev.type()
            when {
                aType == AlertType.ADD_TORRENT -> {
                    val a = ev as? AddTorrentAlert ?: return
                    val ec = a.error()
                    val code: Int = errorValue(ec)
                    if (code != 0) {
                        logE(TAG, "ADD_TORRENT failed code=$code")
                    } else {
                        val h = a.handle()
                        if (h != null && h.isValid) {
                            // Resume only if this torrent still has work to do.
                            val st = h.status()
                            if (st.progress() >= 0.999f || st.state() == TorrentStatus.State.FINISHED || st.state() == TorrentStatus.State.SEEDING) {
                                h.pause()
                            } else {
                                h.resume()
                            }
                            logD(TAG, "Added & resumed: ${safeName(h)}")
                        }
                    }
                }
                aType == AlertType.TORRENT_CHECKED -> {
                    val a = ev as? TorrentCheckedAlert ?: return
                    val h = a.handle()
                    if (h != null && h.isValid) {
                        val st = h.status()
                        if (st.progress() >= 0.999f || st.state() == TorrentStatus.State.FINISHED || st.state() == TorrentStatus.State.SEEDING) {
                            h.pause()
                        } else {
                            h.resume()
                        }
                        logD(TAG, "Checked & resumed: ${safeName(h)}")
                    }
                }
                aType == AlertType.TORRENT_FINISHED -> {
                    val a = ev as? TorrentFinishedAlert ?: return
                    val h = a.handle()
                    if (h != null && h.isValid) {
                        // Prevent post-finish seeding/upload traffic.
                        h.pause()
                        requestSaveResumeData(h)
                    }
                    logI(TAG, "Finished & paused: ${safeName(a.handle())}")
                }
                aType == AlertType.SAVE_RESUME_DATA -> {
                    val a = ev as? SaveResumeDataAlert ?: return
                    try {
                        val h = a.handle()
                        if (h != null && h.isValid) {
                            val hash = h.infoHash().toHex()
                            val p = a.params()
                            var bArray: ByteArray? = null
                            try {
                                bArray = p.javaClass.getMethod("writeResumeData").invoke(p) as? ByteArray
                            } catch (_: Exception) {
                                try {
                                    bArray = p.javaClass.getMethod("bencode").invoke(p) as? ByteArray
                                } catch (__: Exception) {}
                            }
                            if (bArray != null) {
                                val f = File(appCtx?.filesDir, "$hash.fastresume")
                                f.writeBytes(bArray)
                                logD(TAG, "SaveResumeDataAlert: saved fastresume to ${f.absolutePath}")
                            } else {
                                logW(TAG, "SaveResumeDataAlert: Neither writeResumeData nor bencode found on AddTorrentParams")
                            }
                        }
                    } catch (e: Exception) {
                        logE(TAG, "SaveResumeDataAlert error", e)
                    }
                }
                aType == AlertType.SAVE_RESUME_DATA_FAILED -> {
                    val a = ev as? SaveResumeDataFailedAlert ?: return
                    logE(TAG, "SaveResumeDataFailedAlert received: ${a.message()}")
                }
            }
        } catch (ex: Exception) {
            logE(TAG, "handleAlert crash: ${ex.message}")
        }
    }

    private fun requestSaveResumeData(handle: TorrentHandle) {
        try {
            handle.javaClass.getMethod("saveResumeData").invoke(handle)
        } catch (_: Exception) {
            try {
                val swigH = handle.swig()
                val m = swigH.javaClass.methods.firstOrNull { it.name == "save_resume_data" }
                if (m != null) {
                    if (m.parameterTypes.size == 1) {
                        m.invoke(swigH, 1) // save_info_dict
                    } else {
                        m.invoke(swigH)
                    }
                }
            } catch (__: Exception) {}
        }
    }

    // ── Session start ─────────────────────────────────────────────────────────
    suspend fun start(context: Context) {
        if (isStarted) return
        appCtx = context.applicationContext
        withContext(Dispatchers.IO) {
            try {
                sessionManager = SessionManager() // Always create a fresh session to discard zombie state
                val bootstrapNodes =
                    "67.215.246.161:6881," +
                    "87.98.162.88:6881," +
                    "185.157.221.247:25401"

                val sp = SettingsPack().apply {
                    setInteger(settings_pack.int_types.alert_mask.swigValue(), 0x7fffffff)
                    setString(settings_pack.string_types.user_agent.swigValue(), "BitTorrent/7.11.0")
                    setString(settings_pack.string_types.listen_interfaces.swigValue(), "0.0.0.0:0")
                    setString(settings_pack.string_types.dht_bootstrap_nodes.swigValue(), bootstrapNodes)
                    setBoolean(settings_pack.bool_types.enable_dht.swigValue(),    true)
                    setBoolean(settings_pack.bool_types.enable_lsd.swigValue(),    true)
                    setBoolean(settings_pack.bool_types.enable_upnp.swigValue(),   true)
                    setBoolean(settings_pack.bool_types.enable_natpmp.swigValue(), true)
                    setInteger(settings_pack.int_types.connections_limit.swigValue(), 500)
                    setBoolean(settings_pack.bool_types.listen_system_port_fallback.swigValue(), true)
                }

                sessionManager.addListener(alertListener)
                sessionManager.start(SessionParams(sp))
                isStarted = true
                logD(TAG, "Session started OK")
                startPollLoop()
            } catch (ex: Exception) {
                logE(TAG, "start() failed: ${ex.message}")
            }
        }
    }

    fun stop() {
        if (!isStarted) return
        try {
            val handles = sessionManager.swig().get_torrents()
            val c = try { handles.size } catch (_: Exception) { handles.javaClass.getMethod("size").invoke(handles) as Int }
            for (i in 0 until c) {
                 val h = TorrentHandle(handles.get(i))
                 if (h.isValid) requestSaveResumeData(h)
            }
        } catch (_: Exception) {}
        sessionManager.removeListener(alertListener)
        sessionManager.stop()
        isStarted = false
    }

    // ── Downloads directory ───────────────────────────────────────────────────
    fun resolveDownloadsDir(context: Context): File {
        val customUriStr = Prefs.getStorageUri(context)
        if (!customUriStr.isNullOrEmpty()) {
            try {
                val uri = android.net.Uri.parse(customUriStr)
                val pathStr = uri.path?.replace("/tree/primary:", "/storage/emulated/0/") ?: customUriStr
                if (pathStr.startsWith("/")) {
                    val customDir = File(pathStr)
                    if (!customDir.exists()) customDir.mkdirs()
                    return customDir
                }
            } catch (e: Exception) {
                logE(TAG, "Failed resolving custom storage URI: ${e.message}")
            }
        }

        // Default fallback
        val pub = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
        return if (pub.exists() || pub.mkdirs()) pub else {
            val fallback = context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)
                ?: File(context.filesDir, "downloads")
            fallback.also { it.mkdirs() }
        }
    }

    private fun buildPriorities(priorities: List<Boolean>?): org.libtorrent4j.swig.byte_vector? {
        if (priorities == null) return null
        val v = org.libtorrent4j.swig.byte_vector()
        for (isSelected in priorities) {
            v.add(if (isSelected) 4.toByte() else 0.toByte())
        }
        return v
    }

    private fun findHandleByInfoHash(hashHex: String): TorrentHandle? {
        if (!isStarted) return null
        // CRITICAL: We iterate get_torrents() and match by infoHash().toHex() because:
        // - The UI item ID is produced by handle.infoHash().toHex() in buildItem().
        // - sha1_hash(String) SWIG constructor expects a 20-byte raw string, NOT a hex string.
        //   Passing our hex string to find_torrent() via sha1_hash(hexStr) constructs the wrong
        //   hash object, causing find_torrent() to silently return null/invalid every time.
        // - Iterating get_torrents() and comparing toHex() guarantees a format-exact match.
        return try {
            val handles = sessionManager.swig().get_torrents()
            val count: Int = try { handles.size } catch (_: Exception) {
                handles.javaClass.getMethod("size").invoke(handles) as Int
            }
            android.util.Log.d("TorrentEngine", "findHandleByInfoHash: scanning $count handles for hash=$hashHex")
            for (i in 0 until count) {
                val h = TorrentHandle(handles.get(i))
                if (!h.isValid) continue
                val candidateHex = try { h.infoHash().toHex() } catch (_: Exception) { continue }
                if (candidateHex.equals(hashHex, ignoreCase = true)) {
                    android.util.Log.d("TorrentEngine", "findHandleByInfoHash: FOUND handle at index $i for hash=$hashHex")
                    return h
                }
            }
            android.util.Log.e("TorrentEngine", "findHandleByInfoHash: NOT FOUND for hash=$hashHex. " +
                "Available hashes: ${(0 until count).mapNotNull { i ->
                    try { TorrentHandle(handles.get(i)).infoHash().toHex() } catch (_: Exception) { null }
                }}")
            null
        } catch (ex: Exception) {
            android.util.Log.e("TorrentEngine", "findHandleByInfoHash CRASH: ${ex.message}")
            null
        }
    }

    private fun applyFilePrioritiesToHandle(handle: TorrentHandle, priorities: org.libtorrent4j.swig.byte_vector): Boolean {
        return try {
            val swigHandle = handle.swig()
            val methods = swigHandle.javaClass.methods
            val m = methods.firstOrNull {
                (it.name == "prioritize_files" || it.name == "prioritizeFiles") &&
                    it.parameterTypes.size == 1
            }
            if (m != null) {
                m.invoke(swigHandle, priorities)
                true
            } else {
                false
            }
        } catch (_: Exception) {
            false
        }
    }

    private fun getTorrentFlagValue(flagName: String): Long {
        return try {
            val clazz = Class.forName("org.libtorrent4j.swig.torrent_flags")
            val field = clazz.fields.firstOrNull { it.name == flagName } ?: return 0L
            val enumObj = field.get(null) ?: return 0L
            val swigValueMethod = enumObj.javaClass.methods.firstOrNull {
                it.name == "swigValue" && it.parameterTypes.isEmpty()
            }
            when (val v = swigValueMethod?.invoke(enumObj)) {
                is Int -> v.toLong()
                is Long -> v
                else -> 0L
            }
        } catch (_: Exception) {
            0L
        }
    }

    private fun setFlagsRaw(handle: TorrentHandle, flags: Long): Boolean {
        return try {
            val swigHandle = handle.swig()
            val m = swigHandle.javaClass.methods.firstOrNull {
                (it.name == "set_flags" || it.name == "setFlags") && it.parameterTypes.size == 1
            } ?: return false
            val p = m.parameterTypes[0]
            when (p.name) {
                "int", "java.lang.Integer" -> m.invoke(swigHandle, flags.toInt())
                "long", "java.lang.Long" -> m.invoke(swigHandle, flags)
                else -> return false
            }
            true
        } catch (_: Exception) {
            false
        }
    }

    private fun unsetFlagsRaw(handle: TorrentHandle, flags: Long): Boolean {
        return try {
            val swigHandle = handle.swig()
            val m = swigHandle.javaClass.methods.firstOrNull {
                (it.name == "unset_flags" || it.name == "unsetFlags") && it.parameterTypes.size == 1
            } ?: return false
            val p = m.parameterTypes[0]
            when (p.name) {
                "int", "java.lang.Integer" -> m.invoke(swigHandle, flags.toInt())
                "long", "java.lang.Long" -> m.invoke(swigHandle, flags)
                else -> return false
            }
            true
        } catch (_: Exception) {
            false
        }
    }

    private fun forceRecheckRaw(handle: TorrentHandle): Boolean {
        return try {
            val swigHandle = handle.swig()
            val m = swigHandle.javaClass.methods.firstOrNull {
                (it.name == "force_recheck" || it.name == "forceRecheck") && it.parameterTypes.isEmpty()
            } ?: return false
            m.invoke(swigHandle)
            true
        } catch (_: Exception) {
            false
        }
    }

    private fun clearErrorRaw(handle: TorrentHandle): Boolean {
        return try {
            val swigHandle = handle.swig()
            val m = swigHandle.javaClass.methods.firstOrNull {
                (it.name == "clear_error" || it.name == "clearError") && it.parameterTypes.isEmpty()
            } ?: return false
            m.invoke(swigHandle)
            true
        } catch (_: Exception) {
            false
        }
    }

    private fun pausedAndAutoManagedMask(): Long {
        val paused = getTorrentFlagValue("paused")
        val autoManaged = getTorrentFlagValue("auto_managed")
        return paused or autoManaged
    }

    private fun isHandlePaused(handle: TorrentHandle): Boolean {
        return try {
            val swigHandle = handle.swig()
            val methods = swigHandle.javaClass.methods
            val m = methods.firstOrNull {
                (it.name == "is_paused" || it.name == "isPaused") &&
                    it.parameterTypes.isEmpty()
            }
            if (m != null) (m.invoke(swigHandle) as? Boolean) == true else false
        } catch (_: Exception) {
            false
        }
    }

    private fun hasIncompleteTorrents(): Boolean {
        if (!isStarted) return false
        return try {
            val handles: torrent_handle_vector = sessionManager.swig().get_torrents()
            val count: Int = handles.size
            for (i in 0 until count) {
                val h = TorrentHandle(handles.get(i))
                if (!h.isValid) continue
                val st = h.status()
                val hash = h.infoHash().toHex()
                val complete = st.progress() >= 0.999f ||
                    st.state() == TorrentStatus.State.FINISHED ||
                    st.state() == TorrentStatus.State.SEEDING
                if (!complete && !manuallyPausedHashes.contains(hash)) return true
            }
            false
        } catch (_: Exception) {
            true
        }
    }

    private fun enforceIdleSessionPolicy() {
        if (!isStarted) return
        try {
            if (hasIncompleteTorrents()) {
                sessionManager.resume()
            } else {
                sessionManager.pause()
            }

            // Re-apply manual pauses after any session-level state change.
            val handles = sessionManager.swig().get_torrents()
            val count: Int = try { handles.size } catch (_: Exception) { handles.javaClass.getMethod("size").invoke(handles) as Int }
            for (i in 0 until count) {
                val h = TorrentHandle(handles.get(i))
                if (!h.isValid) continue
                val hash = h.infoHash().toHex()
                if (manuallyPausedHashes.contains(hash)) {
                    setTorrentFlagReflectSafe(h.swig(), "auto_managed", false)
                    setTorrentFlagReflectSafe(h.swig(), "paused", true)
                    h.pause()
                }
            }
        } catch (ex: Exception) {
            logE(TAG, "enforceIdleSessionPolicy failed: ${ex.message}")
        }
    }

    // ── Add magnet ────────────────────────────────────────────────────────────
    fun download(magnetLink: String, saveDir: File, priorities: List<Boolean>? = null) {
        if (!saveDir.exists()) saveDir.mkdirs()
        try {
            sessionManager.resume()
            val ec = error_code()
            var params = libtorrent.parse_magnet_uri(magnetLink, ec)
            val code: Int = errorValueSwig(ec)
            if (code != 0) {
                logE(TAG, "Magnet parse error code=\$code")
                return
            }
            val hashHex = try {
                val method = params.javaClass.methods.firstOrNull { it.name == "info_hash" }
                if (method != null) {
                    val h = method.invoke(params)
                    h.javaClass.getMethod("to_hex").invoke(h) as String
                } else {
                    val mHs = params.javaClass.getMethod("info_hashes").invoke(params)
                    val h = mHs.javaClass.getMethod("get_best").invoke(mHs)
                    h.javaClass.getMethod("to_hex").invoke(h) as String
                }
            } catch(_: Exception) { "" }
            
            if (hashHex.isNotEmpty()) {
                try {
                    val f = File(appCtx?.filesDir, "$hashHex.fastresume")
                    if (f.exists()) {
                        val bytes = f.readBytes()
                        val byteVector = org.libtorrent4j.swig.byte_vector()
                        for (b in bytes) byteVector.add(b)
                        val ec2 = error_code()
                        var parsedParams: Any? = null
                        try {
                            parsedParams = libtorrent::class.java.getMethod("read_resume_data", byteVector.javaClass, ec2.javaClass).invoke(null, byteVector, ec2)
                        } catch (_: Exception) {
                            try {
                                val bdecodeClass = Class.forName("org.libtorrent4j.swig.bdecode_node")
                                val bdecodeM = bdecodeClass.methods.firstOrNull { it.name == "bdecode" }
                                val bNode = if (bdecodeM != null) bdecodeM.invoke(null, byteVector, ec2, null) else null
                                val readResM = libtorrent::class.java.methods.firstOrNull { it.name == "read_resume_data" }
                                if (readResM != null && bNode != null) {
                                    parsedParams = readResM.invoke(null, bNode, ec2)
                                }
                            } catch (__: Exception) {}
                        }
                        if (parsedParams != null && errorValueSwig(ec2) == 0) {
                            params = parsedParams as org.libtorrent4j.swig.add_torrent_params
                            logD(TAG, "Loaded fastresume for magnet hash=$hashHex")
                        }
                    }
                } catch(e: Exception) {
                    logE(TAG, "Failed to load fastresume", e)
                }
            }
            params.save_path = saveDir.absolutePath
            val v = buildPriorities(priorities)
            if (v != null) params.set_file_priorities(v)
            val ec2 = error_code()
            sessionManager.swig().add_torrent(params, ec2)
        } catch (ex: Exception) {
            logE(TAG, "download() failed: ${ex.message}")
        }
    }

    // ── Add .torrent file ─────────────────────────────────────────────────────
    fun downloadFromFile(torrentFile: File, saveDir: File, priorities: List<Boolean>? = null): TorrentInfo? {
        return try {
            if (!saveDir.exists()) saveDir.mkdirs()
            sessionManager.resume()

            val ti = TorrentInfo(torrentFile)
            val hashHex = ti.infoHash().toHex()
            val existing = findHandleByInfoHash(hashHex)
            val v = buildPriorities(priorities)

            if (existing != null && existing.isValid) {
                // Duplicate info-hash in session: do not add_torrent again.
                Log.d("TorrentDebug", "Existing hash detected for duplicate. hash=$hashHex")
                if (v != null) {
                    val ok = applyFilePrioritiesToHandle(existing, v)
                    Log.d("TorrentDebug", "Existing hash: set file priorities applied=$ok")
                }
                val clearErrorOk = clearErrorRaw(existing)
                Log.d("TorrentDebug", "Existing hash: clear_error=$clearErrorOk")
                
                val mask = pausedAndAutoManagedMask()
                val unsetOk = unsetFlagsRaw(existing, mask)
                Log.d("TorrentDebug", "Existing hash: unset_flags(paused|auto_managed) ok=$unsetOk mask=$mask")
                
                val recheckOk = forceRecheckRaw(existing)
                Log.d("TorrentDebug", "Existing hash: force_recheck ok=$recheckOk")
                
                existing.resume()
                Log.d("TorrentDebug", "Existing hash: resume() called")
                emitSnapshotNow()
                ti
            } else {
                var p = add_torrent_params()
                val hashHex = ti.infoHash().toHex()
                try {
                    val f = File(appCtx?.filesDir, "$hashHex.fastresume")
                    if (f.exists()) {
                        val bytes = f.readBytes()
                        val byteVector = org.libtorrent4j.swig.byte_vector()
                        for (b in bytes) byteVector.add(b)
                        val ec2 = error_code()
                        var parsedParams: Any? = null
                        try {
                            parsedParams = libtorrent::class.java.getMethod("read_resume_data", byteVector.javaClass, ec2.javaClass).invoke(null, byteVector, ec2)
                        } catch (_: Exception) {
                            try {
                                val bdecodeClass = Class.forName("org.libtorrent4j.swig.bdecode_node")
                                val bdecodeM = bdecodeClass.methods.firstOrNull { it.name == "bdecode" }
                                val bNode = if (bdecodeM != null) bdecodeM.invoke(null, byteVector, ec2, null) else null
                                val readResM = libtorrent::class.java.methods.firstOrNull { it.name == "read_resume_data" }
                                if (readResM != null && bNode != null) {
                                    parsedParams = readResM.invoke(null, bNode, ec2)
                                }
                            } catch (__: Exception) {}
                        }
                        if (parsedParams != null && errorValueSwig(ec2) == 0) {
                            p = parsedParams as org.libtorrent4j.swig.add_torrent_params
                            logD(TAG, "Loaded fastresume for torrent file hash=$hashHex")
                        }
                    }
                } catch(e: Exception) {
                    logE(TAG, "Failed to load fastresume", e)
                }
                
                p.set_ti(ti.swig())
                p.save_path = saveDir.absolutePath
                if (v != null) p.set_file_priorities(v)

                val ec = error_code()
                sessionManager.swig().add_torrent(p, ec)
                val code = errorValueSwig(ec)
                if (code != 0) {
                    logE(TAG, "downloadFromFile add_torrent error code=$code")
                    null
                } else {
                    emitSnapshotNow()
                    ti
                }
            }
        } catch (ex: Exception) {
            logE(TAG, "downloadFromFile failed", ex)
            null
        }
    }

    // ── Fetch metadata for a magnet ───────────────────────────────────────────
    fun fetchMetadata(magnetLink: String, tempFile: File): TorrentInfo? {
        val data = sessionManager.fetchMagnet(magnetLink, 30, tempFile)
        return if (data != null) {
            try { TorrentInfo(data) } catch (_: Exception) { null }
        } else null
    }

    // ── Snapshot list ─────────────────────────────────────────────────────────
    fun getTorrentList(ctx: Context): List<TorrentUiModel> {
        if (!isStarted) return emptyList()
        val result = mutableListOf<TorrentUiModel>()
        try {
            val handles: torrent_handle_vector = sessionManager.swig().get_torrents()
            val count: Int = handles.size  // property access; if this fails try handles.size()
            for (i in 0 until count) {
                val item = buildItem(ctx, handles, i) ?: continue
                result.add(item)
            }
        } catch (ex: Exception) {
            logE(TAG, "getTorrentList failed: ${ex.message}")
        }
        return result
    }

    /**
     * Builds a single TorrentUiModel from index i.
     * Completely isolated so SWIG type-inference errors cannot cascade.
     */
    private fun buildItem(ctx: Context, handles: torrent_handle_vector, i: Int): TorrentUiModel? {
        return try {
            val handle = TorrentHandle(handles.get(i))
            if (!handle.isValid) return null

            val st = handle.status()

            // --- Primitives: extract to typed locals first ---
            val state = st.state()
            val dlRateInt: Int  = st.downloadRate()
            val dlRate: Long    = dlRateInt.toLong()
            val totalDl: Long   = st.totalWanted()
            val doneDl: Long    = st.totalWantedDone()
            val prog: Float     = st.progress()
            val allDl: Long     = st.allTimeDownload()
            val allUl: Long     = st.allTimeUpload()
            val seeds: Int      = st.numSeeds()
            val peers: Int      = st.numPeers()
            val name: String    = safeName(handle, st)
            val id: String      = handle.infoHash().toHex()
            val complete = prog >= 0.999f ||
                state == TorrentStatus.State.FINISHED ||
                state == TorrentStatus.State.SEEDING
            val isPausedByEngine = try {
                val m = st.javaClass.methods.firstOrNull { it.name == "isPaused" && it.parameterTypes.isEmpty() }
                if (m != null) (m.invoke(st) as? Boolean) == true
                else {
                    val m2 = st.javaClass.methods.firstOrNull { it.name == "paused" && it.parameterTypes.isEmpty() }
                    if (m2 != null) (m2.invoke(st) as? Boolean) == true
                    else isHandlePaused(handle)
                }
            } catch (_: Exception) {
                isHandlePaused(handle)
            }
            // manuallyPausedHashes overrides the engine's reported state: the engine
            // sometimes briefly un-pauses a torrent before our flag is fully applied.
            val paused = manuallyPausedHashes.contains(id) || isPausedByEngine
            val stateLabel: String = when {
                complete -> "Complete"
                paused -> "Paused"
                else -> stateToString(state, prog)
            }

            // --- Derived strings ---
            // Instantly snap speed to 0 when paused or complete — libtorrent's moving
            // average otherwise causes a slow decay that makes the UI feel unresponsive.
            val displayDlRate: Long = when {
                paused    -> 0L
                complete  -> 0L
                else      -> dlRate
            }
            val speedStr: String  = Formatter.formatFileSize(ctx, displayDlRate) + "/s"
            val sizeStr: String   = Formatter.formatFileSize(ctx, totalDl)
            val progInt: Int      = (prog * 100f).toInt()

            val etaStr: String = if (displayDlRate > 0L) {
                val remBytes: Long = totalDl - doneDl
                val secs: Long = remBytes / displayDlRate
                val mins: Long = secs / 60L
                if (mins > 60L) "${mins / 60L}h ${mins % 60L}m" else "${mins}m"
            } else "∞"

            val ratioStr: String = if (allDl > 0L) {
                val r: Float = allUl.toFloat() / allDl.toFloat()
                "%.2f".format(r)
            } else "0.00"

            TorrentUiModel(
                id = id, title = name, size = sizeStr, speed = speedStr,
                progress = progInt, status = stateLabel, timeLeft = etaStr,
                seeds = seeds, peers = peers, ratio = ratioStr,
                isPaused = paused,
                isComplete = complete
            )
        } catch (ex: Exception) {
            logE(TAG, "buildItem[$i] failed: ${ex.message}")
            null
        }
    }

    private fun stateToString(state: TorrentStatus.State, progress: Float): String {
        if (progress >= 0.999f || state == TorrentStatus.State.FINISHED || state == TorrentStatus.State.SEEDING) {
            return "Complete"
        }
        return when (state) {
        TorrentStatus.State.CHECKING_FILES       -> "Checking"
        TorrentStatus.State.DOWNLOADING_METADATA -> "Fetching Meta"
        TorrentStatus.State.DOWNLOADING          -> "Downloading"
        TorrentStatus.State.CHECKING_RESUME_DATA -> "Resume Check"
        else                                     -> "Unknown"
    }
    }

    // ── Global Settings (Phase 7) ─────────────────────────────────────────────
    fun setLimits(dlBytes: Int, ulBytes: Int) {
         if (!isStarted) return
         try {
             sessionManager.downloadRateLimit(dlBytes)
             sessionManager.uploadRateLimit(ulBytes)
         } catch (e: Exception) {
             Log.e(TAG, "Error setting limits", e)
         }
    }

    fun pauseAll() {
        if (!isStarted) return
        sessionManager.pause()
    }

    fun resumeIncompleteOnly() {
        if (!isStarted) return
        try {
            sessionManager.resume()
            val handles = sessionManager.swig().get_torrents()
            val count: Int = try { handles.size } catch (_: Exception) { handles.javaClass.getMethod("size").invoke(handles) as Int }
            var incompleteCount = 0
            for (i in 0 until count) {
                val h = TorrentHandle(handles.get(i))
                if (!h.isValid) continue
                val st = h.status()
                val hash = h.infoHash().toHex()
                if (st.progress() >= 0.999f || st.state() == TorrentStatus.State.FINISHED || st.state() == TorrentStatus.State.SEEDING) {
                    h.pause()
                } else if (manuallyPausedHashes.contains(hash)) {
                    setTorrentFlagReflectSafe(h.swig(), "auto_managed", false)
                    setTorrentFlagReflectSafe(h.swig(), "paused", true)
                    h.pause()
                } else {
                    incompleteCount++
                    h.resume()
                }
            }
            if (incompleteCount == 0) {
                sessionManager.pause()
            }
        } catch (ex: Exception) {
            logE(TAG, "resumeIncompleteOnly failed: ${ex.message}")
        }
    }

    fun resumeAll() {
        if (!isStarted) return
        sessionManager.resume()
    }

    // ── Handle lookup ─────────────────────────────────────────────────────────
    private fun findHandle(torrentId: String): TorrentHandle? {
        return findHandleByInfoHash(torrentId)
    }

    fun pauseTorrent(hash: String) {
        try {
            val h = findHandleByInfoHash(hash)
            if (h != null && h.isValid) {
                manuallyPausedHashes.add(hash)
                h.pause()
                android.util.Log.d("TorrentEngine", "SUCCESS: Raw pause() called on $hash")
                emitSnapshotNow()
            } else {
                android.util.Log.e("TorrentEngine", "FAIL: Handle not found for PAUSE hash=$hash")
            }
        } catch (e: Exception) {
            android.util.Log.e("TorrentEngine", "PAUSE CRASH: ${e.message}")
        }
    }

    fun resumeTorrent(hash: String) {
        try {
            val h = findHandleByInfoHash(hash)
            if (h != null && h.isValid) {
                manuallyPausedHashes.remove(hash)
                h.resume()
                android.util.Log.d("TorrentEngine", "SUCCESS: Raw resume() called on $hash")
                emitSnapshotNow()
            } else {
                android.util.Log.e("TorrentEngine", "FAIL: Handle not found for RESUME hash=$hash")
            }
        } catch (e: Exception) {
            android.util.Log.e("TorrentEngine", "RESUME CRASH: ${e.message}")
        }
    }

    private fun setTorrentFlagReflectSafe(swigHandle: Any, flagName: String, set: Boolean) {
        try {
            val clazz = Class.forName("org.libtorrent4j.swig.torrent_flags")
            val field = clazz.getField(flagName)
            val flagObj = field.get(null)
            
            val methodName = if (set) "set_flags" else "unset_flags"
            val altName = if (set) "setFlags" else "unsetFlags"
            
            val m = swigHandle.javaClass.methods.firstOrNull {
                (it.name == methodName || it.name == altName) && it.parameterTypes.size == 1
            }
            if (m != null && flagObj != null) {
                try {
                    m.invoke(swigHandle, flagObj)
                } catch (e: IllegalArgumentException) {
                    val swigValueM = flagObj.javaClass.methods.firstOrNull { it.name == "swigValue" }
                    if (swigValueM != null) {
                        val swigVal = swigValueM.invoke(flagObj)
                        if (swigVal is Number) {
                            when (m.parameterTypes[0].name) {
                                "int", "java.lang.Integer" -> m.invoke(swigHandle, swigVal.toInt())
                                "long", "java.lang.Long" -> m.invoke(swigHandle, swigVal.toLong())
                            }
                        }
                    }
                }
            }
        } catch (e: Exception) {
            android.util.Log.e("TorrentEngine", "Reflect flag \$flagName failed: \${e.message}")
        }
    }

    fun updateTorrentStateOptimistically(hash: String, isPaused: Boolean) {
        val list = _torrentListFlow.value.toMutableList()
        val index = list.indexOfFirst { it.id == hash }
        if (index != -1) {
            val item = list[index]
            list[index] = item.copy(isPaused = isPaused)
            _torrentListFlow.value = list
        }
    }

    fun resume(id: String)  { resumeTorrent(id) }
    fun pause(id: String)   { pauseTorrent(id)  }

    fun removeTorrent(id: String, deleteFiles: Boolean) {
        val h = findHandle(id) ?: run {
            // Even if handle not found, purge from UI state immediately.
            _torrentListFlow.value = _torrentListFlow.value.filter { it.id != id }
            manuallyPausedHashes.remove(id)
            return
        }
        
        // Immediately remove from the UI flow so the card disappears without
        // waiting for the next 1-second poll to re-read the session.
        _torrentListFlow.value = _torrentListFlow.value.filter { it.id != id }
        manuallyPausedHashes.remove(id)

        if (!h.isValid) return
        
        // Use SWIG's delete flag to ensure files are erased if needed
        if (deleteFiles) {
            sessionManager.swig().remove_torrent(h.swig(), session_handle.delete_files)
        } else {
            sessionManager.swig().remove_torrent(h.swig())
        }
        
        // sessionManager.remove(h) will properly clear libtorrent4j's internal state mapping
        // so we can add the exact same hash again later without silent rejections.
        try { sessionManager.remove(h) } catch (_: Exception) {}
    }

    fun getSession(): SessionManager = sessionManager

    // ── 1-second poll → StateFlow ─────────────────────────────────────────────
    private fun startPollLoop() {
        CoroutineScope(Dispatchers.IO).launch {
            while (isStarted) {
                try {
                    val ctx = appCtx
                    if (ctx != null) {
                        _torrentListFlow.value = getTorrentList(ctx)
                    }
                    enforceIdleSessionPolicy()
                } catch (_: Exception) { }
                delay(1000L)
            }
        }
    }
}
