package com.dgabesilva.ripmp3

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * Fixes the two things yt-dlp alone can't: junk-laden titles ("Song (Official
 * Music Video) [HD]") and missing genre/artwork (YouTube exposes no genre/
 * album/artist metadata for regular uploads — verified empirically, not just
 * assumed).
 *
 * Call sites:
 *  - DownloadEngine, right after a fresh download, to add genre + artwork on
 *    top of the already-clean name yt-dlp produced (see its output template).
 *  - DownloadEngine.cleanupLibrary(), a retroactive pass over files that were
 *    downloaded before this existed, deriving artist/title from the current
 *    (messy) filename since there's no yt-dlp session to help this time.
 *  - PlayerActivity's manual "EDIT TAGS" dialog, for the cases neither
 *    automated path gets right (obscure tracks the lookup can't find).
 *
 * Tag writes go through the app's own bundled ffmpeg binary (the same one
 * youtubedl-android already ships and uses internally for -x extraction) via
 * a stream-copy remux — no re-encode, no new Gradle dependency, and it
 * handles both MP3 (ID3) and FLAC (Vorbis comments) with the same
 * `-metadata key=value` flags.
 */
object TagCleaner {

    // Matches a bracket/paren group that contains one of these junk phrases,
    // and removes the WHOLE group — "(Official Music Video)", "[HD]",
    // "(Lyrics)", "(4K Remaster)" all go, not just the keyword inside them.
    private val JUNK_RE = Regex(
        """(?i)[\(\[][^\)\]]*\b(official\s*(music\s*)?video|official\s*audio|official\s*lyric\s*video|lyric\s*video|lyrics?|audio\s*only|visualizer|hd|hq|4k)\b[^\)\]]*[\)\]]"""
    )
    private val WHITESPACE_RE = Regex("""\s{2,}""")
    // Hyphen, en dash, or em dash — covers every "Artist - Title" style split
    // seen in practice, matched non-greedily so a hyphenated title itself
    // ("Anti-Hero") doesn't get chopped at the first dash.
    private val SPLIT_RE = Regex("""^(?<artist>.+?)\s*[-–—]\s*(?<title>.+)$""")

    fun cleanTitle(raw: String): String =
        raw.replace(JUNK_RE, "").replace(WHITESPACE_RE, " ").trim()

    /** Splits "Artist - Title" if the cleaned string has that shape; otherwise (null, cleanedWhole). */
    fun splitArtistTitle(raw: String): Pair<String?, String> {
        val cleaned = cleanTitle(raw)
        val m = SPLIT_RE.find(cleaned)
        return if (m != null) {
            m.groups["artist"]!!.value.trim() to m.groups["title"]!!.value.trim()
        } else {
            null to cleaned
        }
    }

    /**
     * True when [raw] is already "Artist - Title" shaped with no junk left to
     * strip — the retroactive library cleanup skips these entirely (no
     * rename, no network lookup, no ffmpeg call) so re-running it after a
     * first pass is a fast near-no-op instead of re-touching every file.
     */
    fun looksClean(raw: String): Boolean {
        val (artist, _) = splitArtistTitle(raw)
        return artist != null && cleanTitle(raw) == raw
    }

    private fun ffmpegPath(context: Context): String =
        File(context.applicationInfo.nativeLibraryDir, "libffmpeg.so").absolutePath

    data class LookupResult(val genre: String?, val artworkUrl: String?)

