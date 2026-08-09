package com.dgabesilva.ripmp3

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.app.Dialog
import android.content.ServiceConnection
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.media.MediaScannerConnection
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.PopupWindow
import android.widget.TextView
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.dgabesilva.ripmp3.databinding.ActivityBrowserBinding
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.util.Locale

/**
 * Library explorer: browse every song on the phone grouped by artist or album,
 * play a whole group or feed it into the current playlist.
 */
class BrowserActivity : AppCompatActivity() {

    private enum class Mode { ARTISTS, ALBUMS, SONGS, GENRE, FOLDER }

    private lateinit var b: ActivityBrowserBinding
    private val ui = Handler(Looper.getMainLooper())
    private val density by lazy { resources.displayMetrics.density }
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
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

    // ---------- Multi-select (batch rename) ----------
    // Off by default; toggled on from a track's long-press menu or the SELECT
    // button. Tracks are held by file path (stable across the rescans a rename
    // triggers) rather than by Track identity.
    private var selectMode = false
    private val selected = linkedSetOf<String>()

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
        b.tabSongs.setOnClickListener { switchMode(Mode.SONGS) }
        b.tabGenre.setOnClickListener { switchMode(Mode.GENRE) }
        b.tabFolder.setOnClickListener { switchMode(Mode.FOLDER) }

