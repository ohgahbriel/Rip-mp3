package com.dgabesilva.ripmp3

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
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

class PlayerActivity : AppCompatActivity(), DownloadEngine.Listener {

    private lateinit var b: ActivityPlayerBinding
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private val ui = Handler(Looper.getMainLooper())
    private lateinit var adapter: PlaylistAdapter
    private var svc: PlayerService? = null
    private var userSeeking = false

    private val ticker = object : Runnable {
        override fun run() {
            svc?.let { s ->
                if (!userSeeking) {
                    b.timeText.text = fmt(s.positionMs)
                    b.posBar.max = maxOf(1, s.durationMs / 1000)
                    b.posBar.progress = s.positionMs / 1000
                }
                if (b.spectrum.active != s.isPlaying) b.spectrum.active = s.isPlaying
            }
            ui.postDelayed(this, 250)
        }
    }

    private val serviceListener = object : PlayerService.Listener {
        override fun onTrackChanged(index: Int, track: PlayerService.Track?) {
            adapter.notifyDataSetChanged()
            track?.let {
                b.marquee.text = "${index + 1}. ${it.title}"
                b.playlistView.smoothScrollToPosition(index)
                scope.launch(Dispatchers.IO) { readAudioInfo(it.file) }
            }
        }
        override fun onPlayState(playing: Boolean) {
            b.spectrum.active = playing
            svc?.let { b.posBar.max = maxOf(1, it.durationMs / 1000) }
        }
        override fun onTracksReloaded() {
            adapter.notifyDataSetChanged()
            val n = svc?.tracks?.size ?: 0
            b.plCount.text = "$n tracks"
            if (n == 0) b.marquee.text = "NO TRACKS — TAP GET SONGS"
            else if (svc?.currentTrack == null) b.marquee.text = "RIP // MP3 — $n TRACKS LOADED. PRESS PLAY."
        }
    }

