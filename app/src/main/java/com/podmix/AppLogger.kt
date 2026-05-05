package com.podmix

import android.content.Context
import android.util.Log
import java.io.File
import java.io.FileWriter
import java.io.PrintWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

/**
 * AppLogger — log structuré vers fichier + logcat.
 * Pull: adb pull /sdcard/Android/data/com.podmix/files/podmix_debug.log
 *
 * Tags: PLAYER, NET, SEARCH, DEEZER, SPOTIFY, DOWNLOAD, ENRICH, TRACKLIST, SC, YT, UI, DB, ERROR
 */
object AppLogger {

    private const val TAG = "PodMixLog"
    private const val MAX_FILE_SIZE = 5 * 1024 * 1024L  // 5MB
    private const val LOG_FILE = "podmix_debug.log"
    private const val LOG_PREV  = "podmix_debug.prev.log"

    private val fmt = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)
    private val dateFmt = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)

    private var logFile: File? = null
    private var writer: PrintWriter? = null
    private val queue = LinkedBlockingQueue<String>(4096)
    private val running = AtomicBoolean(false)

    /** Appeler dans Application.onCreate() */
    fun init(context: Context) {
        val dir = context.getExternalFilesDir(null) ?: context.filesDir
        logFile = File(dir, LOG_FILE)

        // Rotation si > 5MB
        if (logFile!!.exists() && logFile!!.length() > MAX_FILE_SIZE) {
            File(dir, LOG_PREV).delete()
            logFile!!.renameTo(File(dir, LOG_PREV))
        }

        try {
            writer = PrintWriter(FileWriter(logFile!!, true), true)
            writer!!.println("\n===== SESSION START ${dateFmt.format(Date())} =====")
        } catch (e: Exception) {
            Log.e(TAG, "AppLogger init failed: ${e.message}")
        }

        running.set(true)
        thread(isDaemon = true, name = "PodMixLogger") {
            while (running.get()) {
                try {
                    val line = queue.poll(1, java.util.concurrent.TimeUnit.SECONDS) ?: continue
                    writer?.println(line)
                } catch (_: InterruptedException) {}
            }
        }

        log("SYS", "Logger initialized → ${logFile?.absolutePath}")
    }

    fun close() {
        running.set(false)
        writer?.flush()
        writer?.close()
    }

    /** Log principal — [tag] event | detail */
    fun log(tag: String, event: String, detail: String = "") {
        val ts = fmt.format(Date())
        val line = if (detail.isBlank()) "$ts [$tag] $event"
                   else "$ts [$tag] $event | $detail"
        Log.i(TAG, line)
        queue.offer(line)
    }

    fun err(tag: String, event: String, detail: String = "") {
        val ts = fmt.format(Date())
        val line = "$ts [ERR/$tag] $event | $detail"
        Log.e(TAG, line)
        queue.offer(line)
    }

    // ── Helpers sémantiques ─────────────────────────────────────────────────

    /** Lecture : play, pause, stop, seek, skip, error */
    fun player(event: String, detail: String = "") = log("PLAYER", event, detail)

    /** Résolution URL (SC, YT) */
    fun resolve(event: String, detail: String = "") = log("RESOLVE", event, detail)

    /** Requêtes réseau (hors intercepteur) */
    fun net(event: String, detail: String = "") = log("NET", event, detail)

    /** Recherche SC/YT/DDG */
    fun search(event: String, detail: String = "") = log("SEARCH", event, detail)

    /** Deezer */
    fun deezer(event: String, detail: String = "") = log("DEEZER", event, detail)

    /** Spotify */
    fun spotify(event: String, detail: String = "") = log("SPOTIFY", event, detail)

    /** Téléchargements */
    fun download(event: String, detail: String = "") = log("DOWNLOAD", event, detail)

    /** Enrichissement épisodes */
    fun enrich(event: String, detail: String = "") = log("ENRICH", event, detail)

    /** Pipeline tracklist */
    fun tracklist(event: String, detail: String = "") = log("TRACKLIST", event, detail)

    /** UI navigation */
    fun ui(event: String, detail: String = "") = log("UI", event, detail)

    /** DB ops */
    fun db(event: String, detail: String = "") = log("DB", event, detail)

    /** Chemin du fichier log (pour afficher à l'user) */
    fun logFilePath(): String = logFile?.absolutePath ?: "not initialized"
}
