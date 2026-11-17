package com.worm.app.player

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import com.worm.app.data.Library
import java.io.File
import java.io.FileNotFoundException

/**
 * Serves cover art to Android Auto, the lock screen and the media notification, which live in
 * other processes and can't read Worm's private files. Burrow covers are never served.
 */
class ArtworkProvider : ContentProvider() {
    override fun onCreate() = true

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
        val ctx = context ?: throw FileNotFoundException()
        val id = uri.lastPathSegment ?: throw FileNotFoundException()
        val book = Library.get(ctx).book(id) ?: throw FileNotFoundException()
        if (book.hidden) throw FileNotFoundException()
        val file = book.coverPath?.let(::File)?.takeIf { it.isFile } ?: throw FileNotFoundException()
        return ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
    }

    override fun getType(uri: Uri) = "image/jpeg"
    override fun query(uri: Uri, p: Array<out String>?, s: String?, a: Array<out String>?, o: String?): Cursor? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, s: String?, a: Array<out String>?) = 0
    override fun update(uri: Uri, v: ContentValues?, s: String?, a: Array<out String>?) = 0
}