    private val conn = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, binder: IBinder) {
            svc = (binder as PlayerService.LocalBinder).service
            svc!!.listener = serviceListener
            syncFromService()
        }
        override fun onServiceDisconnected(name: ComponentName) { svc = null }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivityPlayerBinding.inflate(layoutInflater)
        setContentView(b.root)

        b.marquee.isSelected = true // required for marquee scrolling

        // Shade controls (Android 13+) + audio library access to find songs on the phone
        val perms = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= 33) {
            if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED)
                perms += Manifest.permission.POST_NOTIFICATIONS
            if (checkSelfPermission(Manifest.permission.READ_MEDIA_AUDIO) != PackageManager.PERMISSION_GRANTED)
                perms += Manifest.permission.READ_MEDIA_AUDIO
        } else if (checkSelfPermission(Manifest.permission.READ_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
            perms += Manifest.permission.READ_EXTERNAL_STORAGE
        }
        if (perms.isNotEmpty()) requestPermissions(perms.toTypedArray(), 1)

        DownloadEngine.init(this)

        adapter = PlaylistAdapter()
        b.playlistView.adapter = adapter
        b.playlistView.setOnItemClickListener { _, _, pos, _ -> svc?.play(pos) }

        b.getSongsBtn.setOnClickListener { startActivity(Intent(this, DownloadActivity::class.java)) }
        b.dlStrip.setOnClickListener { startActivity(Intent(this, DownloadActivity::class.java)) }
        b.closeBtn.setOnClickListener { finish() } // music keeps playing via the service

        b.btnPlay.setOnClickListener { svc?.resume() }
        b.btnPause.setOnClickListener { svc?.pause() }
        b.btnStop.setOnClickListener {
            svc?.stopToZero()
            b.timeText.text = fmt(0)
            b.posBar.progress = 0
        }
        b.btnPrev.setOnClickListener { svc?.step(-1) }
        b.btnNext.setOnClickListener { svc?.step(+1) }
        b.btnEject.setOnClickListener { svc?.loadTracks() }

        b.btnShuffle.setOnClickListener {
            val s = svc ?: return@setOnClickListener
            s.shuffle = !s.shuffle
            styleToggle(b.btnShuffle, s.shuffle)
        }
        b.btnRepeat.setOnClickListener {
            val s = svc ?: return@setOnClickListener
            s.repeatAll = !s.repeatAll
            styleToggle(b.btnRepeat, s.repeatAll)
        }

        b.posBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar, progress: Int, fromUser: Boolean) {
                if (fromUser) b.timeText.text = fmt(progress * 1000)
            }
            override fun onStartTrackingTouch(sb: SeekBar) { userSeeking = true }
            override fun onStopTrackingTouch(sb: SeekBar) {
                userSeeking = false
                svc?.seekTo(sb.progress * 1000)
            }
        })

        b.volBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar, progress: Int, fromUser: Boolean) {
                if (fromUser) svc?.volume = progress / 100f
            }
            override fun onStartTrackingTouch(sb: SeekBar) {}
            override fun onStopTrackingTouch(sb: SeekBar) {}
        })

        bindService(Intent(this, PlayerService::class.java), conn, Context.BIND_AUTO_CREATE)
        DownloadEngine.addListener(this)
        ui.post(ticker)
    }

    private fun syncFromService() {
        val s = svc ?: return
        adapter.notifyDataSetChanged()
        b.plCount.text = "${s.tracks.size} tracks"
        b.volBar.progress = (s.volume * 100).toInt()
        styleToggle(b.btnShuffle, s.shuffle)
        styleToggle(b.btnRepeat, s.repeatAll)
        b.spectrum.active = s.isPlaying
        s.currentTrack?.let {
            b.marquee.text = "${s.current + 1}. ${it.title}"
            b.posBar.max = maxOf(1, s.durationMs / 1000)
            scope.launch(Dispatchers.IO) { readAudioInfo(it.file) }
        } ?: serviceListener.onTracksReloaded()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        // Audio permission just granted → rescan so phone songs appear
        svc?.loadTracks()
    }

    private fun styleToggle(v: TextView, on: Boolean) {
        v.isSelected = on
        v.setTextColor(getColor(if (on) R.color.waGreen else R.color.waText))
    }

    // ---------- Download status strip ----------

    override fun onDownloadStatus(s: DownloadEngine.Status) {
        b.dlStrip.visibility = if (s.running || s.error) View.VISIBLE else View.GONE
        b.dlText.text = "DL: ${s.message}"
        b.dlText.setTextColor(getColor(if (s.error) R.color.err else R.color.waGreen))
        b.dlBar.visibility = if (s.running && s.progress >= 0) View.VISIBLE else View.GONE
        if (s.progress >= 0) b.dlBar.progress = s.progress
        if (s.done) svc?.loadTracks() // new tracks straight into the playlist
    }

    // ---------- Metadata chips ----------

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

        val isFlac = f.extension.equals("flac", true)
        withContext(Dispatchers.Main) {
            b.kbpsText.text = when {
                isFlac -> "FLAC"
                kbps != null -> "$kbps kbps"
                else -> "--- kbps"
            }
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
        private val list get() = svc?.tracks ?: emptyList<PlayerService.Track>()
        override fun getCount() = list.size
        override fun getItem(position: Int) = list[position]
        override fun getItemId(position: Int) = position.toLong()

        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val v = convertView ?: layoutInflater.inflate(R.layout.playlist_item, parent, false)
            val t = list[position]
            val title = v.findViewById<TextView>(R.id.trackTitle)
            val dur = v.findViewById<TextView>(R.id.trackDur)
            val flacTag = if (t.file.extension.equals("flac", true)) " [FLAC]" else ""
            title.text = "${position + 1}. ${t.title}$flacTag"
            dur.text = if (t.durationMs > 0) fmt(t.durationMs) else "-:--"
            val isCurrent = position == (svc?.current ?: -1)
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
        DownloadEngine.removeListener(this)
        svc?.let { if (it.listener === serviceListener) it.listener = null }
        runCatching { unbindService(conn) }
        scope.cancel()
    }
}
