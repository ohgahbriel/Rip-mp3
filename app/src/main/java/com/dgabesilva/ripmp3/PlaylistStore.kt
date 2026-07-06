package com.dgabesilva.ripmp3

import android.content.Context
import java.io.File

/**
 * Playlists as standard .m3u files in files/Playlists/ — portable to any
 * player that understands M3U.
 */
object PlaylistStore {

    private fun dir(ctx: Context) =
        File(ctx.getExternalFilesDir(null), "Playlists").apply { mkdirs() }

    private fun sanitize(name: String) =
        name.replace(Regex("[\\\\/:*?\"<>|]"), "_").trim().ifEmpty { "playlist" }

    fun list(ctx: Context): List<String> =
        dir(ctx).listFiles { f -> f.extension.equals("m3u", true) }
            ?.map { it.nameWithoutExtension }
            ?.sortedBy { it.lowercase() }
            ?: emptyList()

    /** Returns the cleaned name it was saved under. */
    fun save(ctx: Context, name: String, tracks: List<PlayerService.Track>): String {
        val clean = sanitize(name)
        File(dir(ctx), "$clean.m3u").writeText(buildString {
            appendLine("#EXTM3U")
            tracks.forEach {
                appendLine("#EXTINF:${it.durationMs / 1000},${it.title}")
                appendLine(it.file.absolutePath)
            }
        })
        return clean
    }

    fun load(ctx: Context, name: String): List<File> =
        File(dir(ctx), "${sanitize(name)}.m3u").takeIf { it.exists() }
            ?.readLines()
            ?.filter { it.isNotBlank() && !it.startsWith("#") }
            ?.map { File(it.trim()) }
            ?.filter { it.exists() }
            ?: emptyList()

    fun delete(ctx: Context, name: String) {
        File(dir(ctx), "${sanitize(name)}.m3u").delete()
    }
}
