package com.dgabesilva.ripmp3

import android.Manifest
import android.app.Dialog
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.PopupWindow
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
    private val density by lazy { resources.displayMetrics.density }

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
            val s = svc ?: return
            b.plName.text = s.queueName
            b.plCount.text = "${s.tracks.size} trk"
            if (s.tracks.isEmpty() && s.currentTrack == null)
                b.marquee.text =
                    if (s.queueName == "LIBRARY") "NO TRACKS — TAP GET SONGS"
                    else "EMPTY LIST — ADD FROM BROWSER"
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
        Skin.apply(this)
        super.onCreate(savedInstanceState)
        b = ActivityPlayerBinding.inflate(layoutInflater)
        setContentView(b.root)

        b.marquee.isSelected = true // required for marquee scrolling
        b.spectrum.setPalette(Skin.spectrum(this))

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
        b.playlistView.setOnItemLongClickListener { _, _, pos, _ ->
            // Long-press removes a track from the queue (not from disk)
            val s = svc ?: return@setOnItemLongClickListener true
            if (pos in s.tracks.indices) {
                val t = s.tracks[pos]
                val list = s.tracks.toMutableList().also { it.removeAt(pos) }
                s.setQueue(list, s.queueName)
                flashMarquee("REMOVED: ${t.title}")
            }
            true
        }

        b.menuBtn.setOnClickListener { showMenu(it) }
        b.getSongsBtn.setOnClickListener { startActivity(Intent(this, DownloadActivity::class.java)) }
        b.browserBtn.setOnClickListener { startActivity(Intent(this, BrowserActivity::class.java)) }
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

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        // Audio permission just granted → rescan so phone songs appear
        svc?.loadTracks()
    }

    private fun syncFromService() {
        val s = svc ?: return
        adapter.notifyDataSetChanged()
        b.plName.text = s.queueName
        b.plCount.text = "${s.tracks.size} trk"
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

    private fun styleToggle(v: TextView, on: Boolean) {
        v.isSelected = on
        v.setTextColor(skinColor(if (on) R.attr.skinLcd else R.attr.skinText))
    }

    private fun flashMarquee(msg: String) {
        val prev = b.marquee.text
        b.marquee.text = msg
        ui.postDelayed({ if (b.marquee.text == msg) b.marquee.text = prev }, 2200)
    }

    // ---------- ≡ menu ----------

    private fun showMenu(anchor: View) {
        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = getDrawable(R.drawable.bevel_raised)
            val p = (5 * density).toInt()
            setPadding(p, p, p, p)
            minimumWidth = (210 * density).toInt()
        }
        val popup = PopupWindow(panel, ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, true)
        popup.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        popup.elevation = 12f

        fun item(label: String, action: () -> Unit) {
            panel.addView(TextView(this).apply {
                text = label
                setTextColor(skinColor(R.attr.skinText))
                textSize = 12f
                typeface = Typeface.MONOSPACE
                letterSpacing = 0.08f
                setPadding((12 * density).toInt(), (10 * density).toInt(), (12 * density).toInt(), (10 * density).toInt())
                setOnClickListener { popup.dismiss(); action() }
            })
        }
        fun sep() {
            panel.addView(View(this).apply {
                setBackgroundColor(skinColor(R.attr.skinBevelDark))
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, (1.5f * density).toInt()
                ).apply { topMargin = (3 * density).toInt(); bottomMargin = (3 * density).toInt() }
            })
        }

        item("⇩ GET SONGS") { startActivity(Intent(this, DownloadActivity::class.java)) }
        item("◫ BROWSER") { startActivity(Intent(this, BrowserActivity::class.java)) }
        sep()
        item("＋ NEW PLAYLIST") { svc?.newQueue(); flashMarquee("NEW EMPTY LIST — ADD FROM BROWSER") }
        item("💾 SAVE PLAYLIST") { savePlaylistDialog() }
        item("▤ LOAD PLAYLIST") { loadPlaylistDialog() }
        item("✕ DELETE PLAYLIST") { deletePlaylistDialog() }
        item("♫ WHOLE LIBRARY") { svc?.resetToLibrary() }
        sep()
        item("◨ SKINS") { skinsDialog() }
        item("⟳ RESCAN LIBRARY") { svc?.loadTracks(); flashMarquee("RESCANNING…") }

        popup.showAsDropDown(anchor, 0, (4 * density).toInt())
    }

    // ---------- Winamp-style dialogs ----------

    private fun waDialog(title: String, build: (LinearLayout, Dialog) -> Unit) {
        val dialog = Dialog(this)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = getDrawable(R.drawable.bevel_raised)
            val p = (10 * density).toInt()
            setPadding(p, p, p, p)
        }
        root.addView(TextView(this).apply {
            text = "◢ $title"
            setTextColor(skinColor(R.attr.skinAccent))
            textSize = 12f
            typeface = Typeface.MONOSPACE
            setTypeface(typeface, Typeface.BOLD)
            letterSpacing = 0.15f
            setPadding(0, 0, 0, (8 * density).toInt())
        })
        build(root, dialog)
        dialog.setContentView(root)
        dialog.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        dialog.window?.setLayout((300 * density).toInt(), ViewGroup.LayoutParams.WRAP_CONTENT)
        dialog.show()
    }

    private fun waListDialog(title: String, items: List<String>, empty: String, onPick: (Int) -> Unit) {
        waDialog(title) { root, dialog ->
            if (items.isEmpty()) {
                root.addView(TextView(this).apply {
                    text = empty
                    setTextColor(skinColor(R.attr.skinText))
                    textSize = 12f
                    typeface = Typeface.MONOSPACE
                    setPadding((6 * density).toInt(), (6 * density).toInt(), (6 * density).toInt(), (10 * density).toInt())
                })
            }
            items.forEachIndexed { i, name ->
                root.addView(TextView(this).apply {
                    text = name
                    setTextColor(skinColor(R.attr.skinLcd))
                    textSize = 13f
                    typeface = Typeface.MONOSPACE
                    background = getDrawable(R.drawable.lcd_sunken)
                    setPadding((10 * density).toInt(), (9 * density).toInt(), (10 * density).toInt(), (9 * density).toInt())
                    layoutParams = LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
                    ).apply { bottomMargin = (4 * density).toInt() }
                    setOnClickListener { dialog.dismiss(); onPick(i) }
                })
            }
        }
    }

    private fun savePlaylistDialog() {
        val s = svc ?: return
        if (s.tracks.isEmpty()) { flashMarquee("NOTHING TO SAVE — QUEUE IS EMPTY"); return }
        waDialog("SAVE PLAYLIST") { root, dialog ->
            val input = EditText(this).apply {
                setText(if (s.queueName != "LIBRARY" && s.queueName != "NEW LIST") s.queueName else "")
                hint = "playlist name"
                setTextColor(skinColor(R.attr.skinLcd))
                setHintTextColor(skinColor(R.attr.skinBevelLight))
                textSize = 14f
                typeface = Typeface.MONOSPACE
                background = getDrawable(R.drawable.lcd_sunken)
                setPadding((10 * density).toInt(), (10 * density).toInt(), (10 * density).toInt(), (10 * density).toInt())
            }
            root.addView(input)
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                setPadding(0, (8 * density).toInt(), 0, 0)
            }
            fun btn(label: String, action: () -> Unit) = TextView(this).apply {
                text = label
                gravity = Gravity.CENTER
                setTextColor(skinColor(R.attr.skinAccent))
                textSize = 12f
                typeface = Typeface.MONOSPACE
                setTypeface(typeface, Typeface.BOLD)
                background = getDrawable(R.drawable.wa_button)
                layoutParams = LinearLayout.LayoutParams(0, (38 * density).toInt(), 1f)
                    .apply { marginEnd = (4 * density).toInt() }
                setOnClickListener { action() }
            }
            row.addView(btn("SAVE") {
                val name = input.text.toString().trim()
                if (name.isNotEmpty()) {
                    val clean = PlaylistStore.save(this, name, s.tracks)
                    s.renameQueue(clean)
                    flashMarquee("SAVED: $clean (${s.tracks.size} TRK)")
                    dialog.dismiss()
                }
            })
            row.addView(btn("CANCEL") { dialog.dismiss() })
            root.addView(row)
        }
    }

    private fun loadPlaylistDialog() {
        val names = PlaylistStore.list(this)
        waListDialog("LOAD PLAYLIST", names, "NO SAVED PLAYLISTS YET") { i ->
            val s = svc ?: return@waListDialog
            val byPath = s.library.associateBy { it.file.absolutePath }
            val loaded = PlaylistStore.load(this, names[i]).map { f ->
                byPath[f.absolutePath] ?: PlayerService.Track(f, f.nameWithoutExtension)
            }
            s.setQueue(loaded, names[i])
            flashMarquee("LOADED: ${names[i]} (${loaded.size} TRK)")
        }
    }

    private fun deletePlaylistDialog() {
        val names = PlaylistStore.list(this)
        waListDialog("DELETE PLAYLIST", names, "NO SAVED PLAYLISTS YET") { i ->
            PlaylistStore.delete(this, names[i])
            flashMarquee("DELETED: ${names[i]}")
        }
    }

    private fun skinsDialog() {
        waListDialog("SELECT SKIN", Skin.NAMES, "") { i ->
            if (i != Skin.get(this)) {
                Skin.set(this, i)
                recreate()
            }
        }
    }

    // ---------- Download status strip ----------

    override fun onDownloadStatus(s: DownloadEngine.Status) {
        b.dlStrip.visibility = if (s.running || s.error) View.VISIBLE else View.GONE
        b.dlText.text = "DL: ${s.message}"
        b.dlText.setTextColor(if (s.error) getColor(R.color.err) else skinColor(R.attr.skinLcd))
        b.dlBar.visibility = if (s.running && s.progress >= 0) View.VISIBLE else View.GONE
        if (s.progress >= 0) b.dlBar.progress = s.progress
        if (s.done) svc?.loadTracks() // new tracks straight into the library
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
            val color = skinColor(if (isCurrent) R.attr.skinAccent else R.attr.skinLcd)
            title.setTextColor(color)
            dur.setTextColor(color)
            v.setBackgroundColor(if (isCurrent) skinColor(R.attr.skinPanelDeep) else Color.TRANSPARENT)
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
