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
 * Music Video) [HD]") and missing genre (YouTube exposes no genre/album/artist
 * metadata for regular uploads — verified empirically, not just assumed).
 *
 * Two call sites:
 *  - DownloadEngine, right after a fresh download, to add a genre tag on top
 *    of the already-clean name yt-dlp produced (see its output template).
 *  - DownloadEngine.cleanupLibrary(), a retroactive pass over files that were
 *    downloaded before this existed, deriving artist/title from the current
 *    (messy) filename since there's no yt-dlp session to help this time.
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

    private fun ffmpegPath(context: Context): String =
        File(context.applicationInfo.nativeLibraryDir, "libffmpeg.so").absolutePath

    /**
     * Best-effort genre lookup via the free, keyless iTunes Search API.
     * Never throws — returns null on any network failure, empty result, or
     * a match whose artist name doesn't loosely correspond to what we asked
     * for (cheap guard against tagging the wrong genre from a bad match).
     */
    suspend fun lookupGenre(artist: String, title: String): String? = withContext(Dispatchers.IO) {
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
            val results = JSONObject(body).optJSONArray("results") ?: return@withContext null
            if (results.length() == 0) return@withContext null
            val r = results.getJSONObject(0)
            val gotArtist = r.optString("artistName", "")
            if (!gotArtist.contains(artist, ignoreCase = true) && !artist.contains(gotArtist, ignoreCase = true)) {
                return@withContext null
            }
            r.optString("primaryGenreName", "").takeIf { it.isNotBlank() }
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

    /**
     * Rewrites title/artist/genre tags via a stream-copy ffmpeg remux and
     * renames the file to match ("Artist - Title.ext", or just "Title.ext"
     * when no artist is known). Returns the file's final location — on any
     * ffmpeg failure the original file is left untouched and returned as-is,
     * so a bad remux never loses the track.
     */
    suspend fun retagAndRename(context: Context, file: File, artist: String?, title: String, genre: String?): File =
        withContext(Dispatchers.IO) {
            val cleanName = sanitizeFilename(if (artist != null) "$artist - $title" else title) + "." + file.extension
            val dest = uniqueDest(File(file.parentFile, cleanName), current = file)
            val tmp = File(file.parentFile, ".tagtmp_${System.nanoTime()}.${file.extension}")

            val args = mutableListOf(
                ffmpegPath(context), "-y", "-i", file.absolutePath, "-c", "copy",
                "-metadata", "title=$title",
            )
            if (artist != null) { args += "-metadata"; args += "artist=$artist" }
            if (genre != null) { args += "-metadata"; args += "genre=$genre" }
            args += tmp.absolutePath

            val ok = try {
                val proc = ProcessBuilder(args).redirectErrorStream(true).start()
                proc.inputStream.bufferedReader().use { it.readText() } // drain so ffmpeg's pipe can't fill and stall it
                proc.waitFor() == 0 && tmp.length() > 0
            } catch (e: Exception) {
                false
            }

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

    /**
     * Retroactive cleanup for files downloaded before this pipeline existed:
     * derives artist/title from each file's CURRENT (possibly messy) name —
     * there's no yt-dlp session to consult this time — looks up genre, and
     * retags+renames in place. Genre lookups run with bounded parallelism
     * (4 at a time) so a big library doesn't serialize one network
     * round-trip per track.
     */
    suspend fun cleanupLibrary(context: Context, dir: File, onProgress: (ProgressEvent) -> Unit) = coroutineScope {
        val files = dir.walkTopDown()
            .filter { it.isFile && (it.extension.equals("mp3", true) || it.extension.equals("flac", true)) }
            .toList()
        // AtomicInteger, not a plain var — up to 4 of these run genuinely
        // concurrently under limitedParallelism(4), and a bare `var done++`
        // across threads would lose increments (lost-update race), making
        // the final count drift from files.size.
        val done = java.util.concurrent.atomic.AtomicInteger(0)
        val lookupDispatcher = Dispatchers.IO.limitedParallelism(4)
        files.map { f ->
            async(lookupDispatcher) {
                val (artist, title) = splitArtistTitle(f.nameWithoutExtension)
                val genre = if (artist != null) lookupGenre(artist, title) else null
                retagAndRename(context, f, artist, title, genre)
                onProgress(ProgressEvent(done.incrementAndGet(), files.size, title))
            }
        }.awaitAll()
    }
}