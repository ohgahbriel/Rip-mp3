package com.dgabesilva.ripmp3

import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.provider.MediaStore

/**
 * Renames music the app does NOT own (files another source put on the phone,
 * surfaced through the media store) by updating its MediaStore row. On Android
 * 11+ the caller must first obtain write consent for the URIs via
 * [MediaStore.createWriteRequest]; after the user taps Allow, updating
 * DISPLAY_NAME renames the file on disk and TITLE updates what every app shows.
 */
object MediaStoreRenamer {

    private val COLLECTION = MediaStore.Audio.Media.EXTERNAL_CONTENT_URI

    /** Resolves a file path to its MediaStore content URI (null if not indexed). */
    fun uriFor(context: Context, path: String): Uri? = runCatching {
        context.contentResolver.query(
            COLLECTION, arrayOf(MediaStore.Audio.Media._ID),
            "${MediaStore.Audio.Media.DATA}=?", arrayOf(path), null
        )?.use { c ->
            if (c.moveToFirst()) ContentUris.withAppendedId(COLLECTION, c.getLong(0)) else null
        }
    }.getOrNull()

    /** Renames the file ([displayName] must include the extension) and sets its display title. */
    fun applyUpdate(context: Context, uri: Uri, displayName: String, title: String): Boolean = runCatching {
        val cv = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, displayName)
            put(MediaStore.Audio.Media.TITLE, title)
        }
        context.contentResolver.update(uri, cv, null, null) > 0
    }.getOrDefault(false)
}