    /**
     * Best-effort genre + artwork lookup via the free, keyless iTunes Search
     * API. Never throws — returns nulls on any network failure, empty
     * result, or a match whose artist name doesn't loosely correspond to
     * what we asked for (cheap guard against tagging the wrong genre/art
     * from a bad match).
     */
    suspend fun lookupMetadata(artist: String, title: String): LookupResult = withContext(Dispatchers.IO) {
        try {
            val q = URLEncoder.encode("$artist $title", "UTF-8")
            val url = URL("https://itunes.apple.com/search?term=$q&media=music&entity=song&limit=1")
            val conn = (url.openConnection() as HttpURLConnection).apply {
                connectTimeout = 6000
                readTimeout = 6000
            }
            val body = try {
                conn.inputStream.bufferedReader().use { it.readText() }
            } finally {
                conn.disconnect()
            }
            val results = JSONObject(body).optJSONArray("results")
            if (results == null || results.length() == 0) return@withContext LookupResult(null, null)
            val r = results.getJSONObject(0)
            val gotArtist = r.optString("artistName", "")
            if (!gotArtist.contains(artist, ignoreCase = true) && !artist.contains(gotArtist, ignoreCase = true)) {
                return@withContext LookupResult(null, null)
            }
            val genre = r.optString("primaryGenreName", "").takeIf { it.isNotBlank() }
            // iTunes' default artwork is a 100x100 thumbnail; every artwork
            // URL follows the same "NNNxNNNbb.jpg" suffix convention, so
            // swapping in a bigger size is a plain string substitution —
            // no separate endpoint or extra request needed.
            val artworkUrl = r.optString("artworkUrl100", "").takeIf { it.isNotBlank() }
                ?.replace("100x100bb", "600x600bb")
            LookupResult(genre, artworkUrl)
        } catch (e: Exception) {
            LookupResult(null, null)
        }
    }

    /** Downloads artwork bytes. Best-effort — null on any failure, capped at 5 MB. */
    suspend fun downloadArtwork(url: String): ByteArray? = withContext(Dispatchers.IO) {
        try {
            val conn = (URL(url).openConnection() as HttpURLConnection).apply {
                connectTimeout = 6000
                readTimeout = 8000
            }
            try {
                val bytes = conn.inputStream.use { it.readBytes() }
                bytes.takeIf { it.isNotEmpty() && it.size <= 5 * 1024 * 1024 }
            } finally {
                conn.disconnect()
            }
        } catch (e: Exception) {
            null
        }
    }

    private fun sanitizeFilename(name: String): String =
        name.replace(Regex("""[\\/:*?"<>|]"""), "_").trim().take(180).ifBlank { "untitled" }

    private fun uniqueDest(preferred: File, current: File): File {
        if (preferred.absolutePath == current.absolutePath || !preferred.exists()) return preferred
        val base = preferred.nameWithoutExtension
        val ext = preferred.extension
        var n = 2
        while (true) {
            val candidate = File(preferred.parentFile, "$base ($n).$ext")
            if (!candidate.exists()) return candidate
            n++
        }
    }

    private fun runFfmpeg(args: List<String>, output: File): Boolean = try {
        val proc = ProcessBuilder(args).redirectErrorStream(true).start()
        proc.inputStream.bufferedReader().use { it.readText() } // drain so ffmpeg's pipe can't fill and stall it
        proc.waitFor() == 0 && output.length() > 0
    } catch (e: Exception) {
        false
    }

