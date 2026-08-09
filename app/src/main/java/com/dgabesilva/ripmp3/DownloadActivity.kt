package com.dgabesilva.ripmp3

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.dgabesilva.ripmp3.databinding.ActivityDownloadBinding
import kotlinx.coroutines.*
import java.util.Locale

class DownloadActivity : AppCompatActivity(), DownloadEngine.Listener {

    private lateinit var b: ActivityDownloadBinding
    private lateinit var chips: List<TextView>
    private var format = "mp3"
    private var bitrate = "0" // best VBR
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private val results = mutableListOf<DownloadEngine.SearchResult>()
    private lateinit var resultsAdapter: ResultsAdapter

    override fun onCreate(savedInstanceState: Bundle?) {
        Skin.apply(this)
        super.onCreate(savedInstanceState)
        b = ActivityDownloadBinding.inflate(layoutInflater)
        setContentView(b.root)

        DownloadEngine.init(this)

        // Handle "Share -> RIP MP3" from the YouTube app
        if (intent?.action == Intent.ACTION_SEND) {
            intent.getStringExtra(Intent.EXTRA_TEXT)?.let { b.urlInput.setText(it) }
        }

        chips = listOf(b.chipBest, b.chip320, b.chip192, b.chipFlac)
        b.chipBest.setOnClickListener { pickChip(0, "mp3", "0") }
        b.chip320.setOnClickListener { pickChip(1, "mp3", "320K") }
        b.chip192.setOnClickListener { pickChip(2, "mp3", "192K") }
        b.chipFlac.setOnClickListener { pickChip(3, "flac", "0") }
        pickChip(0, "mp3", "0")

        resultsAdapter = ResultsAdapter()
        b.resultsList.layoutManager = LinearLayoutManager(this)
        b.resultsList.adapter = resultsAdapter

        b.searchBtn.setOnClickListener { performSearch() }
        b.searchInput.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEARCH) { performSearch(); true } else false
        }

        b.downloadBtn.setOnClickListener { startDownload(b.urlInput.text.toString().trim()) }
        b.cancelBtn.setOnClickListener { DownloadEngine.cancel() }
        b.backBtn.setOnClickListener {
            // Opened via Share? There's no player behind us — launch it.
            if (isTaskRoot) startActivity(Intent(this, PlayerActivity::class.java))
            finish()
        }

        DownloadEngine.addListener(this)
    }

    private fun pickChip(idx: Int, fmt: String, rate: String) {
        format = fmt
        bitrate = rate
        chips.forEachIndexed { i, chip ->
            chip.isSelected = i == idx
            chip.setTextColor(skinColor(if (i == idx) R.attr.skinLcd else R.attr.skinText))
        }
    }

    private fun performSearch() {
        val q = b.searchInput.text.toString().trim()
        if (q.isEmpty()) return
        (getSystemService(INPUT_METHOD_SERVICE) as? InputMethodManager)
            ?.hideSoftInputFromWindow(b.searchInput.windowToken, 0)
        onDownloadStatus(DownloadEngine.Status("Searching “$q”…", -1, running = false))
        results.clear()
        resultsAdapter.notifyDataSetChanged()
        scope.launch {
            val found = DownloadEngine.search(q)
            results.clear()
            results.addAll(found)
            resultsAdapter.notifyDataSetChanged()
            onDownloadStatus(
                DownloadEngine.Status(
                    if (found.isEmpty()) "No results (or yt-dlp still starting up)." else "${found.size} results — tap one to download.",
                    -1, running = false, error = found.isEmpty()
                )
            )
        }
    }

    private fun startDownload(url: String) {
        if (!url.startsWith("http")) {
            onDownloadStatus(DownloadEngine.Status("Paste a valid URL first.", -1, running = false, error = true))
            return
        }
        DownloadEngine.start(this, url, format, bitrate)
    }

    override fun onDownloadStatus(s: DownloadEngine.Status) {
        b.statusText.text = s.message
        b.statusText.setTextColor(if (s.error) getColor(R.color.err) else skinColor(R.attr.skinLcd))
        b.downloadBtn.isEnabled = !s.running
        b.downloadBtn.alpha = if (s.running) 0.45f else 1f
        b.cancelBtn.visibility = if (s.running) View.VISIBLE else View.GONE
        if (s.progress >= 0) {
            b.progressBar.visibility = View.VISIBLE
            b.progressBar.progress = s.progress
        } else if (!s.running) {
            b.progressBar.visibility = View.GONE
        }
    }

    private fun fmtDur(sec: Int): String =
        if (sec <= 0) "-:--" else String.format(Locale.US, "%d:%02d", sec / 60, sec % 60)

    private inner class ResultsAdapter : RecyclerView.Adapter<ResultsAdapter.VH>() {
        inner class VH(v: View) : RecyclerView.ViewHolder(v) {
            val handle: TextView = v.findViewById(R.id.dragHandle)
            val title: TextView = v.findViewById(R.id.trackTitle)
            val secondary: TextView = v.findViewById(R.id.trackSecondary)
            val dur: TextView = v.findViewById(R.id.trackDur)
        }

        override fun getItemCount() = results.size

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH =
            VH(layoutInflater.inflate(R.layout.playlist_item, parent, false)).also {
                it.handle.visibility = View.GONE
            }

        override fun onBindViewHolder(holder: VH, position: Int) {
            val r = results[position]
            holder.title.text = r.title
            holder.secondary.text = r.uploader
            holder.dur.text = fmtDur(r.durationSec)
            holder.itemView.setOnClickListener {
                b.urlInput.setText(r.url)
                startDownload(r.url)
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        DownloadEngine.removeListener(this)
        scope.cancel()
    }
}
