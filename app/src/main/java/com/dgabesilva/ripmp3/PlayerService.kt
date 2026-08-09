package com.dgabesilva.ripmp3

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.provider.MediaStore
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.drawable.Icon
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaMetadata
import android.media.MediaMetadataRetriever
import android.media.MediaPlayer
import android.media.PlaybackParams
import android.media.audiofx.BassBoost
import android.media.audiofx.Equalizer
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.os.Binder
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import androidx.core.content.ContextCompat
import java.io.File
import java.util.Locale
import kotlin.concurrent.thread
import kotlin.random.Random

/**
 * Foreground playback service: keeps music going while the app is backgrounded
 * and puts prev / play-pause / next controls in the notification shade
 * (pull down the status bar to drive the player from anywhere).
 */
class PlayerService : Service() {

    data class Track(
        val file: File,
        val title: String,
        var durationMs: Int = 0,
        var artist: String = "Unknown Artist",
        var album: String = "Unknown Album",
        var genre: String = "Unknown Genre"
    )

    interface Listener {
        fun onTrackChanged(index: Int, track: Track?)
        fun onPlayState(playing: Boolean)
        fun onTracksReloaded()
    }

    inner class LocalBinder : Binder() { val service get() = this@PlayerService }
    private val binder = LocalBinder()
    override fun onBind(intent: Intent?): IBinder = binder

    val library = mutableListOf<Track>()   // every song found on the device
    val tracks = mutableListOf<Track>()    // active play queue (what the playlist editor shows)
    var queueName = "LIBRARY"; private set
    var current = -1; private set
    var shuffle = false
    var repeatAll = false
    var listener: Listener? = null
    var volume = 0.8f
        set(value) {
            field = value
            runCatching { mp?.setVolume(value * value, value * value) }
        }

    private var mp: MediaPlayer? = null
    private var prepared = false
    private var session: MediaSession? = null
    private val main = Handler(Looper.getMainLooper())

    // ---------- Audio focus / output routing ----------
    private val audioManager by lazy { getSystemService(AUDIO_SERVICE) as AudioManager }
    private var focusRequest: AudioFocusRequest? = null
    private var resumeOnFocusGain = false   // true only after a *transient* loss we should recover from
    private var ducked = false

