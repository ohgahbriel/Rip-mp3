package com.dgabesilva.ripmp3

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.net.Uri
import android.provider.MediaStore
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.drawable.Icon
import android.media.AudioManager
import android.media.MediaMetadata
import android.media.MediaMetadataRetriever
import android.media.audiofx.BassBoost
import android.media.audiofx.Equalizer
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.os.Binder
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import androidx.annotation.OptIn
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import java.io.File
import java.util.Locale
import kotlin.concurrent.thread

/**
 * Foreground playback service. The audio engine is ExoPlayer (Media3): it holds
 * the whole queue as media items so consecutive tracks play GAPLESS, and it owns
 * audio focus, "becoming noisy" (headphone-unplug) handling, and the playback
 * wake lock. We keep our own framework MediaSession + notification so the shade
 * / lock-screen / Bluetooth controls, and the direct-binding activities, are
 * unchanged. State changes are driven off a single [Player.Listener].
 */
@OptIn(UnstableApi::class)
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
    val tracks = mutableListOf<Track>()    // active play queue; kept in lockstep with the player's media items
    var queueName = "LIBRARY"; private set
    var current = -1; private set

    // Shuffle / repeat are backed by ExoPlayer; the fields are the source of
    // truth for persistence and are applied to the player whenever they change.
    private var _shuffle = false
    var shuffle: Boolean
        get() = _shuffle
        set(v) { _shuffle = v; player?.shuffleModeEnabled = v; saveState() }
    private var _repeatAll = false
    var repeatAll: Boolean
        get() = _repeatAll
        set(v) { _repeatAll = v; player?.repeatMode = if (v) Player.REPEAT_MODE_ALL else Player.REPEAT_MODE_OFF; saveState() }

    var listener: Listener? = null
    var volume = 0.8f
        set(value) {
            field = value
            runCatching { player?.volume = value * value }   // perceptual taper
        }

    private var player: ExoPlayer? = null
    private var audioSessionId = 0
    private var session: MediaSession? = null
    private val main = Handler(Looper.getMainLooper())
    private val audioManager by lazy { getSystemService(AUDIO_SERVICE) as AudioManager }

    // Cached decode of the current file's embedded cover, so setMetadata (called
    // on every state change) doesn't re-read the file each time.
    private var artCache: Pair<String, Bitmap?>? = null

    // ---------- Resume ----------
    private var sessionRestored = false

    // ---------- Audio effects / speed / sleep / A-B loop ----------
    private var eq: Equalizer? = null
    private var bass: BassBoost? = null
    var eqBandCount = 0; private set
    var eqMinLevel: Short = -1500; private set
    var eqMaxLevel: Short = 1500; private set
    private val eqCenterFreqs = mutableListOf<Int>()   // Hz per band
    val eqPresetNames = mutableListOf<String>()
    var fxEnabled = false; private set
    private var eqLevels = ShortArray(0)               // millibel per band
    var bassStrength = 0; private set                  // 0..1000
    var speed = 1f; private set
    var pitch = 1f; private set
    private var sleepEndMs = 0L
    var loopA = -1; private set
    var loopB = -1; private set

    val isPlaying get() = player?.isPlaying == true
    val positionMs get() = (player?.currentPosition ?: 0L).toInt().coerceAtLeast(0)
    val durationMs get() =
        player?.duration?.takeIf { it != C.TIME_UNSET }?.toInt()?.coerceAtLeast(0) ?: (currentTrack?.durationMs ?: 0)
    val currentTrack get() = tracks.getOrNull(current)

    companion object {
        private const val CHANNEL = "playback"
        private const val NOTIF_ID = 7
        const val ACT_PREV = "com.dgabesilva.ripmp3.PREV"
        const val ACT_PLAYPAUSE = "com.dgabesilva.ripmp3.PLAYPAUSE"
        const val ACT_NEXT = "com.dgabesilva.ripmp3.NEXT"
        const val ACT_SHUTDOWN = "com.dgabesilva.ripmp3.SHUTDOWN"
    }

    // ---------- Player callbacks ----------

    private val playerListener = object : Player.Listener {
        override fun onMediaItemTransition(item: MediaItem?, reason: Int) {
            val idx = player?.currentMediaItemIndex ?: -1
            current = if (idx in tracks.indices) idx else -1
            clearLoop() // A-B loop is per-track
            val t = currentTrack
            listener?.onTrackChanged(current, t)
            // Count a play only when we're actually going to play it (not on the
            // paused restore/setMediaItems that also fires this callback).
            if (player?.playWhenReady == true) t?.let { recordPlay(it) }
            updateSession(if (isPlaying) PlaybackState.STATE_PLAYING else PlaybackState.STATE_PAUSED)
            refreshNotification()
            saveState()
        }

        override fun onIsPlayingChanged(playing: Boolean) {
            if (playing) {
                startService(Intent(this@PlayerService, PlayerService::class.java))
                updateSession(PlaybackState.STATE_PLAYING)
                goForeground()
            } else {
                updateSession(PlaybackState.STATE_PAUSED)
                notifyPaused()
            }
            listener?.onPlayState(playing)
            saveState()
        }

        override fun onPlaybackStateChanged(state: Int) {
            if (state == Player.STATE_ENDED) {
                updateSession(PlaybackState.STATE_STOPPED)
                notifyPaused()
                listener?.onPlayState(false)
                saveState()
            }
        }

        override fun onPlayerError(error: PlaybackException) {
            // A bad/missing file — skip to the next rather than wedging.
            player?.let { if (it.hasNextMediaItem()) it.seekToNextMediaItem() }
        }
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

        probeFxCaps()
        loadFxState()

        // Build the engine. ExoPlayer manages focus + becoming-noisy + wakelock.
        audioSessionId = runCatching { audioManager.generateAudioSessionId() }.getOrDefault(0)
        player = ExoPlayer.Builder(this).build().apply {
            setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(C.USAGE_MEDIA)
                    .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
                    .build(),
                /* handleAudioFocus = */ true
            )
            setHandleAudioBecomingNoisy(true)
            setWakeMode(C.WAKE_MODE_LOCAL)
            if (audioSessionId != 0) runCatching { setAudioSessionId(audioSessionId) }
            volume = this@PlayerService.volume * this@PlayerService.volume
            setPlaybackParameters(PlaybackParameters(speed, pitch))
            addListener(playerListener)
        }
        if (fxEnabled && audioSessionId != 0) attachFx(audioSessionId)

        loadTracks()
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

    private fun buildMediaItem(t: Track): MediaItem = MediaItem.fromUri(Uri.fromFile(t.file))

    /** Rebuilds the player's playlist from [tracks] (a full reset). Used by whole-queue changes only. */
    private fun syncPlayerItems(startIndex: Int, startPos: Long, play: Boolean) {
        val p = player ?: return
        if (tracks.isEmpty()) { p.clearMediaItems(); current = -1; return }
        val idx = startIndex.coerceIn(0, tracks.size - 1)
        p.setMediaItems(tracks.map { buildMediaItem(it) }, idx, startPos)
        p.prepare()
        p.playWhenReady = play
        current = idx
    }

    // ---------- Embedded art ----------

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
        if (!fxEnabled || eqBandCount == 0 || sessionId == 0) return
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
        if (on) attachFx(audioSessionId) else releaseFx()
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

    fun setSpeed(v: Float) { speed = v.coerceIn(0.25f, 3f); applyParams(); saveFx() }
    fun setPitch(v: Float) { pitch = v.coerceIn(0.5f, 2f); applyParams(); saveFx() }
    private fun applyParams() { runCatching { player?.playbackParameters = PlaybackParameters(speed, pitch) } }

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

    // ---------- Queue management ----------

    fun setQueue(list: List<Track>, name: String, startIndex: Int = -1, autoplay: Boolean = false) {
        val playingFile = currentTrack?.file
        tracks.clear()
        tracks.addAll(list)
        queueName = name
        val idx = when {
            startIndex >= 0 -> startIndex
            playingFile != null -> tracks.indexOfFirst { it.file == playingFile }.let { if (it >= 0) it else 0 }
            else -> 0
        }
        // Keep playing seamlessly if the current track is still here and we're not
        // being asked to start somewhere specific; otherwise start fresh.
        val keepingCurrent = playingFile != null && startIndex < 0 && tracks.getOrNull(idx)?.file == playingFile
        val startPos = if (keepingCurrent) positionMs.toLong() else 0L
        val play = autoplay || (keepingCurrent && isPlaying)
        syncPlayerItems(idx, startPos, play)
        listener?.onTracksReloaded()
        saveState()
    }

    /** Appends without duplicates. Returns how many were actually added. */
    fun enqueue(list: List<Track>): Int {
        val have = tracks.mapTo(HashSet()) { it.file.absolutePath }
        val add = list.filter { it.file.absolutePath !in have }
        if (add.isNotEmpty()) {
            tracks.addAll(add)
            player?.addMediaItems(add.map { buildMediaItem(it) })
            if (player?.playbackState == Player.STATE_IDLE) player?.prepare()
        }
        listener?.onTracksReloaded()
        saveState()
        return add.size
    }

    /** Inserts right after the current track (plays next), skipping dupes. Returns how many were added. */
    fun enqueueNext(list: List<Track>): Int {
        val have = tracks.mapTo(HashSet()) { it.file.absolutePath }
        val add = list.filter { it.file.absolutePath !in have }
        if (add.isNotEmpty()) {
            val at = (current + 1).coerceIn(0, tracks.size)
            tracks.addAll(at, add)
            player?.addMediaItems(at, add.map { buildMediaItem(it) })
            if (player?.playbackState == Player.STATE_IDLE) player?.prepare()
            current = player?.currentMediaItemIndex ?: current
        }
        listener?.onTracksReloaded()
        saveState()
        return add.size
    }

    /** Removes one track from the queue (not disk) without interrupting the current song. */
    fun removeFromQueue(index: Int) {
        if (index !in tracks.indices) return
        tracks.removeAt(index)
        runCatching { player?.removeMediaItem(index) }
        current = player?.currentMediaItemIndex?.takeIf { it in tracks.indices } ?: (if (tracks.isEmpty()) -1 else current.coerceIn(0, tracks.size - 1))
        listener?.onTracksReloaded()
        saveState()
    }

    /** Drag-and-drop reorder: moves the track at [from] to [to], keeping playback going. */
    fun moveTrack(from: Int, to: Int) {
        if (from == to || from !in tracks.indices || to !in tracks.indices) return
        val t = tracks.removeAt(from)
        tracks.add(to, t)
        runCatching { player?.moveMediaItem(from, to) }
        current = player?.currentMediaItemIndex ?: current
        listener?.onTracksReloaded()
        saveState()
    }

    /** Column-header sort: reorders the queue in place, keeping the current song playing. */
    fun sortTracks(comparator: Comparator<Track>) {
        val playingFile = currentTrack?.file
        val wasPlaying = isPlaying
        val pos = positionMs.toLong()
        tracks.sortWith(comparator)
        val idx = if (playingFile != null) tracks.indexOfFirst { it.file == playingFile }.coerceAtLeast(0)
                  else (player?.currentMediaItemIndex ?: 0)
        syncPlayerItems(idx, pos, wasPlaying)
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

    // ---------- Play stats + delete ----------

    private fun statsPrefs() = getSharedPreferences("player_stats", MODE_PRIVATE)

    private fun recordPlay(t: Track) {
        runCatching {
            val path = t.file.absolutePath
            statsPrefs().edit()
                .putInt("c:$path", statsPrefs().getInt("c:$path", 0) + 1)
                .putLong("t:$path", System.currentTimeMillis())
                .apply()
        }
    }

    /** Library tracks sorted by play count (desc), most-played first. */
    fun buildMostPlayed(limit: Int = 60): List<Track> {
        val p = statsPrefs()
        return library.map { it to p.getInt("c:${it.file.absolutePath}", 0) }
            .filter { it.second > 0 }
            .sortedByDescending { it.second }
            .take(limit).map { it.first }
    }

    /** Library tracks sorted by last-played time (desc), most recent first. */
    fun buildRecent(limit: Int = 60): List<Track> {
        val p = statsPrefs()
        return library.map { it to p.getLong("t:${it.file.absolutePath}", 0L) }
            .filter { it.second > 0 }
            .sortedByDescending { it.second }
            .take(limit).map { it.first }
    }

    /** Deletes the file from disk and drops it from the queue + library. Returns true on success. */
    fun deleteTrackFile(t: Track): Boolean {
        val ok = runCatching { t.file.delete() }.getOrDefault(false)
        if (!ok) return false
        val qi = tracks.indexOfFirst { it.file == t.file }
        if (qi >= 0) {
            tracks.removeAt(qi)
            runCatching { player?.removeMediaItem(qi) }
            current = player?.currentMediaItemIndex?.takeIf { it in tracks.indices } ?: -1
        }
        library.removeAll { it.file == t.file }
        listener?.onTracksReloaded()
        saveState()
        runCatching {
            android.media.MediaScannerConnection.scanFile(this, arrayOf(t.file.absolutePath), null, null)
        }
        return true
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
                .putBoolean("shuffle", _shuffle)
                .putBoolean("repeat", _repeatAll)
                .apply()
        }
    }

    /**
     * Rebuilds the last session's queue from saved file paths (skipping any that
     * no longer exist), loads it into the player PAUSED at the saved position,
     * and restores index / shuffle / repeat — so the app reopens exactly where
     * it was without auto-playing.
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
        val idx = p.getInt("q_index", -1).let { if (it in tracks.indices) it else 0 }
        syncPlayerItems(idx, p.getInt("q_pos", 0).coerceAtLeast(0).toLong(), play = false)
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
                // First scan after launch: reopen the last session (or default to
                // the whole library). Later rescans only touch the LIBRARY queue
                // while nothing is playing, so a background download-rescan never
                // interrupts the current song.
                val didRestore = if (!sessionRestored) {
                    sessionRestored = true
                    restoreSavedSession(list)
                } else false
                if (!didRestore && queueName == "LIBRARY" && !isPlaying) {
                    tracks.clear()
                    tracks.addAll(list)
                    val idx = current.coerceIn(0, maxOf(0, tracks.size - 1))
                    syncPlayerItems(idx, 0, false)
                }
                listener?.onTracksReloaded()
            }

            // Fill anything MediaStore didn't give us straight from the file's own
            // tags: duration always (some rows report 0), and genre on the
            // devices/files the GENRE column couldn't cover.
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
        val p = player ?: return
        if (tracks.isEmpty()) return
        val idx = index.coerceIn(0, tracks.size - 1)
        clearLoop()
        if (p.mediaItemCount != tracks.size) syncPlayerItems(idx, 0, false)
        if (p.playbackState == Player.STATE_IDLE) p.prepare()
        p.seekTo(idx, 0)
        p.playWhenReady = true
    }

    fun resume() {
        val p = player ?: return
        if (tracks.isEmpty()) return
        if (p.mediaItemCount == 0) syncPlayerItems(current.coerceAtLeast(0), 0, false)
        if (p.playbackState == Player.STATE_IDLE) p.prepare()
        if (p.currentMediaItemIndex == C.INDEX_UNSET) p.seekTo(current.coerceAtLeast(0), 0)
        p.playWhenReady = true
    }

    fun pause() { player?.playWhenReady = false }

    fun stopToZero() {
        player?.let {
            it.playWhenReady = false
            it.seekTo(it.currentMediaItemIndex.coerceAtLeast(0), 0)
        }
    }

    fun step(dir: Int) {
        val p = player ?: return
        if (tracks.isEmpty()) return
        if (dir > 0) p.seekToNextMediaItem() else p.seekToPreviousMediaItem()
    }

    fun seekTo(ms: Int) {
        player?.seekTo(ms.toLong().coerceAtLeast(0))
        saveState()
    }

    // ---------- Session + notification ----------

    private fun refreshNotification() {
        if (isPlaying) goForeground()
        else runCatching { getSystemService(NotificationManager::class.java).notify(NOTIF_ID, buildNotification()) }
    }

    private fun updateSession(state: Int) {
        session?.setPlaybackState(
            PlaybackState.Builder()
                .setActions(
                    PlaybackState.ACTION_PLAY or PlaybackState.ACTION_PAUSE or
                    PlaybackState.ACTION_PLAY_PAUSE or PlaybackState.ACTION_SKIP_TO_NEXT or
                    PlaybackState.ACTION_SKIP_TO_PREVIOUS or PlaybackState.ACTION_SEEK_TO or
                    PlaybackState.ACTION_STOP
                )
                .setState(state, positionMs.toLong(), if (state == PlaybackState.STATE_PLAYING) speed else 0f)
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
        releaseFx()
        player?.release()
        player = null
        session?.release()
        session = null
    }
}
