package com.dgabesilva.ripmp3

import android.content.Intent
import android.os.Bundle
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import com.dgabesilva.ripmp3.databinding.ActivityDownloadBinding

class DownloadActivity : AppCompatActivity(), DownloadEngine.Listener {

    private lateinit var b: ActivityDownloadBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivityDownloadBinding.inflate(layoutInflater)
        setContentView(b.root)

        DownloadEngine.init(this)

        // Handle "Share -> RIP MP3" from the YouTube app
        if (intent?.action == Intent.ACTION_SEND) {
            intent.getStringExtra(Intent.EXTRA_TEXT)?.let { b.urlInput.setText(it) }
        }

        b.downloadBtn.setOnClickListener { startDownload() }
        b.cancelBtn.setOnClickListener { DownloadEngine.cancel() }
        b.backBtn.setOnClickListener {
            // Opened via Share? There's no player behind us — launch it.
            if (isTaskRoot) startActivity(Intent(this, PlayerActivity::class.java))
            finish()
        }

        DownloadEngine.addListener(this)
    }

    private fun startDownload() {
        val url = b.urlInput.text.toString().trim()
        if (!url.startsWith("http")) {
            onDownloadStatus(DownloadEngine.Status("Paste a valid URL first.", -1, running = false, error = true))
            return
        }
        val checked = b.qualityGroup.checkedRadioButtonId
        val format = if (checked == R.id.qFlac) "flac" else "mp3"
        val bitrate = when (checked) {
            R.id.q320 -> "320K"
            R.id.q192 -> "192K"
            else -> "0" // best VBR
        }
        DownloadEngine.start(this, url, format, bitrate)
    }

    override fun onDownloadStatus(s: DownloadEngine.Status) {
        b.statusText.text = s.message
        b.statusText.setTextColor(getColor(if (s.error) R.color.err else R.color.dim))
        b.downloadBtn.isEnabled = !s.running
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
