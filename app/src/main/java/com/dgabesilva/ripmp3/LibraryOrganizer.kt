package com.dgabesilva.ripmp3

import android.content.Context

/**
 * Scans a set of tracks and renames each file to a chosen naming scheme so the
 * whole library follows one convention. Reuses [TagCleaner] for junk-stripping,
 * the Artist/Title split, and the tag-writing rename.
 *
 * Scope is whatever list of tracks the caller passes (the whole library, or the
 * tracks of one playlist). App-owned files (its own downloads) are renamed here
 * directly; phone music is flagged [Plan.appOwned]==false so the caller can run
 * it through the MediaStore write-consent flow ([MediaStoreRenamer]).
 */
object LibraryOrganizer {

    enum class Template(val label: String) {
        ARTIST_TITLE("Artist - Title"),
        TITLE_ARTIST("Title - Artist"),
    }

    /**
     * One proposed rename. [newBase] is the filename (no extension) it would
     * take; [newTitle] is the clean song title (used when updating phone-music
     * metadata). [appOwned] true = the app can rename it directly; false = it's
     * phone music that needs the MediaStore write-consent path.
     */
    data class Plan(
        val track: PlayerService.Track,
        val newBase: String,
        val newTitle: String,
        val appOwned: Boolean,
    )

    private fun isPlaceholderArtist(a: String) =
        a.isBlank() || a == "Unknown Artist" || a == "RIP DOWNLOADS"

    /** Best artist/title for a track: real tags when present, otherwise split from the (cleaned) filename. */
    fun derive(t: PlayerService.Track): Pair<String?, String> =
        if (isPlaceholderArtist(t.artist)) TagCleaner.splitArtistTitle(t.title)
        else t.artist to TagCleaner.cleanTitle(t.title)

    fun newBase(t: PlayerService.Track, tpl: Template): String {
        val (artist, title) = derive(t)
        val base = when {
            artist.isNullOrBlank() -> title
            tpl == Template.ARTIST_TITLE -> "$artist - $title"
            else -> "$title - $artist"
        }
        return TagCleaner.sanitize(base)
    }

    /** True when the file lives in the app's own storage (safely renameable without extra permissions). */
    fun isWritable(context: Context, t: PlayerService.Track): Boolean {
        val appDir = context.getExternalFilesDir(null)?.absolutePath ?: return false
        return t.file.absolutePath.startsWith(appDir)
    }

    /** Builds the list of renames that would actually change something (skips already-correct names). */
    fun buildPlan(context: Context, tracks: List<PlayerService.Track>, tpl: Template): List<Plan> =
        tracks.mapNotNull { t ->
            val (artist, title) = derive(t)
            val base = TagCleaner.sanitize(when {
                artist.isNullOrBlank() -> title
                tpl == Template.ARTIST_TITLE -> "$artist - $title"
                else -> "$title - $artist"
            })
            if (base.isBlank() || base == t.file.nameWithoutExtension) null
            else Plan(t, base, title, isWritable(context, t))
        }

    /** Renames the app-owned plans directly (retag+rename). Phone-music plans are handled separately. Returns count done. */
    suspend fun apply(context: Context, plans: List<Plan>, onProgress: (done: Int, total: Int) -> Unit): Int {
        val own = plans.filter { it.appOwned }
        var ok = 0
        own.forEachIndexed { i, p ->
            val (artist, title) = derive(p.track)
            runCatching {
                TagCleaner.retagAndRename(context, p.track.file, artist, title, genre = null, destBaseName = p.newBase)
                ok++
            }
            onProgress(i + 1, own.size)
        }
        return ok
    }
}
