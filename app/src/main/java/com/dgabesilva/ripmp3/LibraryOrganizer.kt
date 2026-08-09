package com.dgabesilva.ripmp3

import android.content.Context

/**
 * Scans a set of tracks and renames each file to a chosen naming scheme so the
 * whole library follows one convention. Reuses [TagCleaner] for junk-stripping,
 * the Artist/Title split, and the tag-writing rename.
 *
 * Scope is whatever list of tracks the caller passes (the whole library, or the
 * tracks of one playlist). Only files the app is allowed to write — its own
 * downloads — are renamed; anything else is reported as [Plan.writable]==false
 * so the UI can show it was skipped (renaming other apps' music needs a separate
 * Android write-consent flow).
 */
object LibraryOrganizer {

    enum class Template(val label: String) {
        ARTIST_TITLE("Artist - Title"),
        TITLE_ARTIST("Title - Artist"),
    }

    /** One proposed rename. [newBase] is the filename (no extension) it would take. */
    data class Plan(val track: PlayerService.Track, val newBase: String, val writable: Boolean)

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
            val base = newBase(t, tpl)
            if (base.isBlank() || base == t.file.nameWithoutExtension) null
            else Plan(t, base, isWritable(context, t))
        }

    /** Applies the writable plans, retag+renaming each. Returns how many succeeded. */
    suspend fun apply(context: Context, plans: List<Plan>, onProgress: (done: Int, total: Int) -> Unit): Int {
        val writable = plans.filter { it.writable }
        var ok = 0
        writable.forEachIndexed { i, p ->
            val (artist, title) = derive(p.track)
            runCatching {
                TagCleaner.retagAndRename(context, p.track.file, artist, title, genre = null, destBaseName = p.newBase)
                ok++
            }
            onProgress(i + 1, writable.size)
        }
        return ok
    }
}
