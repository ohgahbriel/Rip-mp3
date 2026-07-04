package com.dgabesilva.ripmp3

import android.content.Intent
import android.media.MediaScannerConnection
import android.os.Bundle
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import com.dgabesilva.ripmp3.databinding.ActivityMainBinding
import com.yausername.ffmpeg.FFmpeg
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLRequest
import kotlinx.coroutines.*
import java.io.File

class MainActivity : AppCompatActivity() {

    private lateinit var b: ActivityMainBinding
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private var job: Job? = null
    private val processId = "rip-mp3"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivityMainBinding.inflate(layoutInflater)
        setContentView(b.root)

        // Init native yt-dlp + ffmpeg (once)
        scope.launch(Dispatchers.IO) {
            try {
                YoutubeDL.getInstance().init(this@MainActivity)
                FFmpeg.getInstance().init(this@MainActivity)
                // Keep yt-dlp fresh so YouTube changes don't break it
                runCatching { YoutubeDL.getInstance().updateYoutubeDL(this@MainActivity) }
                withContext(Dispatchers.Main) { setStatus("Ready.") }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) { setStatus("Init failed: ${e.message}", error = true) }
            }
        }

        // Handle "Share -> RIP MP3" from the YouTube app
        if (intent?.action == Intent.ACTION_SEND) {
            intent.getStringExtra(Intent.EXTRA_TEXT)?.let { b.urlInput.setText(it) }
        }

        b.downloadBtn.setOnClickListener { startDownload() }
        b.playerBtn.setOnClickListener { startActivity(Intent(this, PlayerActivity::class.java)) }
        b.cancelBtn.setOnClickListener {
            YoutubeDL.getInstance().destroyProcessById(processId)
            job?.cancel()
            setStatus("Cancelled.")
            toggleBusy(false)
        }
    }

    private fun startDownload() {
        val url = b.urlInput.text.toString().trim()
        if (!url.startsWith("http")) {
            setStatus("Paste a valid URL first.", error = true)
            return
        }

        val outDir = File(getExternalFilesDir(null), "MP3").apply { mkdirs() }
        val bitrate = when (b.qualityGroup.checkedRadioButtonId) {
            R.id.q320 -> "320K"
            R.id.q192 -> "192K"
            else -> "0" // best VBR
        }
        val isPlaylist = url.contains(Regex("[?&]list=")) || url.contains("/playlist")

        toggleBusy(true)
        setStatus(if (isPlaylist) "Playlist detected - starting..." else "Starting...")
        b.progressBar.progress = 0

        job = scope.launch(Dispatchers.IO) {
            try {
                val outTemplate = if (isPlaylist)
                    "${outDir.absolutePath}/%(playlist_title)s/%(playlist_index)02d - %(title)s.%(ext)s"
                else
                    "${outDir.absolutePath}/%(title)s.%(ext)s"

                val request = YoutubeDLRequest(url).apply {
                    addOption("-x")
                    addOption("--audio-format", "mp3")
                    addOption("--audio-quality", bitrate)
                    addOption(if (isPlaylist) "--yes-playlist" else "--no-playlist")
                    addOption("--ignore-errors")
                    addOption("--download-archive", File(filesDir, "mp3archive.txt").absolutePath)
                    addOption("--embed-thumbnail")
                    addOption("--add-metadata")
                    addOption("-o", outTemplate)
                }

                var item = 0
                var total = 0
                val itemRe = Regex("""Downloading item (\d+) of (\d+)""")

                YoutubeDL.getInstance().execute(request, processId) { progress, _, line ->
                    itemRe.find(line)?.let {
                        item = it.groupValues[1].toInt()
                        total = it.groupValues[2].toInt()
                    }
                    scope.launch(Dispatchers.Main) {
                        if (total > 0 && progress >= 0) {
                            // Overall playlist progress across all tracks
                            val overall = (((item - 1) + progress / 100f) / total * 100).toInt()
                            b.progressBar.progress = overall.coerceIn(0, 100)
                            setStatus("Track $item/$total - ${"%.0f".format(progress)}%")
                        } else if (progress >= 0) {
                            b.progressBar.progress = progress.toInt()
                            setStatus(if (progress >= 99f) "Converting to MP3..." else "Downloading... ${"%.0f".format(progress)}%")
                        }
                    }
                }

                // Make files visible to music player apps (including playlist subfolders)
                outDir.walkTopDown().filter { it.isFile }.forEach {
                    MediaScannerConnection.scanFile(this@MainActivity, arrayOf(it.absolutePath), null, null)
                }

                withContext(Dispatchers.Main) {
                    b.progressBar.progress = 100
                    setStatus(
                        if (isPlaylist) "Done! Playlist ($total tracks) saved to files/MP3"
                        else "Done! Saved to Android/data/com.dgabesilva.ripmp3/files/MP3"
                    )
                    toggleBusy(false)
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    setStatus("Error: ${e.message}", error = true)
                    toggleBusy(false)
                }
            }
        }
    }

    private fun setStatus(msg: String, error: Boolean = false) {
        b.statusText.text = msg
        b.statusText.setTextColor(getColor(if (error) R.color.err else R.color.dim))
    }

    private fun toggleBusy(busy: Boolean) {
        b.downloadBtn.isEnabled = !busy
        b.cancelBtn.visibility = if (busy) View.VISIBLE else View.GONE
        b.progressBar.visibility = if (busy || b.progressBar.progress == 100) View.VISIBLE else View.GONE
    }

    override fun onDestroy() {
        super.onDestroy()
        scope.cancel()
    }
}