    private val audioAttributes: AudioAttributes by lazy {
        AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_MEDIA)
            .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
            .build()
    }

    // Cached decode of the current file's embedded cover, so setMetadata (called
    // on every state change) doesn't re-read the file each time — only when the
    // track actually changes.
    private var artCache: Pair<String, Bitmap?>? = null

    // ---------- Resume + smart shuffle ----------
    // Restored-session resume point: only honored when play() is asked for
    // exactly [resumeIndex], so tapping a *different* track after a restore
    // starts it from 0 rather than inheriting the saved position.
    private var resumeIndex = -1
    private var resumePositionMs = 0
    private var sessionRestored = false
    // Shuffle as a bag of not-yet-played indices + a back-stack, instead of a
    // bare random pick that can replay a song two tracks later.
    private var shuffleBag = mutableListOf<Int>()
    private val shuffleHistory = ArrayDeque<Int>()

    // ---------- Audio effects / speed / sleep / A-B loop ----------
    private var eq: Equalizer? = null
    private var bass: BassBoost? = null
    // Device EQ capabilities, probed once (0 bands = EQ unsupported here).
    var eqBandCount = 0; private set
    var eqMinLevel: Short = -1500; private set
    var eqMaxLevel: Short = 1500; private set
    private val eqCenterFreqs = mutableListOf<Int>()   // Hz per band
    val eqPresetNames = mutableListOf<String>()
    // Persisted user FX state.
    var fxEnabled = false; private set
    private var eqLevels = ShortArray(0)               // millibel per band
    var bassStrength = 0; private set                  // 0..1000
    var speed = 1f; private set
    var pitch = 1f; private set
    // Sleep timer + A-B loop.
    private var sleepEndMs = 0L
    var loopA = -1; private set
    var loopB = -1; private set

    private val focusListener = AudioManager.OnAudioFocusChangeListener { change ->
        when (change) {
            // Someone took focus for good (another music app) — stop and don't
            // silently resume over them later.
            AudioManager.AUDIOFOCUS_LOSS -> { resumeOnFocusGain = false; pause() }
            // A phone call / transient sound — pause, but remember to pick back
            // up if we were playing.
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> { resumeOnFocusGain = isPlaying; pause() }
            // A nav prompt / notification — duck instead of pausing.
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> { ducked = true; applyDuck() }
            AudioManager.AUDIOFOCUS_GAIN -> {
                if (ducked) { ducked = false; applyDuck() }
                if (resumeOnFocusGain) { resumeOnFocusGain = false; resume() }
            }
        }
    }

    // Headphones unplugged / bluetooth disconnected: pause instead of blasting
    // the track out of the phone speaker.
    private val noisyReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context?, i: Intent?) {
            if (i?.action == AudioManager.ACTION_AUDIO_BECOMING_NOISY) pause()
        }
    }

    val isPlaying get() = prepared && runCatching { mp?.isPlaying == true }.getOrDefault(false)
    val positionMs get() = if (prepared) runCatching { mp?.currentPosition ?: 0 }.getOrDefault(0) else 0
    val durationMs get() = if (prepared) runCatching { mp?.duration ?: 0 }.getOrDefault(0) else 0
    val currentTrack get() = tracks.getOrNull(current)

    companion object {
        private const val CHANNEL = "playback"
        private const val NOTIF_ID = 7
        const val ACT_PREV = "com.dgabesilva.ripmp3.PREV"
        const val ACT_PLAYPAUSE = "com.dgabesilva.ripmp3.PLAYPAUSE"
        const val ACT_NEXT = "com.dgabesilva.ripmp3.NEXT"
        const val ACT_SHUTDOWN = "com.dgabesilva.ripmp3.SHUTDOWN"
    }

    override fun onCreate() {
        super.onCreate()
        if (Build.VERSION.SDK_INT >= 26) {
            val nm = getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL, "Playback", NotificationManager.IMPORTANCE_LOW)
            )
        }
        session = MediaSession(this, "ripmp3").apply {
            setCallback(object : MediaSession.Callback() {
                override fun onPlay() { resume() }
                override fun onPause() { pause() }
                override fun onSkipToNext() { step(+1) }
                override fun onSkipToPrevious() { step(-1) }
                override fun onSeekTo(pos: Long) { seekTo(pos.toInt()) }
                override fun onStop() { stopToZero() }
            })
            isActive = true
        }
        ContextCompat.registerReceiver(
            this, noisyReceiver,
            IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY),
            ContextCompat.RECEIVER_NOT_EXPORTED
        )
        probeFxCaps()
        loadFxState()
        loadTracks()
    }

    // ---------- Audio focus helpers ----------

    private fun applyDuck() {
        val v = if (ducked) volume * 0.3f else volume
        runCatching { mp?.setVolume(v * v, v * v) }
    }

    /** Asks the system for playback focus; true if granted. */
    private fun requestFocus(): Boolean {
        val res = if (Build.VERSION.SDK_INT >= 26) {
            val req = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
                .setAudioAttributes(audioAttributes)
                .setOnAudioFocusChangeListener(focusListener)
                .setWillPauseWhenDucked(false)
                .build()
            focusRequest = req
            audioManager.requestAudioFocus(req)
        } else {
            @Suppress("DEPRECATION")
            audioManager.requestAudioFocus(focusListener, AudioManager.STREAM_MUSIC, AudioManager.AUDIOFOCUS_GAIN)
        }
        return res == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
    }

    private fun abandonFocus() {
        if (Build.VERSION.SDK_INT >= 26) focusRequest?.let { audioManager.abandonAudioFocusRequest(it) }
        else @Suppress("DEPRECATION") audioManager.abandonAudioFocus(focusListener)
    }

    /** Embedded cover art for [t], decoded once and cached by path. Null when the file has none. */
    private fun albumArt(t: Track): Bitmap? {
        artCache?.let { if (it.first == t.file.absolutePath) return it.second }
        val bmp = runCatching {
            val mmr = MediaMetadataRetriever()
            try {
                mmr.setDataSource(t.file.absolutePath)
                mmr.embeddedPicture?.let { BitmapFactory.decodeByteArray(it, 0, it.size) }
            } finally {
                runCatching { mmr.release() }
            }
        }.getOrNull()
        artCache = t.file.absolutePath to bmp
        return bmp
    }

    // ---------- Audio effects (EQ + bass) ----------

    /** One-time probe of the device's EQ capabilities via a throwaway global instance. */
    private fun probeFxCaps() {
        runCatching {
            val e = Equalizer(0, 0)
            eqBandCount = e.numberOfBands.toInt()
            e.bandLevelRange.let { eqMinLevel = it[0]; eqMaxLevel = it[1] }
            eqCenterFreqs.clear()
            for (b in 0 until eqBandCount) eqCenterFreqs.add(e.getCenterFreq(b.toShort()) / 1000)
            eqPresetNames.clear()
            for (p in 0 until e.numberOfPresets.toInt()) eqPresetNames.add(e.getPresetName(p.toShort()))
            e.release()
        }
        if (eqLevels.size != eqBandCount) eqLevels = ShortArray(eqBandCount)
    }

    private fun attachFx(sessionId: Int) {
        releaseFx()
        if (!fxEnabled || eqBandCount == 0) return
        runCatching {
            eq = Equalizer(1, sessionId).apply {
                enabled = true
                for (b in 0 until eqBandCount) runCatching { setBandLevel(b.toShort(), eqLevels[b]) }
            }
        }
        runCatching {
            bass = BassBoost(1, sessionId).apply {
                enabled = true
                runCatching { setStrength(bassStrength.coerceIn(0, 1000).toShort()) }
            }
        }
    }

    private fun releaseFx() {
        runCatching { eq?.release() }; eq = null
        runCatching { bass?.release() }; bass = null
    }

    fun eqCenterFreq(band: Int): Int = eqCenterFreqs.getOrElse(band) { 0 }
    fun eqBand(band: Int): Short = eqLevels.getOrElse(band) { 0 }

    fun setFxEnabled(on: Boolean) {
        fxEnabled = on
        saveFx()
        if (on) mp?.audioSessionId?.let { attachFx(it) } else releaseFx()
    }

    fun setEqBand(band: Int, level: Short) {
        if (band !in 0 until eqBandCount) return
        eqLevels[band] = level.coerceIn(eqMinLevel, eqMaxLevel)
        runCatching { eq?.setBandLevel(band.toShort(), eqLevels[band]) }
        saveFx()
    }

    /** Applies a built-in preset (reading its band levels via a throwaway instance so it works even when idle). */
    fun applyEqPreset(preset: Int) {
        runCatching {
            val probe = Equalizer(0, 0)
            probe.usePreset(preset.toShort())
            for (b in 0 until eqBandCount) eqLevels[b] = probe.getBandLevel(b.toShort())
            probe.release()
        }
        runCatching { eq?.let { for (b in 0 until eqBandCount) it.setBandLevel(b.toShort(), eqLevels[b]) } }
        saveFx()
    }

    fun setBass(strength: Int) {
        bassStrength = strength.coerceIn(0, 1000)
        runCatching { bass?.setStrength(bassStrength.toShort()) }
        saveFx()
    }

    // ---------- Speed / pitch ----------

    private fun applyPlaybackParams(p: MediaPlayer) {
        if (speed == 1f && pitch == 1f) return
        runCatching { p.playbackParams = p.playbackParams.setSpeed(speed).setPitch(pitch) }
    }

    private fun applyLiveParams() {
        val p = mp ?: return
        if (!prepared) return
        runCatching {
            val wasPlaying = p.isPlaying
            p.playbackParams = p.playbackParams.setSpeed(speed).setPitch(pitch)
            // Setting params can (re)start the player; keep the paused state intact.
            if (!wasPlaying && p.isPlaying) p.pause()
        }
    }

    fun setSpeed(v: Float) { speed = v.coerceIn(0.25f, 3f); applyLiveParams(); saveFx() }
    fun setPitch(v: Float) { pitch = v.coerceIn(0.5f, 2f); applyLiveParams(); saveFx() }

    private fun saveFx() {
        runCatching {
            getSharedPreferences("player_fx", MODE_PRIVATE).edit()
                .putBoolean("enabled", fxEnabled)
                .putInt("bass", bassStrength)
                .putString("bands", eqLevels.joinToString(","))
                .putFloat("speed", speed)
                .putFloat("pitch", pitch)
                .apply()
        }
    }

    private fun loadFxState() {
        val p = getSharedPreferences("player_fx", MODE_PRIVATE)
        fxEnabled = p.getBoolean("enabled", false)
        bassStrength = p.getInt("bass", 0)
        speed = p.getFloat("speed", 1f)
        pitch = p.getFloat("pitch", 1f)
        p.getString("bands", null)?.split(",")?.mapNotNull { it.toShortOrNull() }?.let {
            if (it.size == eqBandCount) eqLevels = it.toShortArray()
        }
    }

    // ---------- Sleep timer ----------

    val sleepRemainingMs: Long get() = if (sleepEndMs > 0) (sleepEndMs - System.currentTimeMillis()).coerceAtLeast(0) else 0

    private val sleepRunnable = Runnable { sleepEndMs = 0; pause() }

    /** [minutes] <= 0 cancels. Pauses playback when it fires. */
    fun setSleepTimer(minutes: Int) {
        main.removeCallbacks(sleepRunnable)
        if (minutes <= 0) { sleepEndMs = 0; return }
        val ms = minutes * 60_000L
        sleepEndMs = System.currentTimeMillis() + ms
        main.postDelayed(sleepRunnable, ms)
    }

    // ---------- A-B loop ----------

    private val loopChecker = object : Runnable {
        override fun run() {
            if (loopA in 0 until loopB && isPlaying && positionMs >= loopB) seekTo(loopA)
            if (loopA >= 0 && loopB > loopA) main.postDelayed(this, 150)
        }
    }

    fun setLoopA() {
        loopA = positionMs
        if (loopB in 0..loopA) loopB = -1
        startLoopChecker()
    }

    fun setLoopB() {
        if (loopA >= 0 && positionMs > loopA) { loopB = positionMs; startLoopChecker() }
    }

    fun clearLoop() {
        loopA = -1; loopB = -1
        main.removeCallbacks(loopChecker)
    }

    private fun startLoopChecker() {
        main.removeCallbacks(loopChecker)
        if (loopA >= 0 && loopB > loopA) main.post(loopChecker)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACT_PREV -> step(-1)
            ACT_PLAYPAUSE -> if (isPlaying) pause() else resume()
            ACT_NEXT -> step(+1)
            ACT_SHUTDOWN -> if (!isPlaying) stopSelf()
        }
        return START_NOT_STICKY
    }

    // ---------- Library ----------

    // ---------- Queue management ----------

    fun setQueue(list: List<Track>, name: String, startIndex: Int = -1, autoplay: Boolean = false) {
        val playingFile = currentTrack?.file
        tracks.clear()
        tracks.addAll(list)
        queueName = name
        current = if (playingFile != null) tracks.indexOfFirst { it.file == playingFile } else -1
        clearShuffleState()
        listener?.onTracksReloaded()
        if (autoplay && tracks.isNotEmpty()) play(if (startIndex >= 0) startIndex else 0)
        saveState()
    }

    /** Appends without duplicates. Returns how many were actually added. */
    fun enqueue(list: List<Track>): Int {
        val have = tracks.mapTo(HashSet()) { it.file.absolutePath }
        val add = list.filter { it.file.absolutePath !in have }
        tracks.addAll(add)
        clearShuffleState()
        listener?.onTracksReloaded()
        saveState()
        return add.size
    }

    /** Drag-and-drop reorder: moves the track at [from] to [to], keeping playback position stable. */
    fun moveTrack(from: Int, to: Int) {
        if (from == to || from !in tracks.indices || to !in tracks.indices) return
        val playingFile = currentTrack?.file
        val t = tracks.removeAt(from)
        tracks.add(to, t)
        current = if (playingFile != null) tracks.indexOfFirst { it.file == playingFile } else current
        clearShuffleState()
        listener?.onTracksReloaded()
        saveState()
    }

    /** Column-header sort: reorders the queue in place, keeping playback position stable. */
    fun sortTracks(comparator: Comparator<Track>) {
        val playingFile = currentTrack?.file
        tracks.sortWith(comparator)
        current = if (playingFile != null) tracks.indexOfFirst { it.file == playingFile } else current
        clearShuffleState()
        listener?.onTracksReloaded()
        saveState()
    }

    fun newQueue() = setQueue(emptyList(), "NEW LIST")

    fun resetToLibrary() = setQueue(library.toList(), "LIBRARY")

    fun renameQueue(name: String) {
        queueName = name
        listener?.onTracksReloaded()
        saveState()
    }

    // ---------- Session persistence (resume where you left off) ----------

    private fun statePrefs() = getSharedPreferences("player_state", MODE_PRIVATE)

    /** Snapshots the queue + current position so the next launch can resume it. Cheap; called at every settling point. */
    private fun saveState() {
        runCatching {
            statePrefs().edit()
                .putString("q_name", queueName)
                .putString("q_paths", tracks.joinToString("\n") { it.file.absolutePath })
                .putInt("q_index", current)
                .putInt("q_pos", positionMs)
                .putBoolean("shuffle", shuffle)
                .putBoolean("repeat", repeatAll)
                .apply()
        }
    }

    /**
     * Rebuilds the last session's queue from saved file paths (skipping any that
     * no longer exist) and restores index / shuffle / repeat, leaving
     * [resumeIndex]/[resumePositionMs] set so the saved position is honored on the next play.
     * Does NOT auto-play — just makes the app reopen exactly where it was.
     */
    private fun restoreSavedSession(lib: List<Track>): Boolean {
        val p = statePrefs()
        val paths = p.getString("q_paths", null)?.split("\n")?.filter { it.isNotEmpty() } ?: return false
        if (paths.isEmpty()) return false
        val byPath = lib.associateBy { it.file.absolutePath }
        val restored = paths.mapNotNull { path ->
            byPath[path] ?: File(path).takeIf { it.exists() }?.let { f -> Track(f, f.nameWithoutExtension) }
        }
        if (restored.isEmpty()) return false
        tracks.clear(); tracks.addAll(restored)
        queueName = p.getString("q_name", "LIBRARY") ?: "LIBRARY"
        shuffle = p.getBoolean("shuffle", false)
        repeatAll = p.getBoolean("repeat", false)
        val idx = p.getInt("q_index", -1)
        current = if (idx in tracks.indices) idx else -1
        resumeIndex = current
        resumePositionMs = p.getInt("q_pos", 0).coerceAtLeast(0)
        clearShuffleState()
        currentTrack?.let { listener?.onTrackChanged(current, it) }
        return true
    }

    fun hasAudioPermission(): Boolean =
        if (Build.VERSION.SDK_INT >= 33)
            checkSelfPermission(Manifest.permission.READ_MEDIA_AUDIO) == PackageManager.PERMISSION_GRANTED
        else
            checkSelfPermission(Manifest.permission.READ_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED

    fun loadTracks() {
        thread {
            val found = LinkedHashMap<String, Track>() // path -> track, keeps order + dedupes

            // 1) Our own downloads (files/MP3, including playlist subfolders)
            val dir = File(getExternalFilesDir(null), "MP3")
            dir.walkTopDown()
                .filter {
                    it.isFile && (it.extension.equals("mp3", true) || it.extension.equals("flac", true))
                }
                .sortedBy { it.absolutePath.lowercase(Locale.ROOT) }
                .forEach {
                    val album = it.parentFile?.name?.takeIf { n -> n != "MP3" } ?: "Downloads"
                    found[it.absolutePath] =
                        Track(it, it.nameWithoutExtension, artist = "RIP DOWNLOADS", album = album)
                }

            // 2) Every other song on the phone via the media library
            if (hasAudioPermission()) runCatching {
                // GENRE as a Media column only exists on API 30+; on older
                // devices we leave it out of the query and let the background
                // tag-read pass below fill genre from each file instead.
                val hasGenreCol = Build.VERSION.SDK_INT >= 30
                val proj = buildList {
                    add(MediaStore.Audio.Media.DATA)
                    add(MediaStore.Audio.Media.TITLE)
                    add(MediaStore.Audio.Media.DURATION)
                    add(MediaStore.Audio.Media.ARTIST)
                    add(MediaStore.Audio.Media.ALBUM)
                    if (hasGenreCol) add(MediaStore.Audio.Media.GENRE)
                }.toTypedArray()
                contentResolver.query(
                    MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, proj,
                    "${MediaStore.Audio.Media.IS_MUSIC} != 0", null,
                    "${MediaStore.Audio.Media.TITLE} COLLATE NOCASE ASC"
                )?.use { c ->
                    val iData = c.getColumnIndexOrThrow(MediaStore.Audio.Media.DATA)
                    val iTitle = c.getColumnIndexOrThrow(MediaStore.Audio.Media.TITLE)
                    val iDur = c.getColumnIndexOrThrow(MediaStore.Audio.Media.DURATION)
                    val iArtist = c.getColumnIndexOrThrow(MediaStore.Audio.Media.ARTIST)
                    val iAlbum = c.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM)
                    val iGenre = if (hasGenreCol) c.getColumnIndex(MediaStore.Audio.Media.GENRE) else -1
                    while (c.moveToNext()) {
                        val path = c.getString(iData) ?: continue
                        val artist = c.getString(iArtist)
                            ?.takeIf { it.isNotBlank() && it != "<unknown>" } ?: "Unknown Artist"
                        val album = c.getString(iAlbum)
                            ?.takeIf { it.isNotBlank() && it != "<unknown>" } ?: "Unknown Album"
                        val genre = (if (iGenre >= 0) c.getString(iGenre) else null)
                            ?.takeIf { it.isNotBlank() && it != "<unknown>" } ?: "Unknown Genre"
                        val existing = found[path]
                        if (existing != null) {
                            // Our download, already indexed — take the real tags
                            if (c.getInt(iDur) > 0) existing.durationMs = c.getInt(iDur)
                            if (artist != "Unknown Artist") existing.artist = artist
                            if (album != "Unknown Album") existing.album = album
                            if (genre != "Unknown Genre") existing.genre = genre
                            continue
                        }
                        val f = File(path)
                        if (!f.exists()) continue
                        found[path] = Track(
                            f, c.getString(iTitle) ?: f.nameWithoutExtension,
                            c.getInt(iDur), artist, album, genre
                        )
                    }
                }
            }

            val list = found.values.toList()
            main.post {
                library.clear()
                library.addAll(list)
                // First scan after launch: try to reopen the last session. If
                // there's nothing saved (or none of it survives), fall through
                // to the default whole-library queue.
                val didRestore = if (!sessionRestored) {
                    sessionRestored = true
                    restoreSavedSession(list)
                } else false
                if (!didRestore && queueName == "LIBRARY") {
                    val playingFile = currentTrack?.file
                    tracks.clear()
                    tracks.addAll(list)
                    current = if (playingFile != null) tracks.indexOfFirst { it.file == playingFile } else -1
                }
                listener?.onTracksReloaded()
            }

            // Fill anything MediaStore didn't give us straight from the file's
            // own tags: duration always (some rows report 0), and genre on the
            // devices/files the GENRE column above couldn't cover (API < 30, or
            // a file MediaStore never tagged). Both come from a single
            // retriever open per track, and only when actually missing, so this
            // stays a one-time cost that skips already-complete tracks.
            list.forEachIndexed { i, t ->
                val needDur = t.durationMs == 0
                val needGenre = t.genre == "Unknown Genre"
                if (needDur || needGenre) readFileTags(t, needDur, needGenre)
                if (i % 8 == 7) main.post { listener?.onTracksReloaded() }
            }
            main.post { listener?.onTracksReloaded() }
        }
    }

    private fun readFileTags(t: Track, wantDuration: Boolean, wantGenre: Boolean) {
        val mmr = MediaMetadataRetriever()
        try {
            mmr.setDataSource(t.file.absolutePath)
            if (wantDuration) {
                mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                    ?.toIntOrNull()?.let { t.durationMs = it }
            }
            if (wantGenre) {
                mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_GENRE)
                    ?.takeIf { it.isNotBlank() }?.let { t.genre = it }
            }
        } catch (e: Exception) {
            // leave defaults on any unreadable file
        } finally {
            runCatching { mmr.release() }
        }
    }

    // ---------- Transport ----------

    fun play(index: Int) {
        if (tracks.isEmpty()) return
        val idx = index.coerceIn(0, tracks.size - 1)
        // Resume point applies only to the exact track we saved it for.
        val seekOnPrepare = if (idx == resumeIndex) resumePositionMs else 0
        resumeIndex = -1; resumePositionMs = 0
        clearLoop()   // A-B loop is per-track; a new track starts fresh
        releasePlayer()
        current = idx
        val t = tracks[idx]
        listener?.onTrackChanged(idx, t)

        val p = MediaPlayer()
        mp = p
        attachFx(p.audioSessionId)
        // Keep the CPU alive while playing with the screen off, and tag the
        // stream as media so the system routes/ducks it correctly.
        p.setWakeMode(applicationContext, PowerManager.PARTIAL_WAKE_LOCK)
        runCatching { p.setAudioAttributes(audioAttributes) }
        p.setOnPreparedListener {
            prepared = true
            if (t.durationMs == 0) t.durationMs = it.duration
            ducked = false
            it.setVolume(volume * volume, volume * volume)
            requestFocus()
            it.start()
            applyPlaybackParams(it)
            if (seekOnPrepare > 0) runCatching { it.seekTo(seekOnPrepare) }
            // Promote to a started foreground service so playback + the
            // shade controls survive the activity going away
            startService(Intent(this, PlayerService::class.java))
            updateSession(PlaybackState.STATE_PLAYING)
            goForeground()
            listener?.onPlayState(true)
            saveState()
        }
        p.setOnCompletionListener { onTrackEnd() }
        p.setOnErrorListener { _, _, _ ->
            prepared = false
            listener?.onPlayState(false)
            true
        }
        try {
            p.setDataSource(t.file.absolutePath)
            p.prepareAsync()
        } catch (e: Exception) {
            listener?.onPlayState(false)
        }
    }

    fun resume() {
        val p = mp
        if (p == null || !prepared) {
            if (current >= 0) play(current) else if (tracks.isNotEmpty()) play(0)
            return
        }
        if (!p.isPlaying) {
            ducked = false
            requestFocus()
            runCatching { p.start() }
            updateSession(PlaybackState.STATE_PLAYING)
            goForeground()
            listener?.onPlayState(true)
        }
    }

    fun pause() {
        val p = mp ?: return
        if (prepared && runCatching { p.isPlaying }.getOrDefault(false)) {
            runCatching { p.pause() }
            updateSession(PlaybackState.STATE_PAUSED)
            notifyPaused()
            listener?.onPlayState(false)
            saveState()
        }
    }

    fun stopToZero() {
        val p = mp ?: return
        if (prepared) runCatching {
            p.pause()
            p.seekTo(0)
        }
        updateSession(PlaybackState.STATE_STOPPED)
        notifyPaused()
        listener?.onPlayState(false)
        saveState()
    }

    fun step(dir: Int) {
        if (tracks.isEmpty()) return
        val next = nextIndex(dir) ?: return
        play(next)
    }

    fun seekTo(ms: Int) {
        if (prepared) runCatching { mp?.seekTo(ms) }
        updateSession(if (isPlaying) PlaybackState.STATE_PLAYING else PlaybackState.STATE_PAUSED)
        saveState()
    }

    private fun onTrackEnd() {
        val next = nextIndex(+1)
        if (next != null) play(next)
        else {
            updateSession(PlaybackState.STATE_STOPPED)
            notifyPaused()
            listener?.onPlayState(false)
        }
    }

    private fun nextIndex(dir: Int): Int? {
        if (tracks.isEmpty()) return null
        if (shuffle && tracks.size > 1) {
            if (dir < 0) {
                // Prev walks back through the actual play history, not a fresh random.
                val prev = shuffleHistory.removeLastOrNull()
                return prev?.takeIf { it in tracks.indices } ?: current
            }
            if (current in tracks.indices) {
                shuffleHistory.addLast(current)
                while (shuffleHistory.size > tracks.size) shuffleHistory.removeFirst()
            }
            if (shuffleBag.isEmpty()) {
                // New cycle: every other track once, in a fresh random order.
                shuffleBag = tracks.indices.filter { it != current }.shuffled(Random).toMutableList()
                if (shuffleBag.isEmpty()) shuffleBag.add(current)
            }
            return shuffleBag.removeAt(0)
        }
        val n = current + dir
        return when {
            n in tracks.indices -> n
            repeatAll -> (n + tracks.size) % tracks.size
            else -> null
        }
    }

    /** Queue changed under us — drop stale shuffle indices so the next pick refills cleanly. */
    private fun clearShuffleState() {
        shuffleBag.clear()
        shuffleHistory.clear()
    }

    private fun releasePlayer() {
        prepared = false
        releaseFx()
        mp?.let { p -> runCatching { p.stop() }; runCatching { p.release() } }
        mp = null
    }

    // ---------- Session + notification ----------

    private fun updateSession(state: Int) {
        session?.setPlaybackState(
            PlaybackState.Builder()
                .setActions(
                    PlaybackState.ACTION_PLAY or PlaybackState.ACTION_PAUSE or
                    PlaybackState.ACTION_PLAY_PAUSE or PlaybackState.ACTION_SKIP_TO_NEXT or
                    PlaybackState.ACTION_SKIP_TO_PREVIOUS or PlaybackState.ACTION_SEEK_TO or
                    PlaybackState.ACTION_STOP
                )
                .setState(state, positionMs.toLong(), if (state == PlaybackState.STATE_PLAYING) 1f else 0f)
                .build()
        )
        currentTrack?.let {
            // Show the real artist on the lock screen / Bluetooth / Android Auto;
            // fall back to the app name only for our own untagged downloads.
            val artist = it.artist.takeUnless { a ->
                a.isBlank() || a == "Unknown Artist" || a == "RIP DOWNLOADS"
            } ?: "RIP // MP3"
            val meta = MediaMetadata.Builder()
                .putString(MediaMetadata.METADATA_KEY_TITLE, it.title)
                .putString(MediaMetadata.METADATA_KEY_ARTIST, artist)
                .putString(MediaMetadata.METADATA_KEY_ALBUM, it.album)
                .putLong(MediaMetadata.METADATA_KEY_DURATION, durationMs.toLong())
            albumArt(it)?.let { art -> meta.putBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART, art) }
            session?.setMetadata(meta.build())
        }
    }

    private fun buildNotification(): Notification {
        val playing = isPlaying

        fun act(action: String, icon: Int, title: String): Notification.Action =
            Notification.Action.Builder(
                Icon.createWithResource(this, icon), title,
                PendingIntent.getService(
                    this, action.hashCode(),
                    Intent(this, PlayerService::class.java).setAction(action),
                    PendingIntent.FLAG_IMMUTABLE
                )
            ).build()

        val content = PendingIntent.getActivity(
            this, 0, Intent(this, PlayerActivity::class.java), PendingIntent.FLAG_IMMUTABLE
        )
        val builder = if (Build.VERSION.SDK_INT >= 26)
            Notification.Builder(this, CHANNEL)
        else @Suppress("DEPRECATION") Notification.Builder(this)

        return builder
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentTitle(currentTrack?.title ?: "RIP // MP3")
            .setContentText(if (playing) "Playing" else "Paused")
            .setContentIntent(content)
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .setOnlyAlertOnce(true)
            .addAction(act(ACT_PREV, android.R.drawable.ic_media_previous, "Previous"))
            .addAction(act(
                ACT_PLAYPAUSE,
                if (playing) android.R.drawable.ic_media_pause else android.R.drawable.ic_media_play,
                if (playing) "Pause" else "Play"
            ))
            .addAction(act(ACT_NEXT, android.R.drawable.ic_media_next, "Next"))
            .setStyle(
                Notification.MediaStyle()
                    .setMediaSession(session?.sessionToken)
                    .setShowActionsInCompactView(0, 1, 2)
            )
            .setDeleteIntent(
                PendingIntent.getService(
                    this, 99,
                    Intent(this, PlayerService::class.java).setAction(ACT_SHUTDOWN),
                    PendingIntent.FLAG_IMMUTABLE
                )
            )
            .build()
    }

    private fun goForeground() {
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(NOTIF_ID, buildNotification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
        } else {
            startForeground(NOTIF_ID, buildNotification())
        }
    }

    private fun notifyPaused() {
        // Keep the notification (so shade controls stay) but let it be dismissed
        getSystemService(NotificationManager::class.java).notify(NOTIF_ID, buildNotification())
        stopForeground(STOP_FOREGROUND_DETACH)
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        super.onTaskRemoved(rootIntent)
        saveState()
        if (!isPlaying) stopSelf()
    }

    override fun onDestroy() {
        super.onDestroy()
        saveState()
        main.removeCallbacks(sleepRunnable)
        main.removeCallbacks(loopChecker)
        stopForeground(STOP_FOREGROUND_REMOVE)
        releasePlayer()
        runCatching { unregisterReceiver(noisyReceiver) }
        abandonFocus()
        session?.release()
        session = null
    }
}
