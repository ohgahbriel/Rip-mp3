package com.dgabesilva.ripmp3

import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.media.MediaPlayer
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.SeekBar
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.dgabesilva.ripmp3.databinding.ActivityPlayerBinding
import kotlinx.coroutines.*
import java.io.File
import java.util.Locale
import kotlin.random.Random

class PlayerActivity : AppCompatActivity() {

    data class Track(val file: File, val title: String, var durationMs: Int = 0)

    private lateinit var b: ActivityPlayerBinding
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private val ui = Handler(Looper.getMainLooper())

    private var mp: MediaPlayer? = null
    private var prepared = false
    private val tracks = mutableListOf<Track>()
    private var current = -1
    private var shuffle = false
    private var repeatAll = false
    private var userSeeking = false
    private lateinit var adapter: PlaylistAdapter

    private val ticker = object : Runnable {
        override fun run() {
            val p = mp
            if (p != null && prepared) {
                runCatching {
                    val pos = p.currentPosition
                    b.timeText.text = fmt(pos)
                    if (!userSeeking) b.posBar.progress = pos / 1000
                }
            }
            ui.postDelayed(this, 250)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivityPlayerBinding.inflate(layoutInflater)
        setContentView(b.root)

        b.marquee.isSelected = true // required for marquee scrolling

        adapter = PlaylistAdapter()
        b.playlistView.adapter = adapter
        b.playlistView.setOnItemClickListener { _, _, pos, _ -> play(pos) }

        b.closeBtn.setOnClickListener { finish() }
        b.btnPlay.setOnClickListener { if (current >= 0) resume() else play(0) }
        b.btnPause.setOnClickListener { pause() }
        b.btnStop.setOnClickListener { stopToZero() }
        b.btnPrev.setOnClickListener { step(-1) }
        b.btnNext.setOnClickListener { step(+1) }
        b.btnEject.setOnClickListener { loadTracks() }

        b.btnShuffle.setOnClickListener {
            shuffle = !shuffle
            b.btnShuffle.isSelected = shuffle
            b.btnShuffle.setTextColor(getColor(if (shuffle) R.color.waGreen else R.color.waText))
        }
        b.btnRepeat.setOnClickListener {
            repeatAll = !repeatAll
            b.btnRepeat.isSelected = repeatAll
            b.btnRepeat.setTextColor(getColor(if (repeatAll) R.color.waGreen else R.color.waText))
        }

        b.posBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar, progress: Int, fromUser: Boolean) {
                if (fromUser) b.timeText.text = fmt(progress * 1000)
            }
            override fun onStartTrackingTouch(sb: SeekBar) { userSeeking = true }
            override fun onStopTrackingTouch(sb: SeekBar) {
                userSeeking = false
                if (prepared) runCatching { mp?.seekTo(sb.progress * 1000) }
            }
        })

        b.volBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar, progress: Int, fromUser: Boolean) = applyVolume()
            override fun onStartTrackingTouch(sb: SeekBar) {}
            override fun onStopTrackingTouch(sb: SeekBar) {}
        })

        loadTracks()
        ui.post(ticker)
    }

    // ---------- Playlist ----------

    private fun loadTracks() {
        scope.launch(Dispatchers.IO) {
            val dir = File(getExternalFilesDir(null), "MP3")
            val found = dir.walkTopDown()
                .filter { it.isFile && it.extension.equals("mp3", ignoreCase = true) }
                .sortedBy { it.absolutePath.lowercase(Locale.ROOT) }
                .map { Track(it, it.nameWithoutExtension) }
                .toList()

            withContext(Dispatchers.Main) {
                tracks.clear()
                tracks.addAll(found)
                current = -1
                adapter.notifyDataSetChanged()
                b.plCount.text = "${tracks.size} tracks"
                b.marquee.text =
                    if (tracks.isEmpty()) "NO MP3S FOUND — DOWNLOAD SOME TRACKS FIRST"
                    else "RIP // MP3 — ${tracks.size} TRACKS LOADED. PRESS PLAY."
            }

            // Fill in durations in the background
            found.forEachIndexed { i, t ->
                t.durationMs = readDurationMs(t.file)
                if (i % 8 == 7) withContext(Dispatchers.Main) { adapter.notifyDataSetChanged() }
            }
            withContext(Dispatchers.Main) { adapter.notifyDataSetChanged() }
        }
    }

    // ---------- Transport ----------

    private fun play(index: Int) {
        if (tracks.isEmpty()) return
        val idx = index.coerceIn(0, tracks.size - 1)
        releasePlayer()
        current = idx
        val t = tracks[idx]
        adapter.notifyDataSetChanged()
        b.playlistView.smoothScrollToPosition(idx)
        b.marquee.text = "${idx + 1}. ${t.title}"

        val p = MediaPlayer()
        mp = p
        p.setOnPreparedListener {
            prepared = true
            b.posBar.max = it.duration / 1000
            if (t.durationMs == 0) { t.durationMs = it.duration; adapter.notifyDataSetChanged() }
            b.marquee.text = "${idx + 1}. ${t.title} (${fmt(it.duration)})"
            applyVolume()
            it.start()
            b.spectrum.active = true
        }
        p.setOnCompletionListener { onTrackEnd() }
        p.setOnErrorListener { _, _, _ ->
            b.marquee.text = "ERROR READING: ${t.title}"
            b.spectrum.active = false
            true
        }
        try {
            p.setDataSource(t.file.absolutePath)
            p.prepareAsync()
        } catch (e: Exception) {
            b.marquee.text = "ERROR: ${e.message}"
        }

        scope.launch(Dispatchers.IO) { readAudioInfo(t.file) }
    }

    private fun resume() {
        val p = mp ?: return play(current.coerceAtLeast(0))
        if (prepared && !p.isPlaying) {
            runCatching { p.start() }
            b.spectrum.active = true
        }
    }

    private fun pause() {
        val p = mp ?: return
        if (prepared && p.isPlaying) {
            runCatching { p.pause() }
            b.spectrum.active = false
        }
    }

    private fun stopToZero() {
        val p = mp ?: return
        if (prepared) runCatching {
            p.pause()
            p.seekTo(0)
        }
        b.timeText.text = fmt(0)
        b.posBar.progress = 0
        b.spectrum.active = false
    }

    private fun step(dir: Int) {
        if (tracks.isEmpty()) return
        val next = nextIndex(dir) ?: return
        play(next)
    }

    private fun onTrackEnd() {
        val next = nextIndex(+1)
        if (next != null) play(next)
        else {
            b.spectrum.active = false
            b.marquee.text = "END OF PLAYLIST"
        }
    }

    private fun nextIndex(dir: Int): Int? {
        if (tracks.isEmpty()) return null
        if (shuffle && tracks.size > 1) {
            var r: Int
            do { r = Random.nextInt(tracks.size) } while (r == current)
            return r
        }
        val n = current + dir
        return when {
            n in tracks.indices -> n
            repeatAll -> (n + tracks.size) % tracks.size
            else -> null
        }
    }

    private fun applyVolume() {
        val v = b.volBar.progress / 100f
        runCatching { mp?.setVolume(v * v, v * v) } // squared for a natural taper
    }

    private fun releasePlayer() {
        prepared = false
        mp?.let { p -> runCatching { p.stop() }; runCatching { p.release() } }
        mp = null
    }

    // ---------- Metadata ----------

    private fun readDurationMs(f: File): Int {
        val mmr = MediaMetadataRetriever()
        return try {
            mmr.setDataSource(f.absolutePath)
            mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toIntOrNull() ?: 0
        } catch (e: Exception) { 0 } finally { runCatching { mmr.release() } }
    }

    private suspend fun readAudioInfo(f: File) {
        var kbps: Int? = null
        var khz: Double? = null
        var channels: Int? = null

        val mmr = MediaMetadataRetriever()
        runCatching {
            mmr.setDataSource(f.absolutePath)
            kbps = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_BITRATE)
                ?.toIntOrNull()?.div(1000)
        }
        runCatching { mmr.release() }

        val ex = MediaExtractor()
        runCatching {
            ex.setDataSource(f.absolutePath)
            for (i in 0 until ex.trackCount) {
                val fmt = ex.getTrackFormat(i)
                val mime = fmt.getString(MediaFormat.KEY_MIME) ?: continue
                if (mime.startsWith("audio/")) {
                    khz = fmt.getInteger(MediaFormat.KEY_SAMPLE_RATE) / 1000.0
                    channels = fmt.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                    break
                }
            }
        }
        runCatching { ex.release() }

        withContext(Dispatchers.Main) {
            b.kbpsText.text = if (kbps != null) "$kbps kbps" else "--- kbps"
            b.khzText.text = if (khz != null) String.format(Locale.US, "%.1f kHz", khz) else "-- kHz"
            b.chanText.text = when (channels) {
                1 -> "mono"
                2 -> "stereo"
                null -> "------"
                else -> "${channels}ch"
            }
        }
    }

    private fun fmt(ms: Int): String {
        val s = ms / 1000
        return String.format(Locale.US, "%d:%02d", s / 60, s % 60)
    }

    // ---------- Playlist adapter ----------

    private inner class PlaylistAdapter : BaseAdapter() {
        override fun getCount() = tracks.size
        override fun getItem(position: Int) = tracks[position]
        override fun getItemId(position: Int) = position.toLong()

        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val v = convertView ?: layoutInflater.inflate(R.layout.playlist_item, parent, false)
            val t = tracks[position]
            val title = v.findViewById<TextView>(R.id.trackTitle)
            val dur = v.findViewById<TextView>(R.id.trackDur)
            title.text = "${position + 1}. ${t.title}"
            dur.text = if (t.durationMs > 0) fmt(t.durationMs) else "-:--"
            val isCurrent = position == current
            val color = getColor(if (isCurrent) R.color.waGold else R.color.waGreen)
            title.setTextColor(color)
            dur.setTextColor(color)
            v.setBackgroundColor(getColor(if (isCurrent) R.color.waPanelDeep else android.R.color.transparent))
            return v
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        ui.removeCallbacks(ticker)
        releasePlayer()
        scope.cancel()
    }
}
