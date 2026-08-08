package com.dgabesilva.ripmp3

import android.app.Activity
import android.app.Dialog
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.media.MediaMetadataRetriever
import android.view.Gravity
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * The shared "edit tags / rename" sheet — the single place any track's
 * title / artist / genre gets changed, so the same Winamp-styled dialog and
 * the same retag+rename pipeline back the ≡ menu, the player queue's
 * long-press, and the browser's long-press alike (previously only the
 * currently-playing track could be edited, from a copy of this that lived
 * privately inside PlayerActivity).
 *
 * Renaming a file *is* editing its title/artist here: the on-disk name is
 * always derived from those two fields by [TagCleaner.retagAndRename], so
 * there's no separate "rename the file" control to keep in sync — change the
 * title, the filename follows.
 */
object TagEditDialog {

    /**
     * Shows the editor for [track]. Reads the file's existing genre on a
     * background thread first (so the GENRE box comes up pre-filled instead of
     * always blank), then builds the dialog on the main thread. [onChanged]
     * fires after a successful retag+rename — the caller uses it to rescan and
     * flash a confirmation.
     */
    fun show(activity: Activity, scope: CoroutineScope, track: PlayerService.Track, onChanged: () -> Unit) {
        scope.launch {
            val genre = withContext(Dispatchers.IO) { readGenre(track.file) }.orEmpty()
            build(activity, scope, track, genre, onChanged)
        }
    }

    private fun readGenre(f: File): String? {
        val mmr = MediaMetadataRetriever()
        return try {
            mmr.setDataSource(f.absolutePath)
            mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_GENRE)?.takeIf { it.isNotBlank() }
        } catch (e: Exception) {
            null
        } finally {
            runCatching { mmr.release() }
        }
    }

    private fun build(
        activity: Activity,
        scope: CoroutineScope,
        track: PlayerService.Track,
        genre: String,
        onChanged: () -> Unit,
    ) {
        val density = activity.resources.displayMetrics.density

        // Placeholder artists ("RIP DOWNLOADS", "Unknown Artist") aren't real
        // tags — for those we split the filename ("Metallica - One") into the
        // artist/title fields so the editor opens already broken apart instead
        // of dumping the whole string into TITLE. A real, non-placeholder
        // artist is trusted as-is.
        val isPlaceholder = track.artist.isBlank() ||
            track.artist == "Unknown Artist" || track.artist == "RIP DOWNLOADS"
        val prefArtist: String
        val prefTitle: String
        if (isPlaceholder) {
            val (a, t) = TagCleaner.splitArtistTitle(track.title)
            prefArtist = a.orEmpty()
            prefTitle = t
        } else {
            prefArtist = track.artist
            prefTitle = track.title
        }

        val dialog = Dialog(activity)
        val root = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            background = activity.getDrawable(R.drawable.bevel_raised)
            val p = (10 * density).toInt()
            setPadding(p, p, p, p)
        }
        root.addView(TextView(activity).apply {
            text = "◢ EDIT TAGS / RENAME"
            setTextColor(activity.skinColor(R.attr.skinAccent))
            textSize = 12f
            typeface = Typeface.MONOSPACE
            setTypeface(typeface, Typeface.BOLD)
            letterSpacing = 0.15f
            setPadding(0, 0, 0, (8 * density).toInt())
        })

        fun field(label: String, value: String, hint: String): EditText {
            root.addView(TextView(activity).apply {
                text = label
                setTextColor(activity.skinColor(R.attr.skinText))
                textSize = 10f
                typeface = Typeface.MONOSPACE
                letterSpacing = 0.1f
                setPadding(0, (8 * density).toInt(), 0, (3 * density).toInt())
            })
            val input = EditText(activity).apply {
                setText(value)
                this.hint = hint
                setTextColor(activity.skinColor(R.attr.skinLcd))
                setHintTextColor(activity.skinColor(R.attr.skinBevelLight))
                textSize = 13f
                typeface = Typeface.MONOSPACE
                background = activity.getDrawable(R.drawable.lcd_sunken)
                setPadding((10 * density).toInt(), (9 * density).toInt(), (10 * density).toInt(), (9 * density).toInt())
            }
            root.addView(input)
            return input
        }

        val titleInput = field("TITLE", prefTitle, "song title")
        val artistInput = field("ARTIST", prefArtist, "artist")
        val genreInput = field("GENRE", genre, "e.g. Rock")

        val row = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, (10 * density).toInt(), 0, 0)
        }
        fun btn(label: String, action: () -> Unit) = TextView(activity).apply {
            text = label
            gravity = Gravity.CENTER
            setTextColor(activity.skinColor(R.attr.skinAccent))
            textSize = 12f
            typeface = Typeface.MONOSPACE
            setTypeface(typeface, Typeface.BOLD)
            background = activity.getDrawable(R.drawable.wa_button)
            layoutParams = LinearLayout.LayoutParams(0, (38 * density).toInt(), 1f)
                .apply { marginEnd = (4 * density).toInt() }
            setOnClickListener { action() }
        }
        row.addView(btn("SAVE") {
            val newTitle = titleInput.text.toString().trim().ifBlank { prefTitle }.ifBlank { track.title }
            val newArtist = artistInput.text.toString().trim().ifBlank { null }
            val newGenre = genreInput.text.toString().trim().ifBlank { null }
            dialog.dismiss()
            scope.launch {
                TagCleaner.retagAndRename(activity, track.file, newArtist, newTitle, newGenre)
                onChanged()
            }
        })
        row.addView(btn("CANCEL") { dialog.dismiss() })
        root.addView(row)

        dialog.setContentView(root)
        dialog.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        dialog.window?.setLayout((300 * density).toInt(), ViewGroup.LayoutParams.WRAP_CONTENT)
        dialog.show()
    }
}
