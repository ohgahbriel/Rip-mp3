package com.dgabesilva.ripmp3

import android.Manifest
import android.annotation.SuppressLint
import android.app.Dialog
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.content.res.ColorStateList
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
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.PopupWindow
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
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

    // Lyrics: fetched once per track (kept in memory), plus the active-line
    // ticker that runs only while the lyrics dialog is open.
    private val lyricsCache = HashMap<String, Lyrics.Result>()
    private var lyricsUpdater: Runnable? = null

    // ---------- Search filter ----------
    // Filters the queue view only (title/artist substring match); the
    // underlying queue itself is untouched. Manual drag-reorder is disabled
    // while this is non-blank — dragging a row to a position among hidden
    // (filtered-out) tracks has no unambiguous meaning, so rather than guess
    // we just turn the handle off, same as most music apps do while search
    // is active.
    private var filterQuery: String = ""

    // ---------- Column sort ----------
    private enum class SortField { TITLE, ARTIST, TIME }
    private var sortField: SortField? = null
    private var sortAsc = true

    private val itemTouchHelper by lazy {
        ItemTouchHelper(object : ItemTouchHelper.SimpleCallback(
            ItemTouchHelper.UP or ItemTouchHelper.DOWN, 0
        ) {
            override fun onMove(
                rv: RecyclerView, vh: RecyclerView.ViewHolder, target: RecyclerView.ViewHolder
            ): Boolean {
                // While a search filter is active, adapter positions no
                // longer equal real queue indices (see PlaylistAdapter.list)
                // — reordering is disabled for the duration rather than
                // guessing what "move" means among hidden rows. The drag
                // handle's touch listener already refuses to start a drag
                // in this state; this is the defense-in-depth backstop.
                if (filterQuery.isNotBlank()) return false
                val from = vh.bindingAdapterPosition
                val to = target.bindingAdapterPosition
                if (from == RecyclerView.NO_POSITION || to == RecyclerView.NO_POSITION) return false
                svc?.moveTrack(from, to)
                // A manual drag supersedes whatever column sort was active
                sortField = null
                updateSortHeader()
                adapter.notifyItemMoved(from, to)
                return true
            }
            override fun onSwiped(vh: RecyclerView.ViewHolder, direction: Int) {}
            override fun isLongPressDragEnabled() = false // dragging only starts from the handle
        })
    }

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
            updatePlCount()
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
        b.playlistView.layoutManager = LinearLayoutManager(this)
        b.playlistView.adapter = adapter
        itemTouchHelper.attachToRecyclerView(b.playlistView)

        b.queueHeader.hdrTitle.setOnClickListener { applySort(SortField.TITLE) }
        b.queueHeader.hdrArtist.setOnClickListener { applySort(SortField.ARTIST) }
        b.queueHeader.hdrTime.setOnClickListener { applySort(SortField.TIME) }
        updateSortHeader()

        b.searchInput.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                filterQuery = s?.toString() ?: ""
                adapter.notifyDataSetChanged()
                updatePlCount()
            }
            override fun afterTextChanged(s: android.text.Editable?) {}
        })

        b.menuBtn.setOnClickListener { showMenu(it) }
        b.dlStrip.setOnClickListener { startActivity(Intent(this, DownloadActivity::class.java)) }

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
        updatePlCount()
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

    private fun applySort(field: SortField) {
        val s = svc ?: return
        if (s.tracks.size < 2) return
        sortAsc = if (sortField == field) !sortAsc else true
        sortField = field
        val cmp: Comparator<PlayerService.Track> = when (field) {
            SortField.TITLE -> compareBy { it.title.lowercase(Locale.ROOT) }
            SortField.ARTIST -> compareBy { it.artist.lowercase(Locale.ROOT) }
            SortField.TIME -> compareBy { it.durationMs }
        }
        s.sortTracks(if (sortAsc) cmp else cmp.reversed())
        updateSortHeader()
    }

    /** "12 trk", or "3/12 trk" while a search filter narrows the visible rows. */
    private fun updatePlCount() {
        val s = svc ?: return
        val total = s.tracks.size
        val shown = adapter.itemCount
        b.plCount.text = if (filterQuery.isBlank() || shown == total) "$total trk" else "$shown/$total trk"
    }

    /** Clears the search box (and its filter) — called wherever the queue itself changes wholesale. */
    private fun clearSearch() {
        if (b.searchInput.text.isNotEmpty()) b.searchInput.text.clear()
    }

    private fun updateSortHeader() {
        fun label(base: String, field: SortField) =
            if (sortField == field) "$base ${if (sortAsc) "▲" else "▼"}" else base
        b.queueHeader.hdrTitle.text = label("TITLE", SortField.TITLE)
        b.queueHeader.hdrArtist.text = label("ARTIST", SortField.ARTIST)
        b.queueHeader.hdrTime.text = label("TIME", SortField.TIME)
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
        item("＋ NEW PLAYLIST") { clearSearch(); svc?.newQueue(); flashMarquee("NEW EMPTY LIST — ADD FROM BROWSER") }
        item("💾 SAVE PLAYLIST") { savePlaylistDialog() }
        item("▤ LOAD PLAYLIST") { loadPlaylistDialog() }
        item("✕ DELETE PLAYLIST") { deletePlaylistDialog() }
        item("♫ WHOLE LIBRARY") { clearSearch(); svc?.resetToLibrary() }
        item("🔥 MOST PLAYED") { playSmart(svc?.buildMostPlayed(), "MOST PLAYED") }
        item("🕐 RECENTLY PLAYED") { playSmart(svc?.buildRecent(), "RECENTLY PLAYED") }
        item("✎ EDIT TAGS") { editTagsDialog() }
        sep()
        item("🎚 EQUALIZER") { equalizerDialog() }
        item("🐢 SPEED / PITCH") { speedDialog() }
        item("🔁 A-B LOOP") { loopDialog() }
        item("🎤 LYRICS") { lyricsDialog() }
        item("⏱ SLEEP TIMER") { sleepDialog() }
        sep()
        item("◨ SKINS") { skinsDialog() }
        item("⟳ RESCAN LIBRARY") { svc?.loadTracks(); flashMarquee("RESCANNING…") }
        item("🧹 CLEAN UP TITLES") {
            if (DownloadEngine.isDownloading) {
                flashMarquee("BUSY — WAIT FOR THE CURRENT DOWNLOAD/CLEANUP TO FINISH")
            } else {
                flashMarquee("CLEANING UP TITLES…")
                DownloadEngine.cleanupLibrary(this)
            }
        }
        item("🗂 ORGANIZE LIBRARY") { organizeDialog() }

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
            clearSearch()
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

    // ---------- Organize library ----------

    /** Pick a naming scheme + a scope (whole library or a playlist); tapping a scope opens the preview. */
    private fun organizeDialog() {
        val s = svc ?: return
        waDialog("ORGANIZE LIBRARY") { root, dialog ->
            var tpl = LibraryOrganizer.Template.ARTIST_TITLE
            root.addView(dialogLabel("NAMING SCHEME", accent = true))
            val chips = mutableListOf<TextView>()
            fun paintChips() = chips.forEachIndexed { i, c ->
                val on = LibraryOrganizer.Template.values()[i] == tpl
                c.isSelected = on
                c.setTextColor(skinColor(if (on) R.attr.skinLcd else R.attr.skinText))
            }
            val chipRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            LibraryOrganizer.Template.values().forEach { t ->
                val c = TextView(this).apply {
                    text = t.label
                    gravity = Gravity.CENTER
                    textSize = 11f
                    typeface = Typeface.MONOSPACE
                    background = getDrawable(R.drawable.wa_button)
                    layoutParams = LinearLayout.LayoutParams(0, (36 * density).toInt(), 1f)
                        .apply { marginEnd = (4 * density).toInt() }
                    setOnClickListener { tpl = t; paintChips() }
                }
                chips.add(c); chipRow.addView(c)
            }
            root.addView(chipRow)
            paintChips()

            root.addView(dialogLabel("SCOPE — TAP TO PREVIEW", accent = true))
            fun scopeItem(label: String, tracks: () -> List<PlayerService.Track>) {
                root.addView(TextView(this).apply {
                    text = label
                    setTextColor(skinColor(R.attr.skinLcd))
                    textSize = 13f
                    typeface = Typeface.MONOSPACE
                    background = getDrawable(R.drawable.lcd_sunken)
                    setPadding((10 * density).toInt(), (9 * density).toInt(), (10 * density).toInt(), (9 * density).toInt())
                    layoutParams = LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
                    ).apply { bottomMargin = (4 * density).toInt() }
                    setOnClickListener { dialog.dismiss(); previewOrganizeDialog(tracks(), tpl) }
                })
            }
            scopeItem("♫ WHOLE LIBRARY (${s.library.size})") { s.library.toList() }
            PlaylistStore.list(this).forEach { name ->
                scopeItem("▤ $name") { playlistTracks(name) }
            }
        }
    }

    private fun playlistTracks(name: String): List<PlayerService.Track> {
        val s = svc ?: return emptyList()
        val byPath = s.library.associateBy { it.file.absolutePath }
        return PlaylistStore.load(this, name)
            .map { f -> byPath[f.absolutePath] ?: PlayerService.Track(f, f.nameWithoutExtension) }
    }

    /** Dry-run: shows every proposed old → new rename before anything is touched. */
    private fun previewOrganizeDialog(tracks: List<PlayerService.Track>, tpl: LibraryOrganizer.Template) {
        val plans = LibraryOrganizer.buildPlan(this, tracks, tpl)
        val writable = plans.filter { it.writable }
        val skipped = plans.size - writable.size
        waDialog("PREVIEW") { root, dialog ->
            root.addView(dialogLabel(
                if (plans.isEmpty()) "ALREADY ORGANIZED — NOTHING TO RENAME"
                else "${writable.size} TO RENAME" + if (skipped > 0) " · $skipped SKIPPED (NOT APP FILES)" else "",
                accent = true
            ))
            if (writable.isNotEmpty()) {
                val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
                writable.take(300).forEach { p ->
                    box.addView(TextView(this).apply {
                        text = "${p.track.file.nameWithoutExtension}\n→ ${p.newBase}"
                        setTextColor(skinColor(R.attr.skinLcd))
                        textSize = 11f
                        typeface = Typeface.MONOSPACE
                        setPadding(0, (5 * density).toInt(), 0, (5 * density).toInt())
                    })
                }
                root.addView(ScrollView(this).apply {
                    layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, (300 * density).toInt())
                    background = getDrawable(R.drawable.lcd_sunken)
                    setPadding((8 * density).toInt(), (6 * density).toInt(), (8 * density).toInt(), (6 * density).toInt())
                    addView(box)
                })
            }
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                setPadding(0, (10 * density).toInt(), 0, 0)
            }
            if (writable.isNotEmpty()) {
                row.addView(dialogButton("APPLY (${writable.size})") {
                    dialog.dismiss()
                    flashMarquee("ORGANIZING…")
                    scope.launch {
                        val done = LibraryOrganizer.apply(this@PlayerActivity, plans) { d, t ->
                            if (t > 0 && d % 5 == 0) flashMarquee("ORGANIZING $d/$t…")
                        }
                        svc?.loadTracks()
                        flashMarquee("ORGANIZED $done FILE${if (done == 1) "" else "S"}")
                    }
                })
            }
            row.addView(dialogButton(if (writable.isEmpty()) "CLOSE" else "CANCEL") { dialog.dismiss() })
            root.addView(row)
        }
    }

    // ---------- Effects / speed / loop / sleep ----------

    private fun skinnedSeekBar(maxVal: Int, value: Int, onChange: (Int) -> Unit): SeekBar =
        SeekBar(this).apply {
            max = maxVal
            progress = value.coerceIn(0, maxVal)
            progressTintList = ColorStateList.valueOf(skinColor(R.attr.skinLcd))
            thumbTintList = ColorStateList.valueOf(skinColor(R.attr.skinAccent))
            progressBackgroundTintList = ColorStateList.valueOf(skinColor(R.attr.skinBevelLight))
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: SeekBar, p: Int, fromUser: Boolean) { if (fromUser) onChange(p) }
                override fun onStartTrackingTouch(sb: SeekBar) {}
                override fun onStopTrackingTouch(sb: SeekBar) {}
            })
        }

    private fun dialogLabel(text: String, accent: Boolean = false): TextView = TextView(this).apply {
        this.text = text
        setTextColor(skinColor(if (accent) R.attr.skinAccent else R.attr.skinText))
        textSize = 10f
        typeface = Typeface.MONOSPACE
        letterSpacing = 0.1f
        setPadding(0, (9 * density).toInt(), 0, (2 * density).toInt())
    }

    private fun dialogButton(label: String, action: () -> Unit): TextView = TextView(this).apply {
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

    private fun equalizerDialog() {
        val s = svc ?: return
        if (s.eqBandCount == 0) { flashMarquee("EQUALIZER NOT AVAILABLE ON THIS DEVICE"); return }
        waDialog("EQUALIZER") { root, dialog ->
            val toggle = TextView(this).apply {
                gravity = Gravity.CENTER
                textSize = 12f
                typeface = Typeface.MONOSPACE
                setTypeface(typeface, Typeface.BOLD)
                background = getDrawable(R.drawable.wa_button)
                setPadding(0, (9 * density).toInt(), 0, (9 * density).toInt())
            }
            fun paintToggle() {
                toggle.text = if (s.fxEnabled) "EQ: ON" else "EQ: OFF"
                toggle.setTextColor(skinColor(if (s.fxEnabled) R.attr.skinLcd else R.attr.skinText))
            }
            paintToggle()
            toggle.setOnClickListener { s.setFxEnabled(!s.fxEnabled); paintToggle() }
            root.addView(toggle)

            // Preset chips
            if (s.eqPresetNames.isNotEmpty()) {
                val prow = LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                    setPadding(0, (8 * density).toInt(), 0, 0)
                }
                s.eqPresetNames.forEachIndexed { i, name ->
                    prow.addView(TextView(this).apply {
                        text = name
                        textSize = 10f
                        typeface = Typeface.MONOSPACE
                        setTextColor(skinColor(R.attr.skinAccent))
                        background = getDrawable(R.drawable.wa_button)
                        setPadding((10 * density).toInt(), (6 * density).toInt(), (10 * density).toInt(), (6 * density).toInt())
                        layoutParams = LinearLayout.LayoutParams(
                            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
                        ).apply { marginEnd = (4 * density).toInt() }
                        setOnClickListener { s.applyEqPreset(i); dialog.dismiss(); equalizerDialog() }
                    })
                }
                root.addView(HorizontalScrollView(this).apply {
                    isHorizontalScrollBarEnabled = false
                    addView(prow)
                })
            }

            // Bands (scrollable so many-band devices don't push DONE off screen)
            val bandsBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
            val range = (s.eqMaxLevel - s.eqMinLevel).toInt()
            for (b in 0 until s.eqBandCount) {
                val freq = s.eqCenterFreq(b)
                bandsBox.addView(dialogLabel(if (freq >= 1000) "${freq / 1000} kHz" else "$freq Hz"))
                bandsBox.addView(skinnedSeekBar(range, s.eqBand(b) - s.eqMinLevel) { p ->
                    s.setEqBand(b, (p + s.eqMinLevel).toShort())
                })
            }
            bandsBox.addView(dialogLabel("BASS BOOST", accent = true))
            bandsBox.addView(skinnedSeekBar(1000, s.bassStrength) { p -> s.setBass(p) })
            root.addView(ScrollView(this).apply {
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, (260 * density).toInt()
                )
                addView(bandsBox)
            })

            root.addView(dialogButton("DONE") { dialog.dismiss() }.apply {
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, (38 * density).toInt()
                ).apply { topMargin = (10 * density).toInt() }
            })
        }
    }

    private fun speedDialog() {
        val s = svc ?: return
        waDialog("SPEED / PITCH") { root, dialog ->
            val steps = 1000
            fun norm(v: Float, min: Float, max: Float) = (((v - min) / (max - min)) * steps).toInt()
            fun value(p: Int, min: Float, max: Float) = min + (p.toFloat() / steps) * (max - min)

            fun slider(title: String, suffix: String, value: Float, min: Float, max: Float, onSet: (Float) -> Unit): SeekBar {
                val lab = dialogLabel("$title: ${String.format(Locale.US, "%.2f", value)}$suffix", accent = true)
                root.addView(lab)
                val bar = skinnedSeekBar(steps, norm(value, min, max)) { p ->
                    val v = value(p, min, max)
                    lab.text = "$title: ${String.format(Locale.US, "%.2f", v)}$suffix"
                    onSet(v)
                }
                root.addView(bar)
                return bar
            }
            val spBar = slider("SPEED", "x", s.speed, 0.5f, 2f) { s.setSpeed(it) }
            val piBar = slider("PITCH", "", s.pitch, 0.5f, 2f) { s.setPitch(it) }

            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                setPadding(0, (12 * density).toInt(), 0, 0)
            }
            row.addView(dialogButton("RESET") {
                s.setSpeed(1f); s.setPitch(1f)
                spBar.progress = norm(1f, 0.5f, 2f)
                piBar.progress = norm(1f, 0.5f, 2f)
            })
            row.addView(dialogButton("DONE") { dialog.dismiss() })
            root.addView(row)
        }
    }

    private fun loopDialog() {
        val s = svc ?: return
        waDialog("A-B LOOP") { root, dialog ->
            val status = TextView(this).apply {
                setTextColor(skinColor(R.attr.skinLcd))
                textSize = 13f
                typeface = Typeface.MONOSPACE
                gravity = Gravity.CENTER
                background = getDrawable(R.drawable.lcd_sunken)
                setPadding((10 * density).toInt(), (10 * density).toInt(), (10 * density).toInt(), (10 * density).toInt())
            }
            fun refresh() {
                val a = if (s.loopA >= 0) fmt(s.loopA) else "--:--"
                val bEnd = if (s.loopB > s.loopA && s.loopB >= 0) fmt(s.loopB) else "--:--"
                status.text = "A  $a   ↔   B  $bEnd"
            }
            refresh()
            root.addView(status)

            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                setPadding(0, (10 * density).toInt(), 0, 0)
            }
            row.addView(dialogButton("SET A") { s.setLoopA(); refresh(); flashMarquee("LOOP A SET") })
            row.addView(dialogButton("SET B") { s.setLoopB(); refresh(); flashMarquee(if (s.loopB > s.loopA) "LOOP ON" else "SET A FIRST") })
            row.addView(dialogButton("CLEAR") { s.clearLoop(); refresh() })
            root.addView(row)

            root.addView(dialogButton("DONE") { dialog.dismiss() }.apply {
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, (38 * density).toInt()
                ).apply { topMargin = (10 * density).toInt() }
            })
        }
    }

    private fun sleepDialog() {
        val opts = listOf("OFF", "15 MIN", "30 MIN", "45 MIN", "60 MIN")
        val mins = listOf(0, 15, 30, 45, 60)
        waListDialog("SLEEP TIMER", opts, "") { i ->
            svc?.setSleepTimer(mins[i])
            flashMarquee(if (mins[i] == 0) "SLEEP TIMER OFF" else "SLEEP IN ${mins[i]} MIN")
        }
    }

    private fun lyricsDialog() {
        val t = svc?.currentTrack ?: run { flashMarquee("NOTHING PLAYING"); return }
        waDialog("LYRICS") { root, dialog ->
            val info = TextView(this).apply {
                text = "SEARCHING LYRICS…"
                setTextColor(skinColor(R.attr.skinText))
                textSize = 10f
                typeface = Typeface.MONOSPACE
                letterSpacing = 0.1f
                setPadding(0, 0, 0, (6 * density).toInt())
            }
            root.addView(info)

            val container = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
            val scroll = ScrollView(this).apply {
                layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, (360 * density).toInt())
                background = getDrawable(R.drawable.lcd_sunken)
                setPadding((8 * density).toInt(), (6 * density).toInt(), (8 * density).toInt(), (6 * density).toInt())
                addView(container)
            }
            root.addView(scroll)
            root.addView(dialogButton("CLOSE") { dialog.dismiss() }.apply {
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, (38 * density).toInt()
                ).apply { topMargin = (10 * density).toInt() }
            })

            dialog.setOnDismissListener {
                lyricsUpdater?.let { ui.removeCallbacks(it) }
                lyricsUpdater = null
            }

            fun render(res: Lyrics.Result) {
                container.removeAllViews()
                val synced = res.synced
                when {
                    res.isEmpty -> info.text = "NO LYRICS FOUND"
                    !synced.isNullOrEmpty() -> {
                        info.text = "SYNCED — TAP A LINE TO JUMP"
                        val lineViews = synced.map { line ->
                            TextView(this).apply {
                                text = line.text.ifBlank { "♪" }
                                setTextColor(skinColor(R.attr.skinText))
                                textSize = 13f
                                typeface = Typeface.MONOSPACE
                                setPadding((4 * density).toInt(), (6 * density).toInt(), (4 * density).toInt(), (6 * density).toInt())
                                setOnClickListener { svc?.seekTo(line.timeMs) }
                            }.also { container.addView(it) }
                        }
                        val upd = object : Runnable {
                            override fun run() {
                                val pos = svc?.positionMs ?: 0
                                var active = -1
                                for (i in synced.indices) { if (synced[i].timeMs <= pos) active = i else break }
                                synced.indices.forEach { i ->
                                    val on = i == active
                                    lineViews[i].setTextColor(skinColor(if (on) R.attr.skinAccent else R.attr.skinText))
                                    lineViews[i].setTypeface(Typeface.MONOSPACE, if (on) Typeface.BOLD else Typeface.NORMAL)
                                }
                                if (active >= 0) scroll.smoothScrollTo(0, (lineViews[active].top - scroll.height / 2).coerceAtLeast(0))
                                ui.postDelayed(this, 350)
                            }
                        }
                        lyricsUpdater = upd
                        ui.post(upd)
                    }
                    else -> {
                        info.text = "PLAIN LYRICS"
                        container.addView(TextView(this).apply {
                            text = res.plain
                            setTextColor(skinColor(R.attr.skinLcd))
                            textSize = 13f
                            typeface = Typeface.MONOSPACE
                            setPadding((4 * density).toInt(), (4 * density).toInt(), (4 * density).toInt(), (4 * density).toInt())
                        })
                    }
                }
            }

            val cached = lyricsCache[t.file.absolutePath]
            if (cached != null) {
                render(cached)
            } else {
                // Placeholder-artist downloads carry "Artist - Title" in the title;
                // split it so LRCLIB gets a real artist to match on.
                val (qa, qt) = if (t.artist.isBlank() || t.artist == "Unknown Artist" || t.artist == "RIP DOWNLOADS")
                    TagCleaner.splitArtistTitle(t.title) else t.artist to t.title
                scope.launch {
                    val res = Lyrics.fetch(qa ?: "", qt, (svc?.durationMs ?: t.durationMs) / 1000)
                    lyricsCache[t.file.absolutePath] = res
                    if (dialog.isShowing) render(res)
                }
            }
        }
    }

    /** ≡ menu entry: edits the currently-playing track. */
    private fun editTagsDialog() {
        val t = svc?.currentTrack ?: run { flashMarquee("NOTHING PLAYING TO EDIT"); return }
        openTagEditor(t)
    }

    /**
     * Opens the shared edit/rename sheet for any track (playing or not) — the
     * manual fix for what the automated pipeline (DownloadEngine + TagCleaner)
     * gets wrong: an obscure track the iTunes lookup can't find, or a title it
     * split wrong. The sheet pre-fills the existing genre and pre-splits a raw
     * "Artist - Title" filename into the fields; see [TagEditDialog].
     */
    private fun openTagEditor(t: PlayerService.Track) {
        TagEditDialog.show(this, scope, t) {
            svc?.loadTracks()
            flashMarquee("TAGS UPDATED")
        }
    }

    /** Destructive: deletes the actual file from the device after a confirm. */
    private fun confirmDeleteFile(t: PlayerService.Track) {
        waDialog("DELETE FILE") { root, dialog ->
            root.addView(TextView(this).apply {
                text = "Delete \"${t.title}\" from this device?\nThis can't be undone."
                setTextColor(skinColor(R.attr.skinText))
                textSize = 12f
                typeface = Typeface.MONOSPACE
                setPadding(0, 0, 0, (12 * density).toInt())
            })
            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            row.addView(dialogButton("DELETE") {
                dialog.dismiss()
                val ok = svc?.deleteTrackFile(t) ?: false
                flashMarquee(if (ok) "DELETED: ${t.title}" else "COULDN'T DELETE FILE")
            })
            row.addView(dialogButton("CANCEL") { dialog.dismiss() })
            root.addView(row)
        }
    }

    /** Loads an auto-built queue (most-played / recent) into the player. */
    private fun playSmart(list: List<PlayerService.Track>?, name: String) {
        val q = list ?: emptyList()
        if (q.isEmpty()) { flashMarquee("NO PLAY HISTORY YET"); return }
        clearSearch()
        svc?.setQueue(q, name)
        flashMarquee("$name (${q.size} TRK)")
    }

    /** Bevel-chrome dropdown menu anchored to [anchor] — used for a row's long-press options. */
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

    private inner class PlaylistAdapter : RecyclerView.Adapter<PlaylistAdapter.VH>() {
        // Pairs each visible row with its REAL index in svc.tracks. When
        // unfiltered this is just 0..n-1 in order (adapter position ==
        // real index, same as before); while searching it's the matching
        // subset, so play/remove/numbering must resolve through .index
        // rather than trusting the adapter position directly — that's the
        // whole reason this is IndexedValue and not a plain List<Track>.
        private val list: List<IndexedValue<PlayerService.Track>>
            get() {
                val all = svc?.tracks ?: emptyList()
                val q = filterQuery.trim()
                if (q.isEmpty()) return all.withIndex().toList()
                return all.withIndex().filter { (_, t) -> Search.matches(q, t.title, t.artist) }
            }

        inner class VH(view: View) : RecyclerView.ViewHolder(view) {
            val handle: TextView = view.findViewById(R.id.dragHandle)
            val num: TextView = view.findViewById(R.id.trackNum)
            val title: TextView = view.findViewById(R.id.trackTitle)
            val artist: TextView = view.findViewById(R.id.trackArtist)
            val dur: TextView = view.findViewById(R.id.trackDur)
        }

        override fun getItemCount() = list.size

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            val v = layoutInflater.inflate(R.layout.queue_item, parent, false)
            return VH(v)
        }

        @SuppressLint("ClickableViewAccessibility")
        override fun onBindViewHolder(holder: VH, position: Int) {
            val (realIndex, t) = list[position]
            val flacTag = if (t.file.extension.equals("flac", true)) " [FLAC]" else ""
            holder.num.text = "${realIndex + 1}."
            holder.title.text = "${t.title}$flacTag"
            holder.artist.text = t.artist
            holder.dur.text = if (t.durationMs > 0) fmt(t.durationMs) else "-:--"
            val isCurrent = realIndex == (svc?.current ?: -1)
            val color = skinColor(if (isCurrent) R.attr.skinAccent else R.attr.skinLcd)
            holder.num.setTextColor(color)
            holder.title.setTextColor(color)
            holder.artist.setTextColor(color)
            holder.dur.setTextColor(color)
            holder.itemView.setBackgroundColor(if (isCurrent) skinColor(R.attr.skinPanelDeep) else Color.TRANSPARENT)

            holder.itemView.setOnClickListener {
                val pos = holder.bindingAdapterPosition
                if (pos != RecyclerView.NO_POSITION) svc?.play(list[pos].index)
            }
            holder.itemView.setOnLongClickListener {
                // Long-press opens a per-track menu: play it, edit/rename its
                // tags, or remove it from the queue (removal is queue-only,
                // never deletes the file from disk). Actions re-resolve the
                // row's real index by file at click time rather than trusting
                // the index captured now, so a queue change between opening
                // the menu and tapping can't act on the wrong track.
                val pos = holder.bindingAdapterPosition
                val s = svc
                if (s != null && pos != RecyclerView.NO_POSITION) {
                    val t = list[pos].value
                    popupMenu(holder.itemView, listOf(
                        "▶ PLAY" to {
                            val i = s.tracks.indexOfFirst { it.file == t.file }
                            if (i >= 0) s.play(i)
                        },
                        "⏭ PLAY NEXT" to {
                            s.enqueueNext(listOf(t)); flashMarquee("PLAYS NEXT: ${t.title}")
                        },
                        "✎ EDIT TAGS / RENAME" to { openTagEditor(t) },
                        "✕ REMOVE FROM QUEUE" to {
                            val i = s.tracks.indexOfFirst { it.file == t.file }
                            if (i >= 0) { s.removeFromQueue(i); flashMarquee("REMOVED: ${t.title}") }
                        },
                        "🗑 DELETE FILE" to { confirmDeleteFile(t) },
                    ))
                }
                true
            }
            // Dragging only makes unambiguous sense against the real,
            // unfiltered order — dimmed and inert while a search is active.
            holder.handle.alpha = if (filterQuery.isBlank()) 1f else 0.3f
            holder.handle.setOnTouchListener { _, event ->
                if (filterQuery.isBlank() && event.actionMasked == MotionEvent.ACTION_DOWN) {
                    itemTouchHelper.startDrag(holder)
                }
                false
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        ui.removeCallbacks(ticker)
        lyricsUpdater?.let { ui.removeCallbacks(it) }
        DownloadEngine.removeListener(this)
        svc?.let { if (it.listener === serviceListener) it.listener = null }
        runCatching { unbindService(conn) }
        scope.cancel()
    }
}
