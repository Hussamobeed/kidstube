package com.family.kidstube

import android.content.ContentUris
import android.content.Context
import android.provider.MediaStore

object VideoStore {

    /** Loads every video the system media scanner knows about (local files only). */
    fun loadVideos(context: Context): List<VideoItem> {
        val videos = mutableListOf<VideoItem>()
        val collection = MediaStore.Video.Media.EXTERNAL_CONTENT_URI
        val projection = arrayOf(
            MediaStore.Video.Media._ID,
            MediaStore.Video.Media.DISPLAY_NAME,
            MediaStore.Video.Media.DURATION,
            MediaStore.Video.Media.SIZE
        )
        val sortOrder = "${MediaStore.Video.Media.DATE_ADDED} DESC"

        context.contentResolver.query(collection, projection, null, null, sortOrder)?.use { cursor ->
            val idCol = cursor.getColumnIndexOrThrow(MediaStore.Video.Media._ID)
            val nameCol = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.DISPLAY_NAME)
            val durCol = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.DURATION)
            val sizeCol = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.SIZE)

            while (cursor.moveToNext()) {
                val id = cursor.getLong(idCol)
                val name = cursor.getString(nameCol) ?: continue
                val uri = ContentUris.withAppendedId(collection, id)
                videos.add(
                    VideoItem(
                        id = id,
                        uri = uri,
                        title = name.substringBeforeLast('.'),
                        durationMs = cursor.getLong(durCol),
                        sizeBytes = cursor.getLong(sizeCol)
                    )
                )
            }
        }
        return videos
    }
}