    /**
     * Rewrites title/artist/genre tags (and embeds artwork, if provided) via
     * a stream-copy ffmpeg remux, and renames the file to match ("Artist -
     * Title.ext", or just "Title.ext" when no artist is known). Returns the
     * file's final location.
     *
     * Two-tier fallback: if the artwork-embedding attempt fails (ffmpeg's
     * attached-pic muxing is pickier than a plain audio-only remux — some
     * downloaded images don't play nice), retries tags-only before giving
     * up. On total failure the original file is left untouched and returned
     * as-is, so a bad remux never loses the track.
     */
    suspend fun retagAndRename(
        context: Context, file: File, artist: String?, title: String, genre: String?, artwork: ByteArray? = null,
    ): File = withContext(Dispatchers.IO) {
        val cleanName = sanitizeFilename(if (artist != null) "$artist - $title" else title) + "." + file.extension
        val dest = uniqueDest(File(file.parentFile, cleanName), current = file)
        val tmp = File(file.parentFile, ".tagtmp_${System.nanoTime()}.${file.extension}")
        val artFile = if (artwork != null) File(file.parentFile, ".tagart_${System.nanoTime()}.jpg") else null

        // Audio-only input args — the art input (when present) is appended
        // explicitly by whichever branch below actually wants it, rather
        // than baked into a shared helper, so the tags-only fallback can't
        // accidentally drag the art input back in.
        val audioInArgs = listOf(ffmpegPath(context), "-y", "-i", file.absolutePath)
        fun metadataArgs(): List<String> {
            val args = mutableListOf("-metadata", "title=$title")
            if (artist != null) { args += "-metadata"; args += "artist=$artist" }
            if (genre != null) { args += "-metadata"; args += "genre=$genre" }
            return args
        }

        var ok = false
        if (artFile != null) {
            try {
                artFile.writeBytes(artwork!!)
                val args = audioInArgs + listOf("-i", artFile.absolutePath) + listOf(
                    "-map", "0:a", "-map", "1:0", "-c", "copy",
                    "-id3v2_version", "3", "-metadata:s:v", "title=Album cover",
                    "-metadata:s:v", "comment=Cover (front)", "-disposition:v:0", "attached_pic",
                ) + metadataArgs() + listOf(tmp.absolutePath)
                ok = runFfmpeg(args, tmp)
            } catch (e: Exception) {
                ok = false
            }
        }
        if (!ok) {
            // Either there was no artwork to embed, or embedding it failed —
            // fall back to a plain tags-only remux (audio input only, no
            // art) so the clean title/artist/genre still land even when
            // the art can't.
            tmp.delete()
            val args = audioInArgs + listOf("-c", "copy") + metadataArgs() + listOf(tmp.absolutePath)
            ok = runFfmpeg(args, tmp)
        }
        artFile?.delete()

        if (ok) {
            file.delete()
            if (!tmp.renameTo(dest)) { tmp.delete(); return@withContext file }
            dest
        } else {
            tmp.delete()
            file
        }
    }

    data class ProgressEvent(val done: Int, val total: Int, val name: String)
    data class CleanupResult(val cleaned: Int, val skipped: Int)

    /**
     * Retroactive cleanup for files downloaded before this pipeline existed:
     * derives artist/title from each file's CURRENT (possibly messy) name —
     * there's no yt-dlp session to consult this time — looks up genre +
     * artwork, and retags+renames in place. Files that already look clean
     * (see [looksClean]) are skipped entirely, so re-running this after a
     * first pass is fast. Lookups run with bounded parallelism (4 at a time)
     * so a big library doesn't serialize one network round-trip per track.
     *
     * Returns the cleaned/skipped counts directly rather than leaving the
     * caller to infer "skipped" from the last progress event — when EVERY
     * file is already clean, [onProgress] never fires at all (nothing messy
     * to iterate), so that inference would silently read as zero skipped.
     */
    suspend fun cleanupLibrary(context: Context, dir: File, onProgress: (ProgressEvent) -> Unit): CleanupResult = coroutineScope {
        val allFiles = dir.walkTopDown()
            .filter { it.isFile && (it.extension.equals("mp3", true) || it.extension.equals("flac", true)) }
            .toList()
        val (clean, messy) = allFiles.partition { looksClean(it.nameWithoutExtension) }
        // AtomicInteger, not a plain var — up to 4 of these run genuinely
        // concurrently under limitedParallelism(4), and a bare `var done++`
        // across threads would lose increments (lost-update race), making
        // the final count drift from messy.size.
        val done = java.util.concurrent.atomic.AtomicInteger(0)
        val lookupDispatcher = Dispatchers.IO.limitedParallelism(4)
        messy.map { f ->
            async(lookupDispatcher) {
                val (artist, title) = splitArtistTitle(f.nameWithoutExtension)
                val meta = if (artist != null) lookupMetadata(artist, title) else LookupResult(null, null)
                val art = meta.artworkUrl?.let { downloadArtwork(it) }
                retagAndRename(context, f, artist, title, meta.genre, art)
                onProgress(ProgressEvent(done.incrementAndGet(), messy.size, title))
            }
        }.awaitAll()
        CleanupResult(cleaned = messy.size, skipped = clean.size)
    }
}