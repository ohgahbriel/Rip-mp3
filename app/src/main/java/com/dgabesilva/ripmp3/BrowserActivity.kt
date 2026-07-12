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
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
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
    // allRows is the authoritative, unfiltered list rebuild() produces;
    // rows is what the adapter actually shows and — critically — what the
    // drag handler mutates directly to persist custom order (saveCustomOrder
    // reads straight from `rows`). That mutation-as-source-of-truth only
    // stays correct when rows == allRows, i.e. no filter active, which is
    // exactly when dragging is allowed (see itemTouchHelper below) — a
    // filtered subset has no unambiguous "reorder" meaning, same reasoning
    // as PlayerActivity's queue search.
    private var allRows = listOf<Row>()
    private var rows = listOf<Row>()
    private var filterQuery: String = ""
    private lateinit var adapter: RowAdapter

    private data class Row(val label: String, val secondary: String, val right: String, val track: PlayerService.Track?)

    // ---------- Column sort (track-level rows only) ----------
    private enum class SortField { TITLE, SECONDARY, TIME }
    private var sortField: SortField? = null
    private var sortAsc = true

    // ---------- Manual drag order (persisted per artist/album group) ----------
    private val itemTouchHelper by lazy {
        ItemTouchHelper(object : ItemTouchHelper.SimpleCallback(0, 0) {
            override fun getMovementFlags(rv: RecyclerView, vh: RecyclerView.ViewHolder): Int {
                // Only track-level rows (inside an open group) can be dragged,
                // and only when unfiltered — see the `rows`/`allRows` comment above.
                if (groupKey == null || filterQuery.isNotBlank()) return 0
                return makeMovementFlags(ItemTouchHelper.UP or ItemTouchHelper.DOWN, 0)
            }
            override fun onMove(rv: RecyclerView, vh: RecyclerView.ViewHolder, target: RecyclerView.ViewHolder): Boolean {
                val from = vh.bindingAdapterPosition
                val to = target.bindingAdapterPosition
                if (from == RecyclerView.NO_POSITION || to == RecyclerView.NO_POSITION) return false
                if (rows.getOrNull(from)?.track == null || rows.getOrNull(to)?.track == null) return false
                rows = rows.toMutableList().apply { add(to, removeAt(from)) }
                adapter.notifyItemMoved(from, to)
                val key = groupKey ?: return true
                saveCustomOrder(key, rows.mapNotNull { it.track?.file?.absolutePath })
                // A manual drag supersedes whatever column sort was active
                sortField = null
                updateSortHeader()
                return true
            }
            override fun onSwiped(vh: RecyclerView.ViewHolder, direction: Int) {}
            override fun isLongPressDragEnabled() = false // dragging only starts from the handle
        })
    }

    private fun orderPrefKey(key: String) = "${mode.name}|$key"

    private fun loadCustomOrder(key: String): List<String>? =
        getSharedPreferences(PREFS, MODE_PRIVATE).getString(orderPrefKey(key), null)
            ?.split("\n")?.filter { it.isNotEmpty() }

    private fun saveCustomOrder(key: String, paths: List<String>) {
        getSharedPreferences(PREFS, MODE_PRIVATE).edit()
            .putString(orderPrefKey(key), paths.joinToString("\n"))
            .apply()
    }

    /** Applies any saved manual order for this group; new/unknown tracks land at the end. */
    private fun applyCustomOrder(key: String, list: List<PlayerService.Track>): List<PlayerService.Track> {
        val order = loadCustomOrder(key) ?: return list
        val byPath = list.associateBy { it.file.absolutePath }
        val ordered = order.mapNotNull { byPath[it] }
        val remaining = list.filter { it.file.absolutePath !in order }
        return ordered + remaining
    }

    private companion object {
        const val PREFS = "browser_order"
    }

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
        b.browserList.layoutManager = LinearLayoutManager(this)
        b.browserList.adapter = adapter
        itemTouchHelper.attachToRecyclerView(b.browserList)

        b.closeBtn.setOnClickListener { finish() }
        b.tabArtists.setOnClickListener { switchMode(Mode.ARTISTS) }
        b.tabAlbums.setOnClickListener { switchMode(Mode.ALBUMS) }

        b.browserHeader.hdrTitle.setOnClickListener { applySort(SortField.TITLE) }
        b.browserHeader.hdrSecondary.setOnClickListener { applySort(SortField.SECONDARY) }
        b.browserHeader.hdrTime.setOnClickListener { applySort(SortField.TIME) }

        b.searchInput.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                filterQuery = s?.toString() ?: ""
                applyFilter()
            }
            override fun afterTextChanged(s: android.text.Editable?) {}
        })

        b.playAllBtn.setOnClickListener {
            val key = groupKey ?: return@setOnClickListener
            val list = currentDisplayedTracks()
            if (list.isEmpty()) return@setOnClickListener
            svc?.setQueue(list, key, 0, autoplay = true)
            finish()
        }
        b.queueBtn.setOnClickListener {
            val list = currentDisplayedTracks()
            if (list.isEmpty()) return@setOnClickListener
            val added = svc?.enqueue(list) ?: 0
            flash("QUEUED +$added")
        }

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (groupKey != null) { groupKey = null; clearSearch(); rebuild() } else finish()
            }
        })

        bindService(Intent(this, PlayerService::class.java), conn, Context.BIND_AUTO_CREATE)
    }

    private fun switchMode(m: Mode) {
        mode = m
        groupKey = null
        sortField = null
        clearSearch()
        rebuild()
    }

    private fun clearSearch() {
        if (b.searchInput.text.isNotEmpty()) b.searchInput.text.clear()
    }

    private fun applyFilter() {
        val q = filterQuery.trim()
        rows = if (q.isEmpty()) allRows
               else allRows.filter { r -> r.label.contains(q, ignoreCase = true) || r.secondary.contains(q, ignoreCase = true) }
        adapter.notifyDataSetChanged()
    }

    private fun applySort(field: SortField) {
        if ((groups[groupKey] ?: emptyList()).size < 2) return
        sortAsc = if (sortField == field) !sortAsc else true
        sortField = field
        rebuild()
    }

    private fun sortedTracks(list: List<PlayerService.Track>): List<PlayerService.Track> {
        val field = sortField ?: return list
        val cmp: Comparator<PlayerService.Track> = when (field) {
            SortField.TITLE -> compareBy { it.title.lowercase(Locale.ROOT) }
            SortField.SECONDARY -> compareBy {
                (if (mode == Mode.ARTISTS) it.album else it.artist).lowercase(Locale.ROOT)
            }
            SortField.TIME -> compareBy { it.durationMs }
        }
        return if (sortAsc) list.sortedWith(cmp) else list.sortedWith(cmp.reversed())
    }

    private fun updateSortHeader() {
        fun label(base: String, field: SortField) =
            if (sortField == field) "$base ${if (sortAsc) "▲" else "▼"}" else base
        b.browserHeader.hdrTitle.text = label("TITLE", SortField.TITLE)
        b.browserHeader.hdrSecondary.text = label(if (mode == Mode.ARTISTS) "ALBUM" else "ARTIST", SortField.SECONDARY)
        b.browserHeader.hdrTime.text = label("TIME", SortField.TIME)
    }

    private fun currentDisplayedTracks(): List<PlayerService.Track> = rows.mapNotNull { it.track }

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
            allRows = groups.map { (name, list) -> Row(name, "", "${list.size} trk ▸", null) }
            b.crumb.text = if (mode == Mode.ARTISTS) "ALL ARTISTS (${groups.size})" else "ALL ALBUMS (${groups.size})"
            b.actionRow.visibility = View.GONE
            b.browserHeader.root.visibility = View.GONE
        } else {
            val base = groups[key] ?: emptyList()
            val ordered = applyCustomOrder(key, base)
            val list = sortedTracks(ordered)
            allRows = list.map {
                val secondary = if (mode == Mode.ARTISTS) it.album else it.artist
                Row(it.title, secondary, fmt(it.durationMs), it)
            }
            b.crumb.text = "▸ ${key.uppercase(Locale.ROOT)} (${list.size})"
            b.actionRow.visibility = View.VISIBLE
            b.browserHeader.root.visibility = View.VISIBLE
            updateSortHeader()
        }
        applyFilter()
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

    private inner class RowAdapter : RecyclerView.Adapter<RowAdapter.VH>() {
        inner class VH(view: View) : RecyclerView.ViewHolder(view) {
            val handle: TextView = view.findViewById(R.id.dragHandle)
            val title: TextView = view.findViewById(R.id.trackTitle)
            val secondary: TextView = view.findViewById(R.id.trackSecondary)
            val right: TextView = view.findViewById(R.id.trackDur)
        }

        override fun getItemCount() = rows.size

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            val v = layoutInflater.inflate(R.layout.playlist_item, parent, false)
            return VH(v)
        }

        @android.annotation.SuppressLint("ClickableViewAccessibility")
        override fun onBindViewHolder(holder: VH, position: Int) {
            val row = rows[position]
            holder.title.text = row.label
            holder.secondary.text = row.secondary
            holder.right.text = row.right
            val color = skinColor(if (row.track == null) R.attr.skinAccent else R.attr.skinLcd)
            holder.title.setTextColor(color)
            holder.secondary.setTextColor(color)
            holder.right.setTextColor(color)
            holder.itemView.setBackgroundColor(Color.TRANSPARENT)

            val isLeaf = row.track != null
            holder.handle.visibility = if (isLeaf) View.VISIBLE else View.INVISIBLE
            holder.handle.alpha = if (filterQuery.isBlank()) 1f else 0.3f
            holder.handle.setOnTouchListener { _, event ->
                if (isLeaf && filterQuery.isBlank() && event.actionMasked == MotionEvent.ACTION_DOWN) {
                    itemTouchHelper.startDrag(holder)
                }
                false
            }

            holder.itemView.setOnClickListener {
                val pos = holder.bindingAdapterPosition
                val r = rows.getOrNull(pos) ?: return@setOnClickListener
                if (r.track == null) {
                    groupKey = r.label
                    clearSearch()
                    rebuild()
                } else {
                    val key = groupKey ?: return@setOnClickListener
                    svc?.setQueue(currentDisplayedTracks(), key, pos, autoplay = true)
                    finish()
                }
            }
            holder.itemView.setOnLongClickListener {
                val pos = holder.bindingAdapterPosition
                val r = rows.getOrNull(pos)
                if (r?.track != null) {
                    val added = svc?.enqueue(listOf(r.track)) ?: 0
                    flash(if (added > 0) "QUEUED: ${r.track.title}" else "ALREADY IN QUEUE")
                }
                true
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        svc?.let { if (it.listener === serviceListener) it.listener = null }
        runCatching { unbindService(conn) }
    }
}
