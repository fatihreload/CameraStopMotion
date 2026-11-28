package com.motionstudio.pro.data

import android.content.Context
import android.content.Intent
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.provider.OpenableColumns
import java.util.UUID

object MediaImporter {
    const val IMAGE_DEFAULT_MS = 3000L
    const val IMAGE_MAX_MS = 60_000L

    /** Mengembalikan null bila file tidak didukung atau tidak bisa dibaca. Panggil dari thread IO. */
    fun import(ctx: Context, uri: Uri): Clip? {
        try {
            val cr = ctx.contentResolver
            try { cr.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) } catch (_: Exception) {}
            val mime = cr.getType(uri) ?: return null
            var name = "media"; var size = 0L
            cr.query(uri, null, null, null, null)?.use { c ->
                if (c.moveToFirst()) {
                    val n = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (n >= 0) name = c.getString(n) ?: name
                    val s = c.getColumnIndex(OpenableColumns.SIZE)
                    if (s >= 0) size = c.getLong(s)
                }
            }
            val id = UUID.randomUUID().toString()
            if (mime.startsWith("video/")) {
                val r = MediaMetadataRetriever()
                try {
                    r.setDataSource(ctx, uri)
                    val dur = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: return null
                    if (dur <= 0) return null
                    var w = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: return null
                    var h = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: return null
                    val rot = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull() ?: 0
                    if (rot == 90 || rot == 270) { val t = w; w = h; h = t }
                    return Clip(id, uri.toString(), ClipType.VIDEO, name, dur, 0, dur, w, h, size)
                } finally { r.release() }
            }
            if (mime.startsWith("image/")) {
                val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                cr.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, o) }
                if (o.outWidth <= 0 || o.outHeight <= 0) return null
                return Clip(id, uri.toString(), ClipType.IMAGE, name, IMAGE_MAX_MS, 0, IMAGE_DEFAULT_MS, o.outWidth, o.outHeight, size)
            }
            return null
        } catch (_: Exception) { return null }
    }
}
