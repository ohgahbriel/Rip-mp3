package com.dgabesilva.ripmp3

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.dgabesilva.ripmp3.databinding.ActivityDownloadBinding

class DownloadActivity : AppCompatActivity(), DownloadEngine.Listener {

    private lateinit var b: ActivityDownloadBinding
    private lateinit var chips: List<TextView>
    private var format = "mp3"
    private var bitrate = "0" // best VBR

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

        b.downloadBtn.setOnClickListener { startDownload() }
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

    private fun startDownload() {
        val url = b.urlInput.text.toString().trim()
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

    override fun onDestroy() {
        super.onDestroy()
        DownloadEngine.removeListener(this)
    }
}
