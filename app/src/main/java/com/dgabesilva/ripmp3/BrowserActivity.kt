package com.dgabesilva.ripmp3

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.graphics.Color
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.TextView
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import com.dgabesilva.ripmp3.databinding.ActivityBrowserBinding
import java.util.Locale

/**
 * Library explorer: browse every song on the phone grouped by artist or album,
 * play a whole group or feed it into the current playlist.
 */
class BrowserActivity : AppCompatActivity() {

    private enum class Mode { ARTISTS, ALBUMS }

    private lateinit var b: ActivityBrowserBinding
    private val ui = Handler(Looper.getMainLooper())
    private var svc: PlayerService? = null
    private var mode = Mode.ARTISTS
    private var groupKey: String? = null   // null = group list, else that group's tracks
    private var groups = linkedMapOf<String, List<PlayerService.Track>>()
    private var rows = listOf<Row>()
    private lateinit var adapter: RowAdapter

    private data class Row(val label: String, val right: String, val track: PlayerService.Track?)

    private val serviceListener = object : PlayerService.Listener {
        override fun onTrackChanged(index: Int, track: PlayerService.Track?) {}
        override fun onPlayState(playing: Boolean) {}
        override fun onTracksReloaded() { rebuild() }
    }

    private val conn = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, binder: IBinder) {
            svc = (binder as PlayerService.LocalBinder).service
            svc!!.listener = serviceListener
            rebuild()
        }
        override fun onServiceDisconnected(name: ComponentName) { svc = null }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        Skin.apply(this)
        super.onCreate(savedInstanceState)
        b = ActivityBrowserBinding.inflate(layoutInflater)
        setContentView(b.root)

        adapter = RowAdapter()
        b.browserList.adapter = adapter

        b.closeBtn.setOnClickListener { finish() }
        b.tabArtists.setOnClickListener { switchMode(Mode.ARTISTS) }
        b.tabAlbums.setOnClickListener { switchMode(Mode.ALBUMS) }

        b.playAllBtn.setOnClickListener {
            val (name, list) = currentGroup() ?: return@setOnClickListener
            svc?.setQueue(list, name, 0, autoplay = true)
            finish()
        }
        b.queueBtn.setOnClickListener {
            val (_, list) = currentGroup() ?: return@setOnClickListener
            val added = svc?.enqueue(list) ?: 0
            flash("QUEUED +$added")
        }

        b.browserList.setOnItemClickListener { _, _, pos, _ ->
            val row = rows.getOrNull(pos) ?: return@setOnItemClickListener
            if (row.track == null) {
                groupKey = row.label
                rebuild()
            } else {
                val (name, list) = currentGroup() ?: return@setOnItemClickListener
                svc?.setQueue(list, name, list.indexOf(row.track), autoplay = true)
                finish()
            }
        }
        b.browserList.setOnItemLongClickListener { _, _, pos, _ ->
            val row = rows.getOrNull(pos)
            if (row?.track != null) {
                val added = svc?.enqueue(listOf(row.track)) ?: 0
                flash(if (added > 0) "QUEUED: ${row.track.title}" else "ALREADY IN QUEUE")
            }
            true
        }

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (groupKey != null) { groupKey = null; rebuild() } else finish()
            }
        })

        bindService(Intent(this, PlayerService::class.java), conn, Context.BIND_AUTO_CREATE)
    }

    private fun switchMode(m: Mode) {
        mode = m
        groupKey = null
        rebuild()
    }

    private fun currentGroup(): Pair<String, List<PlayerService.Track>>? {
        val key = groupKey ?: return null
        val list = groups[key] ?: return null
        return key to list
    }

    private fun rebuild() {
        val lib = svc?.library ?: emptyList()
        groups = lib.groupBy {
            if (mode == Mode.ARTISTS) it.artist.ifBlank { "Unknown Artist" }
            else it.album.ifBlank { "Unknown Album" }
        }.toSortedMap(compareBy { it.lowercase(Locale.ROOT) })
            .mapValues { (_, v) -> v.sortedBy { it.title.lowercase(Locale.ROOT) } }
            .toMutableMap() as LinkedHashMap<String, List<PlayerService.Track>>

        // Selected group may have vanished after a rescan
        if (groupKey != null && groupKey !in groups) groupKey = null

        styleTab(b.tabArtists, mode == Mode.ARTISTS)
        styleTab(b.tabAlbums, mode == Mode.ALBUMS)

        val key = groupKey
        if (key == null) {
            rows = groups.map { (name, list) -> Row(name, "${list.size} trk ▸", null) }
            b.crumb.text = if (mode == Mode.ARTISTS) "ALL ARTISTS (${groups.size})" else "ALL ALBUMS (${groups.size})"
            b.actionRow.visibility = View.GONE
        } else {
            val list = groups[key] ?: emptyList()
            rows = list.map { Row(it.title, fmt(it.durationMs), it) }
            b.crumb.text = "▸ ${key.uppercase(Locale.ROOT)} (${list.size})"
            b.actionRow.visibility = View.VISIBLE
        }
        adapter.notifyDataSetChanged()
    }

    private fun styleTab(v: TextView, on: Boolean) {
        v.isSelected = on
        v.setTextColor(skinColor(if (on) R.attr.skinLcd else R.attr.skinText))
    }

    private fun flash(msg: String) {
        val prev = b.crumb.text
        b.crumb.text = msg
        ui.postDelayed({ if (b.crumb.text == msg) b.crumb.text = prev }, 1600)
    }

    private fun fmt(ms: Int): String {
        val s = ms / 1000
        return if (ms <= 0) "-:--" else String.format(Locale.US, "%d:%02d", s / 60, s % 60)
    }

    private inner class RowAdapter : BaseAdapter() {
        override fun getCount() = rows.size
        override fun getItem(position: Int) = rows[position]
        override fun getItemId(position: Int) = position.toLong()

        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val v = convertView ?: layoutInflater.inflate(R.layout.playlist_item, parent, false)
            val row = rows[position]
            val title = v.findViewById<TextView>(R.id.trackTitle)
            val right = v.findViewById<TextView>(R.id.trackDur)
            title.text = row.label
            right.text = row.right
            val color = skinColor(if (row.track == null) R.attr.skinAccent else R.attr.skinLcd)
            title.setTextColor(color)
            right.setTextColor(color)
            v.setBackgroundColor(Color.TRANSPARENT)
            return v
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        svc?.let { if (it.listener === serviceListener) it.listener = null }
        runCatching { unbindService(conn) }
    }
}
