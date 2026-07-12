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
                // Real YouTube titles are frequently junk for our purposes —
                // "Artist - Song (Official Music Video) [HD]" — and plain
                // video uploads carry NO native artist/genre/album metadata
                // at all (verified against yt-dlp directly, including on
                // prominent VEVO-distributed videos: every one of those
                // fields comes back empty). So: strip the known-junk
                // bracket/paren groups from the title, then try to split
                // what's left into Artist/Title on a dash. The output
                // template's %(a,b,c)s syntax picks the first non-empty
                // field, so if a video DOES carry native artist/track data
                // (rare, but happens for Content-ID-recognized music) that
                // wins; our regex-derived split is only the fallback, and
                // uploader/channel is the last resort after that.
                val jobStartMs = System.currentTimeMillis()
                val artistField = "%(artist,meta_artist,uploader,channel)s"
                val titleField = "%(track,meta_track,title)s"
                val outTemplate = if (isPlaylist)
                    "${outDir.absolutePath}/%(playlist_title)s/%(playlist_index)02d - $artistField - $titleField.%(ext)s"
                else
                    "${outDir.absolutePath}/$artistField - $titleField.%(ext)s"

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
                    // --replace-in-metadata and --parse-metadata are both
                    // applied strictly in CLI order (verified directly
                    // against yt-dlp — reversing them leaves the junk
                    // phrases baked into the split-out title instead of
                    // stripped first). --replace-in-metadata also takes 3
                    // space-separated args (FIELDS REGEX REPLACE), which
                    // addOption's single flag+value form can't express. So
                    // ALL of these go through addCommands, in this order,
                    // rather than mixing addOption/addCommands and letting
                    // YoutubeDLRequest's own list-concatenation order
                    // (every addOption first, then all addCommands)
                    // silently reorder them.
                    addCommands(listOf(
                        "--replace-in-metadata", "title",
                        "(?i)[\\(\\[][^\\)\\]]*\\b(official\\s*(music\\s*)?video|official\\s*audio|official\\s*lyric\\s*video|lyric\\s*video|lyrics?|audio\\s*only|visualizer|hd|hq|4k)\\b[^\\)\\]]*[\\)\\]]",
                        ""
                    ))
                    addCommands(listOf("--replace-in-metadata", "title", "\\s{2,}", " "))
                    addCommands(listOf("--replace-in-metadata", "title", "^\\s+|\\s+$", ""))
                    addCommands(listOf("--parse-metadata", "title:(?P<meta_artist>.+?)\\s*[-–—]\\s*(?P<meta_track>.+)"))
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

                // Genre + artwork pass: yt-dlp's own metadata cleanup (above)
                // already gives fresh downloads a clean "Artist - Title" name
                // and embeds whatever native artist/track data existed, but
                // genre and cover art are never available from YouTube itself
                // — this looks them up separately and writes them in. Always
                // retags (not just when a genre/art match is found) so the
                // embedded artist/title tags stay consistent with the
                // filename even on a lookup miss. Scoped to files this job
                // actually produced (mtime since the job started, with a
                // little slack for clock skew) so this never touches
                // unrelated library files.
                val freshFiles = outDir.walkTopDown()
                    .filter { it.isFile && it.lastModified() >= jobStartMs - 2000 &&
                        (it.extension.equals("mp3", true) || it.extension.equals("flac", true)) }
                    .toList()
                if (freshFiles.isNotEmpty()) {
                    post(Status("Tagging…", 100, running = true))
                    for (f in freshFiles) {
                        val (artist, title) = TagCleaner.splitArtistTitle(f.nameWithoutExtension)
                        val meta = if (artist != null) TagCleaner.lookupMetadata(artist, title)
                                   else TagCleaner.LookupResult(null, null)
                        val art = meta.artworkUrl?.let { TagCleaner.downloadArtwork(it) }
                        TagCleaner.retagAndRename(app, f, artist, title, meta.genre, art)
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

    /**
     * Retroactive cleanup for songs downloaded before the metadata pipeline
     * in [start] existed — the messy "mp3rip - Artist: X Song: Y"-style names
     * some yt-dlp extractions produced. Derives artist/title from each
     * file's current name (there's no yt-dlp session to consult this time),
     * looks up genre, and retags+renames in place. Reuses [start]'s Status/
     * Listener broadcast so the existing download-progress UI (dlStrip in
     * PlayerActivity) shows this too, with no new wiring needed there.
     */
    fun cleanupLibrary(context: Context) {
        if (isDownloading) return
        val app = context.applicationContext
        val outDir = File(app.getExternalFilesDir(null), "MP3")
        if (!outDir.isDirectory) {
            post(Status("No downloads folder yet.", -1, running = false, error = true))
            return
        }
        post(Status("Scanning library…", 0, running = true))
        job = scope.launch {
            try {
                val result = TagCleaner.cleanupLibrary(app, outDir) { ev ->
                    if (ev.total > 0) {
                        val pct = (ev.done * 100 / ev.total).coerceIn(0, 100)
                        post(Status("Cleaning ${ev.done}/${ev.total} — ${ev.name}", pct, running = true))
                    }
                }
                outDir.walkTopDown().filter { it.isFile }.forEach {
                    MediaScannerConnection.scanFile(app, arrayOf(it.absolutePath), null, null)
                }
                val skippedNote = if (result.skipped > 0) " (${result.skipped} already clean)" else ""
                post(Status("Done! Titles cleaned up.$skippedNote", 100, running = false, done = true))
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                post(Status("Cleanup error: ${e.message}", -1, running = false, error = true))
            }
        }
    }
}
