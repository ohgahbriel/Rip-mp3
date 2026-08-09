package com.dgabesilva.ripmp3

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * Synced-lyrics lookup via LRCLIB (https://lrclib.net) — free, keyless, no
 * account, same spirit as the keyless iTunes tagging path. Best-effort: every
 * failure (offline, 404, empty) returns an empty result rather than throwing.
 *
 * Tries the exact endpoint first (artist + title + duration, which LRCLIB uses
 * to disambiguate) and falls back to a fuzzy search. Returns timestamped
 * ("synced") lines when available so the UI can highlight/scroll along and let
 * you tap a line to jump — otherwise plain text.
 */
object Lyrics {

    data class Line(val timeMs: Int, val text: String)
    data class Result(val synced: List<Line>?, val plain: String?) {
        val isEmpty get() = synced.isNullOrEmpty() && plain.isNullOrBlank()
    }

    // LRCLIB asks clients to identify themselves in the User-Agent.
    private const val UA = "RIPMP3/2.1 (https://github.com/ohgahbriel/Rip-mp3)"

    suspend fun fetch(artist: String, title: String, durationSec: Int): Result = withContext(Dispatchers.IO) {
        val a = artist.takeUnless { it.isBlank() || it == "Unknown Artist" || it == "RIP DOWNLOADS" } ?: ""
        exactGet(a, title, durationSec)?.takeIf { !it.isEmpty }?.let { return@withContext it }
        searchGet(a, title)?.let { return@withContext it }
        Result(null, null)
    }

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")

    private fun http(urlStr: String): String? = try {
        val conn = (URL(urlStr).openConnection() as HttpURLConnection).apply {
            connectTimeout = 6000
            readTimeout = 6000
            setRequestProperty("User-Agent", UA)
        }
        try {
            if (conn.responseCode != 200) null
            else conn.inputStream.bufferedReader().use { it.readText() }
        } finally {
            conn.disconnect()
        }
    } catch (e: Exception) {
        null
    }

    private fun exactGet(artist: String, title: String, durationSec: Int): Result? {
        val url = "https://lrclib.net/api/get?artist_name=${enc(artist)}&track_name=${enc(title)}&duration=$durationSec"
        val body = http(url) ?: return null
        return runCatching { parseObj(JSONObject(body)) }.getOrNull()
    }

    private fun searchGet(artist: String, title: String): Result? {
        val url = "https://lrclib.net/api/search?track_name=${enc(title)}" +
            if (artist.isNotBlank()) "&artist_name=${enc(artist)}" else ""
        val body = http(url) ?: return null
        return runCatching {
            val arr = JSONArray(body)
            if (arr.length() == 0) null else parseObj(arr.getJSONObject(0))
        }.getOrNull()
    }

    private fun parseObj(o: JSONObject): Result {
        val synced = o.optString("syncedLyrics", "").takeIf { it.isNotBlank() }?.let { parseLrc(it) }
        val plain = o.optString("plainLyrics", "").takeIf { it.isNotBlank() }
        return Result(synced, plain)
    }

    // [mm:ss], [mm:ss.xx] or [mm:ss.xxx]; a line may carry several timestamps.
    private val TS = Regex("""\[(\d{1,2}):(\d{2})(?:[.:](\d{1,3}))?]""")

    fun parseLrc(lrc: String): List<Line> {
        val out = mutableListOf<Line>()
        for (raw in lrc.lines()) {
            val stamps = TS.findAll(raw).toList()
            if (stamps.isEmpty()) continue
            val text = raw.substring(stamps.last().range.last + 1).trim()
            for (m in stamps) {
                val min = m.groupValues[1].toInt()
                val sec = m.groupValues[2].toInt()
                val frac = m.groupValues[3]
                val fracMs = when (frac.length) {
                    0 -> 0
                    1 -> frac.toInt() * 100
                    2 -> frac.toInt() * 10
                    else -> frac.take(3).toInt()
                }
                out.add(Line(min * 60000 + sec * 1000 + fracMs, text))
            }
        }
        return out.sortedBy { it.timeMs }
    }
}
