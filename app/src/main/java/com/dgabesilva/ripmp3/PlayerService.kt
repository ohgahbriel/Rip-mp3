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
import android.provider.MediaStore
import android.graphics.drawable.Icon
import android.media.MediaMetadata
import android.media.MediaMetadataRetriever
import android.media.MediaPlayer
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.os.Binder
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
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
        var album: String = "Unknown Album"
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

    // ---------- Library ----------

    // ---------- Queue management ----------

    fun setQueue(list: List<Track>, name: String, startIndex: Int = -1, autoplay: Boolean = false) {
        val playingFile = currentTrack?.file
        tracks.clear()
        tracks.addAll(list)
        queueName = name
        current = if (playingFile != null) tracks.indexOfFirst { it.file == playingFile } else -1
        listener?.onTracksReloaded()
        if (autoplay && tracks.isNotEmpty()) play(if (startIndex >= 0) startIndex else 0)
    }

    /** Appends without duplicates. Returns how many were actually added. */
    fun enqueue(list: List<Track>): Int {
        val have = tracks.mapTo(HashSet()) { it.file.absolutePath }
        val add = list.filter { it.file.absolutePath !in have }
        tracks.addAll(add)
        listener?.onTracksReloaded()
        return add.size
    }

    fun newQueue() = setQueue(emptyList(), "NEW LIST")

    fun resetToLibrary() = setQueue(library.toList(), "LIBRARY")

    fun renameQueue(name: String) {
        queueName = name
        listener?.onTracksReloaded()
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
                val proj = arrayOf(
                    MediaStore.Audio.Media.DATA,
                    MediaStore.Audio.Media.TITLE,
                    MediaStore.Audio.Media.DURATION,
                    MediaStore.Audio.Media.ARTIST,
                    MediaStore.Audio.Media.ALBUM
                )
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
                    while (c.moveToNext()) {
                        val path = c.getString(iData) ?: continue
                        val artist = c.getString(iArtist)
                            ?.takeIf { it.isNotBlank() && it != "<unknown>" } ?: "Unknown Artist"
                        val album = c.getString(iAlbum)
                            ?.takeIf { it.isNotBlank() && it != "<unknown>" } ?: "Unknown Album"
                        val existing = found[path]
                        if (existing != null) {
                            // Our download, already indexed — take the real tags
                            if (c.getInt(iDur) > 0) existing.durationMs = c.getInt(iDur)
                            if (artist != "Unknown Artist") existing.artist = artist
                            if (album != "Unknown Album") existing.album = album
                            continue
                        }
                        val f = File(path)
                        if (!f.exists()) continue
                        found[path] = Track(
                            f, c.getString(iTitle) ?: f.nameWithoutExtension,
                            c.getInt(iDur), artist, album
                        )
                    }
                }
            }

            val list = found.values.toList()
            main.post {
                library.clear()
                library.addAll(list)
                if (queueName == "LIBRARY") {
                    val playingFile = currentTrack?.file
                    tracks.clear()
                    tracks.addAll(list)
                    current = if (playingFile != null) tracks.indexOfFirst { it.file == playingFile } else -1
                }
                listener?.onTracksReloaded()
            }

            list.forEachIndexed { i, t ->
                if (t.durationMs == 0) t.durationMs = readDurationMs(t.file)
                if (i % 8 == 7) main.post { listener?.onTracksReloaded() }
            }
            main.post { listener?.onTracksReloaded() }
        }
    }

    private fun readDurationMs(f: File): Int {
        val mmr = MediaMetadataRetriever()
        return try {
            mmr.setDataSource(f.absolutePath)
            mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toIntOrNull() ?: 0
        } catch (e: Exception) { 0 } finally { runCatching { mmr.release() } }
    }

    // ---------- Transport ----------

    fun play(index: Int) {
        if (tracks.isEmpty()) return
        val idx = index.coerceIn(0, tracks.size - 1)
        releasePlayer()
        current = idx
        val t = tracks[idx]
        listener?.onTrackChanged(idx, t)

        val p = MediaPlayer()
        mp = p
        p.setOnPreparedListener {
            prepared = true
            if (t.durationMs == 0) t.durationMs = it.duration
            it.setVolume(volume * volume, volume * volume)
            it.start()
            // Promote to a started foreground service so playback + the
            // shade controls survive the activity going away
            startService(Intent(this, PlayerService::class.java))
            updateSession(PlaybackState.STATE_PLAYING)
            goForeground()
            listener?.onPlayState(true)
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
    }

    fun step(dir: Int) {
        if (tracks.isEmpty()) return
        val next = nextIndex(dir) ?: return
        play(next)
    }

    fun seekTo(ms: Int) {
        if (prepared) runCatching { mp?.seekTo(ms) }
        updateSession(if (isPlaying) PlaybackState.STATE_PLAYING else PlaybackState.STATE_PAUSED)
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
            var r: Int
            do { r = Random.nextInt(tracks.size) } while (r == current)
            return r
        }
        val n = current + dir
        return when {
            n in tracks.indices -> n
            repeatAll -> (n + tracks.size) % tracks.size
            else -> null
        }
    }

    private fun releasePlayer() {
        prepared = false
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
            session?.setMetadata(
                MediaMetadata.Builder()
                    .putString(MediaMetadata.METADATA_KEY_TITLE, it.title)
                    .putString(MediaMetadata.METADATA_KEY_ARTIST, "RIP // MP3")
                    .putLong(MediaMetadata.METADATA_KEY_DURATION, durationMs.toLong())
                    .build()
            )
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
        if (!isPlaying) stopSelf()
    }

    override fun onDestroy() {
        super.onDestroy()
        stopForeground(STOP_FOREGROUND_REMOVE)
        releasePlayer()
        session?.release()
        session = null
    }
}