        b.selectBtn.setOnClickListener { enterSelectMode(null) }
        b.selAllBtn.setOnClickListener {
            currentDisplayedTracks().forEach { selected += it.file.absolutePath }
            updateSelBar(); adapter.notifyDataSetChanged()
        }
        b.renameBtn.setOnClickListener { batchRenameDialog() }
        b.selDoneBtn.setOnClickListener { exitSelectMode() }

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
            val key = trackListKey() ?: return@setOnClickListener
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
                when {
                    selectMode -> exitSelectMode()
                    groupKey != null -> { groupKey = null; clearSearch(); rebuild() }
                    else -> finish()
                }
            }
        })

        bindService(Intent(this, PlayerService::class.java), conn, Context.BIND_AUTO_CREATE)
    }

    private fun switchMode(m: Mode) {
        if (selectMode) exitSelectMode(rebuild = false)
        mode = m
        groupKey = null
        sortField = null
        clearSearch()
        rebuild()
    }

    // ---------- Multi-select mode ----------

    /** Enters batch-select mode, optionally pre-selecting [preselect]. Only usable inside a track list. */
    private fun enterSelectMode(preselect: PlayerService.Track?) {
        if (trackListKey() == null) { flash("OPEN A LIST OR SONGS TAB FIRST"); return }
        selectMode = true
        preselect?.let { selected += it.file.absolutePath }
        b.selectBar.visibility = View.VISIBLE
        updateSelBar()
        adapter.notifyDataSetChanged()
    }

    private fun exitSelectMode(rebuild: Boolean = true) {
        selectMode = false
        selected.clear()
        b.selectBar.visibility = View.GONE
        if (rebuild) adapter.notifyDataSetChanged()
    }

    private fun toggleSelection(track: PlayerService.Track) {
        val path = track.file.absolutePath
        if (!selected.remove(path)) selected += path
        updateSelBar()
    }

    private fun updateSelBar() {
        b.selCount.text = "${selected.size} SELECTED"
    }

    private fun clearSearch() {
        if (b.searchInput.text.isNotEmpty()) b.searchInput.text.clear()
    }

    /** The name to stamp on a queue built from what's on screen: the open group, or "ALL SONGS" for the flat tab. */
    private fun trackListKey(): String? = if (mode == Mode.SONGS) "ALL SONGS" else groupKey

    /** The value a track groups under in the current tab (SONGS never groups). */
    private fun groupValueOf(t: PlayerService.Track): String = when (mode) {
        Mode.ARTISTS -> t.artist.ifBlank { "Unknown Artist" }
        Mode.ALBUMS -> t.album.ifBlank { "Unknown Album" }
        Mode.GENRE -> t.genre.ifBlank { "Unknown Genre" }
        Mode.FOLDER -> t.file.parentFile?.name ?: "?"
        Mode.SONGS -> ""
    }

    private fun applyFilter() {
        rows = allRows.filter { r -> Search.matches(filterQuery, r.label, r.secondary) }
        adapter.notifyDataSetChanged()
    }

    private fun applySort(field: SortField) {
        val count = if (mode == Mode.SONGS) (svc?.library?.size ?: 0) else (groups[groupKey] ?: emptyList()).size
        if (count < 2) return
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

        styleTab(b.tabArtists, mode == Mode.ARTISTS)
        styleTab(b.tabAlbums, mode == Mode.ALBUMS)
        styleTab(b.tabSongs, mode == Mode.SONGS)
        styleTab(b.tabGenre, mode == Mode.GENRE)
        styleTab(b.tabFolder, mode == Mode.FOLDER)

        // SONGS: one flat, searchable, sortable list of the whole library —
        // no group level, so it short-circuits the artist/album grouping
        // entirely. This is the "search everything and play any track" view.
        if (mode == Mode.SONGS) {
            groups = linkedMapOf()
            val list = sortedTracks(lib.sortedBy { it.title.lowercase(Locale.ROOT) })
            allRows = list.map { Row(it.title, it.artist, fmt(it.durationMs), it) }
            b.crumb.text = "ALL SONGS (${list.size})"
            b.actionRow.visibility = View.VISIBLE
            b.browserHeader.root.visibility = View.VISIBLE
            updateSortHeader()
            applyFilter()
            return
        }

        groups = lib.groupBy { groupValueOf(it) }
            .toSortedMap(compareBy { it.lowercase(Locale.ROOT) })
            .mapValues { (_, v) -> v.sortedBy { it.title.lowercase(Locale.ROOT) } }
            .toMutableMap() as LinkedHashMap<String, List<PlayerService.Track>>

        // Selected group may have vanished after a rescan
        if (groupKey != null && groupKey !in groups) groupKey = null

        val key = groupKey
        if (key == null) {
            allRows = groups.map { (name, list) -> Row(name, "", "${list.size} trk ▸", null) }
            b.crumb.text = when (mode) {
                Mode.ARTISTS -> "ALL ARTISTS (${groups.size})"
                Mode.GENRE -> "ALL GENRES (${groups.size})"
                Mode.FOLDER -> "ALL FOLDERS (${groups.size})"
                else -> "ALL ALBUMS (${groups.size})"
            }
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
            val isLeaf = row.track != null
            val path = row.track?.file?.absolutePath
            val isSelected = selectMode && path != null && path in selected
            holder.itemView.setBackgroundColor(
                if (isSelected) skinColor(R.attr.skinPanelDeep) else Color.TRANSPARENT
            )

            if (selectMode && isLeaf) {
                // In select mode the handle column doubles as a checkbox.
                holder.handle.text = if (isSelected) "☑" else "☐"
                holder.handle.visibility = View.VISIBLE
                holder.handle.alpha = 1f
                holder.handle.setTextColor(skinColor(if (isSelected) R.attr.skinAccent else R.attr.skinText))
                holder.handle.setOnTouchListener(null)
            } else {
                // Normal mode: restore the drag glyph (a recycled row may carry
                // a checkbox). Drag-reorder is only meaningful inside an open
                // artist/album group where order persists per group — the flat
                // SONGS list and group lists show no handle.
                holder.handle.text = "⠿"
                holder.handle.setTextColor(skinColor(R.attr.skinBevelLight))
                val canDrag = isLeaf && mode != Mode.SONGS && !selectMode
                holder.handle.visibility = if (canDrag) View.VISIBLE else View.INVISIBLE
                holder.handle.alpha = if (filterQuery.isBlank()) 1f else 0.3f
                holder.handle.setOnTouchListener { _, event ->
                    if (canDrag && filterQuery.isBlank() && event.actionMasked == MotionEvent.ACTION_DOWN) {
                        itemTouchHelper.startDrag(holder)
                    }
                    false
                }
            }

            holder.itemView.setOnClickListener {
                val pos = holder.bindingAdapterPosition
                val r = rows.getOrNull(pos) ?: return@setOnClickListener
                val t = r.track
                if (selectMode) {
                    if (t != null) { toggleSelection(t); notifyItemChanged(pos) }
                    return@setOnClickListener
                }
                if (t == null) {
                    groupKey = r.label
                    clearSearch()
                    rebuild()
                } else {
                    val key = trackListKey() ?: return@setOnClickListener
                    svc?.setQueue(currentDisplayedTracks(), key, pos, autoplay = true)
                    finish()
                }
            }
            holder.itemView.setOnLongClickListener {
                if (!selectMode) {
                    val t = rows.getOrNull(holder.bindingAdapterPosition)?.track
                    if (t != null) showTrackMenu(holder.itemView, t)
                }
                true
            }
        }
    }

    /** Per-track long-press menu: play it here, edit/rename its tags, or queue it. */
    private fun showTrackMenu(anchor: View, track: PlayerService.Track) {
        popupMenu(anchor, listOf(
            "▶ PLAY" to {
                val disp = currentDisplayedTracks()
                val i = disp.indexOfFirst { it.file == track.file }.coerceAtLeast(0)
                svc?.setQueue(disp, trackListKey() ?: "ALL SONGS", i, autoplay = true)
                finish()
            },
            "⏭ PLAY NEXT" to {
                val added = svc?.enqueueNext(listOf(track)) ?: 0
                flash(if (added > 0) "PLAYS NEXT: ${track.title}" else "ALREADY IN QUEUE")
            },
            "✎ EDIT TAGS / RENAME" to {
                TagEditDialog.show(this, scope, track) {
                    svc?.loadTracks()   // renamed file → rescan; onTracksReloaded rebuilds
                    flash("TAGS UPDATED")
                }
            },
            "＋ QUEUE" to {
                val added = svc?.enqueue(listOf(track)) ?: 0
                flash(if (added > 0) "QUEUED: ${track.title}" else "ALREADY IN QUEUE")
            },
            "☑ SELECT (BATCH)" to { enterSelectMode(track) },
            "🗑 DELETE FILE" to { confirmDelete(track) },
        ))
    }

    /** Destructive: deletes the file from disk after a confirm, then rescans. */
    private fun confirmDelete(track: PlayerService.Track) {
        AlertDialog.Builder(this)
            .setTitle("Delete file")
            .setMessage("Delete \"${track.title}\" from this device? This can't be undone.")
            .setPositiveButton("Delete") { _, _ ->
                val ok = svc?.deleteTrackFile(track) ?: false
                flash(if (ok) "DELETED: ${track.title}" else "COULDN'T DELETE FILE")
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    /** Bevel-chrome dropdown menu anchored to [anchor]. */
    private fun popupMenu(anchor: View, items: List<Pair<String, () -> Unit>>) {
        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = getDrawable(R.drawable.bevel_raised)
            val p = (5 * density).toInt()
            setPadding(p, p, p, p)
            minimumWidth = (200 * density).toInt()
        }
        val popup = PopupWindow(panel, ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, true)
        popup.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        popup.elevation = 12f
        items.forEach { (label, action) ->
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
        popup.showAsDropDown(anchor, (12 * density).toInt(), -(anchor.height / 2))
    }

    /**
     * Batch find-and-replace across the selected tracks. Replacement is
     * literal (not regex), applied to TITLE and/or ARTIST, with an optional
     * genre set. Each track is retagged and renamed through the same pipeline
     * as a single edit, so filenames follow the new tags.
     */
    private fun batchRenameDialog() {
        val lib = svc?.library ?: emptyList()
        val targets = selected.mapNotNull { p -> lib.firstOrNull { it.file.absolutePath == p } }
        if (targets.isEmpty()) { flash("SELECT SOME TRACKS FIRST"); return }

        val dialog = Dialog(this)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = getDrawable(R.drawable.bevel_raised)
            val p = (10 * density).toInt()
            setPadding(p, p, p, p)
        }
        root.addView(TextView(this).apply {
            text = "◢ BATCH RENAME (${targets.size})"
            setTextColor(skinColor(R.attr.skinAccent))
            textSize = 12f
            typeface = Typeface.MONOSPACE
            setTypeface(typeface, Typeface.BOLD)
            letterSpacing = 0.15f
            setPadding(0, 0, 0, (6 * density).toInt())
        })
        root.addView(TextView(this).apply {
            text = "Find & replace text in the selected tracks' tags. The filename follows the new tags."
            setTextColor(skinColor(R.attr.skinText))
            textSize = 10f
            typeface = Typeface.MONOSPACE
            setPadding(0, 0, 0, (4 * density).toInt())
        })

        fun field(label: String, hint: String): EditText {
            root.addView(TextView(this).apply {
                text = label
                setTextColor(skinColor(R.attr.skinText))
                textSize = 10f
                typeface = Typeface.MONOSPACE
                letterSpacing = 0.1f
                setPadding(0, (8 * density).toInt(), 0, (3 * density).toInt())
            })
            val input = EditText(this).apply {
                this.hint = hint
                setTextColor(skinColor(R.attr.skinLcd))
                setHintTextColor(skinColor(R.attr.skinBevelLight))
                textSize = 13f
                typeface = Typeface.MONOSPACE
                background = getDrawable(R.drawable.lcd_sunken)
                setPadding((10 * density).toInt(), (9 * density).toInt(), (10 * density).toInt(), (9 * density).toInt())
            }
            root.addView(input)
            return input
        }

        val findInput = field("FIND", "text to find (blank = only set genre)")
        val replaceInput = field("REPLACE WITH", "replacement (blank = delete the text)")

        // "Apply to" TITLE / ARTIST toggle chips
        root.addView(TextView(this).apply {
            text = "APPLY TO"
            setTextColor(skinColor(R.attr.skinText))
            textSize = 10f
            typeface = Typeface.MONOSPACE
            letterSpacing = 0.1f
            setPadding(0, (8 * density).toInt(), 0, (3 * density).toInt())
        })
        fun toggleChip(caption: String, initial: Boolean): TextView {
            val chip = TextView(this).apply {
                gravity = Gravity.CENTER
                textSize = 11f
                typeface = Typeface.MONOSPACE
                background = getDrawable(R.drawable.wa_button)
                layoutParams = LinearLayout.LayoutParams(0, (36 * density).toInt(), 1f)
                    .apply { marginEnd = (4 * density).toInt() }
            }
            fun render() {
                chip.text = (if (chip.isSelected) "☑ " else "☐ ") + caption
                chip.setTextColor(skinColor(if (chip.isSelected) R.attr.skinAccent else R.attr.skinText))
            }
            chip.isSelected = initial
            render()
            chip.setOnClickListener { chip.isSelected = !chip.isSelected; render() }
            return chip
        }
        val titleChip = toggleChip("TITLE", true)
        val artistChip = toggleChip("ARTIST", false)
        root.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(titleChip)
            addView(artistChip)
        })

        val genreInput = field("SET GENRE (optional)", "blank = keep each track's genre")

        val btnRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, (10 * density).toInt(), 0, 0)
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
        btnRow.addView(btn("APPLY") {
            val find = findInput.text.toString()
            val replace = replaceInput.text.toString()
            val setGenre = genreInput.text.toString().trim().ifBlank { null }
            val doTitle = titleChip.isSelected
            val doArtist = artistChip.isSelected
            when {
                find.isBlank() && setGenre == null -> { flash("NOTHING TO CHANGE"); return@btn }
                find.isNotBlank() && !doTitle && !doArtist -> { flash("PICK TITLE OR ARTIST"); return@btn }
            }
            dialog.dismiss()
            scope.launch {
                val changed = ArrayList<String>()
                var n = 0
                for (t in targets) {
                    // A placeholder artist ("RIP DOWNLOADS"/"Unknown Artist") is
                    // treated as "no artist" — so title-only renames keep raw
                    // downloads as "Title.ext" instead of baking the placeholder
                    // into the filename.
                    val isPlaceholder = t.artist.isBlank() ||
                        t.artist == "Unknown Artist" || t.artist == "RIP DOWNLOADS"
                    val baseArtist = if (isPlaceholder) null else t.artist
                    val newTitle = if (doTitle && find.isNotBlank()) t.title.replace(find, replace) else t.title
                    val newArtist = if (doArtist && find.isNotBlank() && baseArtist != null)
                        baseArtist.replace(find, replace).ifBlank { null } else baseArtist
                    flash("RENAMING ${n + 1}/${targets.size}…")
                    val out = TagCleaner.retagAndRename(this@BrowserActivity, t.file, newArtist, newTitle, setGenre)
                    changed += t.file.absolutePath   // old path (now gone) so MediaStore drops it
                    changed += out.absolutePath
                    n++
                }
                MediaScannerConnection.scanFile(applicationContext, changed.toTypedArray(), null, null)
                exitSelectMode(rebuild = false)
                svc?.loadTracks()
                flash("RENAMED $n")
            }
        })
        btnRow.addView(btn("CANCEL") { dialog.dismiss() })
        root.addView(btnRow)

        dialog.setContentView(root)
        dialog.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        dialog.window?.setLayout((320 * density).toInt(), ViewGroup.LayoutParams.WRAP_CONTENT)
        dialog.show()
    }

    override fun onDestroy() {
        super.onDestroy()
        svc?.let { if (it.listener === serviceListener) it.listener = null }
        runCatching { unbindService(conn) }
        scope.cancel()
    }
}
