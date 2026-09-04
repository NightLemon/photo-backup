package io.github.nightlemon.photobackup.media

import android.content.ContentResolver
import android.content.ContentUris
import android.content.Context
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import io.github.nightlemon.photobackup.data.BackupRecord
import io.github.nightlemon.photobackup.data.LocalMedia

class MediaRepository(context: Context) {
    private val resolver: ContentResolver = context.contentResolver

    fun scan(): List<LocalMedia> {
        val collection = MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL)
        val projection = arrayOf(
            MediaStore.MediaColumns._ID,
            MediaStore.MediaColumns.VOLUME_NAME,
            MediaStore.MediaColumns.DISPLAY_NAME,
            MediaStore.MediaColumns.RELATIVE_PATH,
            MediaStore.MediaColumns.MIME_TYPE,
            MediaStore.MediaColumns.SIZE,
            MediaStore.MediaColumns.DATE_MODIFIED,
            MediaStore.Images.ImageColumns.DATE_TAKEN,
        )
        val clauses = mutableListOf(
            "${MediaStore.Files.FileColumns.MEDIA_TYPE} IN (?, ?)",
            "${MediaStore.MediaColumns.IS_PENDING}=0",
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) clauses += "${MediaStore.MediaColumns.IS_TRASHED}=0"
        val args = arrayOf(
            MediaStore.Files.FileColumns.MEDIA_TYPE_IMAGE.toString(),
            MediaStore.Files.FileColumns.MEDIA_TYPE_VIDEO.toString(),
        )
        val result = mutableListOf<LocalMedia>()
        resolver.query(
            collection,
            projection,
            clauses.joinToString(" AND "),
            args,
            "${MediaStore.MediaColumns.DATE_MODIFIED} ASC",
        )?.use { cursor ->
            val idColumn = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns._ID)
            val volumeColumn = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.VOLUME_NAME)
            val nameColumn = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DISPLAY_NAME)
            val pathColumn = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.RELATIVE_PATH)
            val mimeColumn = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.MIME_TYPE)
            val sizeColumn = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.SIZE)
            val modifiedColumn = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DATE_MODIFIED)
            val capturedColumn = cursor.getColumnIndexOrThrow(MediaStore.Images.ImageColumns.DATE_TAKEN)
            while (cursor.moveToNext()) {
                val id = cursor.getLong(idColumn)
                val volume = cursor.getString(volumeColumn) ?: MediaStore.VOLUME_EXTERNAL_PRIMARY
                val mimeType = cursor.getString(mimeColumn) ?: "application/octet-stream"
                val collectionUri = if (mimeType.startsWith("video/", ignoreCase = true)) {
                    MediaStore.Video.Media.getContentUri(volume)
                } else {
                    MediaStore.Images.Media.getContentUri(volume)
                }
                val uri = ContentUris.withAppendedId(collectionUri, id)
                result += LocalMedia(
                    mediaKey = "$volume:$id",
                    contentUri = uri.toString(),
                    displayName = cursor.getString(nameColumn) ?: "media-$id",
                    relativePath = cursor.getString(pathColumn).orEmpty(),
                    mimeType = mimeType,
                    byteLength = cursor.getLong(sizeColumn).coerceAtLeast(0),
                    modifiedAt = secondsToMillis(cursor.getLong(modifiedColumn)),
                    capturedAt = cursor.getLong(capturedColumn).coerceAtLeast(0),
                )
            }
        }
        return result
    }

    fun isUnchanged(media: LocalMedia): Boolean = currentMetadata(media.contentUri)?.let {
        it.first == media.byteLength && it.second == media.modifiedAt
    } == true

    fun isUnchanged(record: BackupRecord): Boolean = currentMetadata(record.contentUri)?.let {
        it.first == record.byteLength && it.second == record.modifiedAt
    } == true

    fun exists(contentUri: String): Boolean = currentMetadata(contentUri) != null

    fun deletionUri(record: BackupRecord): Uri {
        val source = Uri.parse(record.contentUri)
        val volume = record.mediaKey.substringBeforeLast(':', MediaStore.VOLUME_EXTERNAL)
        val collection = if (record.mimeType.startsWith("video/", ignoreCase = true)) {
            MediaStore.Video.Media.getContentUri(volume)
        } else {
            MediaStore.Images.Media.getContentUri(volume)
        }
        return ContentUris.withAppendedId(collection, ContentUris.parseId(source))
    }

    private fun currentMetadata(contentUri: String): Pair<Long, Long>? = runCatching {
        resolver.query(
            android.net.Uri.parse(contentUri),
            arrayOf(MediaStore.MediaColumns.SIZE, MediaStore.MediaColumns.DATE_MODIFIED),
            null,
            null,
            null,
        )?.use { cursor ->
            if (!cursor.moveToFirst()) null else
                cursor.getLong(0).coerceAtLeast(0) to secondsToMillis(cursor.getLong(1))
        }
    }.getOrNull()
}

internal fun secondsToMillis(value: Long): Long = when {
    value <= 0 -> 0
    value > Long.MAX_VALUE / 1000 -> Long.MAX_VALUE
    else -> value * 1000
}

