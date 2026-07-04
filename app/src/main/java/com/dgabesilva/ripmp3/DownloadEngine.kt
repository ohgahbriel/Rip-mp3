package com.dgabesilva.ripmp3

import android.content.Context
import android.media.MediaScannerConnection
import com.yausername.ffmpeg.FFmpeg
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLRequest
import kotlinx.coroutines.*
import java.io.File

/**
 * App-wide download engine. Lives outside any activity so downloads keep
 * running while you're on the player screen playing music.
 */
object DownloadEngine {

    data class Status(
        val message: String,
        val progress: Int,   // 0..100, or -1 for none/indeterminate
        val running: Boolean,
        val error: Boolean = false,
        val done: Boolean = false
    )

    interface Listener { fun onDownloadStatus(s: Status) }

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private const val PROCESS_ID = "rip-mp3"

    private var initStarted = false
    var status = Status("Initializing yt-dlp…", -1, running = false); private set
    private var job: Job? = null
    private val listeners = mutableSetOf<Listener>()

    fun addListener(l: Listener) { listeners += l; l.onDownloadStatus(status) }
    fun removeListener(l: Listener) { listeners -= l }

    val isDownloading get() = job?.isActive == true

    private fun post(s: Status) {
        status = s
        scope.launch(Dispatchers.Main) { listeners.toList().forEach { it.onDownloadStatus(s) } }
    }

    fun init(context: Context) {
        if (initStarted) return
        initStarted = true
        val app = context.applicationContext
        scope.launch {
            try {
                YoutubeDL.getInstance().init(app)
                FFmpeg.getInstance().init(app)
                // Keep yt-dlp fresh so YouTube changes don't break it
                runCatching { YoutubeDL.getInstance().updateYoutubeDL(app) }
                post(Status("Ready.", -1, running = false))
            } catch (e: Exception) {
                post(Status("Init failed: ${e.message}", -1, running = false, error = true))
            }
        }
    }

    fun cancel() {
        YoutubeDL.getInstance().destroyProcessById(PROCESS_ID)
        job?.cancel()
        post(Status("Cancelled.", -1, running = false))
    }

    /** [format] is "mp3" or "flac"; [bitrate] only applies to mp3 ("0" = best VBR). */
    fun start(context: Context, url: String, format: String, bitrate: String) {
        if (isDownloading) return
        val app = context.applicationContext
        val outDir = File(app.getExternalFilesDir(null), "MP3").apply { mkdirs() }
        val isPlaylist = url.contains(Regex("[?&]list=")) || url.contains("/playlist")
        post(Status(if (isPlaylist) "Playlist detected — starting…" else "Starting…", 0, running = true))

        job = scope.launch {
            try {
                val outTemplate = if (isPlaylist)
                    "${outDir.absolutePath}/%(playlist_title)s/%(playlist_index)02d - %(title)s.%(ext)s"
                else
                    "${outDir.absolutePath}/%(title)s.%(ext)s"

                val request = YoutubeDLRequest(url).apply {
                    addOption("-x")
                    addOption("--audio-format", format)
                    if (format == "mp3") {
                        addOption("--audio-quality", bitrate)
                        addOption("--embed-thumbnail")
                    }
                    addOption(if (isPlaylist) "--yes-playlist" else "--no-playlist")
                    addOption("--ignore-errors")
                    addOption("--download-archive", File(app.filesDir, "mp3archive.txt").absolutePath)
                    addOption("--add-metadata")
                    addOption("-o", outTemplate)
                }

                var item = 0
                var total = 0
                val itemRe = Regex("""Downloading item (\d+) of (\d+)""")

                YoutubeDL.getInstance().execute(request, PROCESS_ID) { progress, _, line ->
                    itemRe.find(line)?.let {
                        item = it.groupValues[1].toInt()
                        total = it.groupValues[2].toInt()
                    }
                    if (total > 0 && progress >= 0) {
                        val overall = (((item - 1) + progress / 100f) / total * 100).toInt().coerceIn(0, 100)
                        post(Status("Track $item/$total — ${progress.toInt()}%", overall, running = true))
                    } else if (progress >= 0) {
                        val msg = if (progress >= 99f) "Converting to ${format.uppercase()}…"
                                  else "Downloading… ${progress.toInt()}%"
                        post(Status(msg, progress.toInt().coerceIn(0, 100), running = true))
                    }
                }

                // Make files visible to other music apps (including playlist subfolders)
                outDir.walkTopDown().filter { it.isFile }.forEach {
                    MediaScannerConnection.scanFile(app, arrayOf(it.absolutePath), null, null)
                }

                post(Status(
                    if (isPlaylist) "Done! Playlist ($total tracks) saved." else "Done! Saved to files/MP3.",
                    100, running = false, done = true
                ))
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                post(Status("Error: ${e.message}", -1, running = false, error = true))
            }
        }
    }
}
