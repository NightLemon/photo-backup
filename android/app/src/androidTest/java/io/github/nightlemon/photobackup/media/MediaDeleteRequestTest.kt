package io.github.nightlemon.photobackup.media

import android.content.ContentUris
import android.os.Build
import android.provider.MediaStore
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.nightlemon.photobackup.data.BackupRecord
import org.junit.Assert.assertNotNull
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MediaDeleteRequestTest {
    @Test fun canonicalImageUriCanCreateDeleteRequest() {
        assumeTrue(Build.VERSION.SDK_INT >= Build.VERSION_CODES.R)
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val resolver = context.contentResolver
        val collection = MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL)
        val projection = arrayOf(
            MediaStore.MediaColumns._ID,
            MediaStore.MediaColumns.VOLUME_NAME,
            MediaStore.MediaColumns.MIME_TYPE,
        )
        val record = resolver.query(
            collection,
            projection,
            "${MediaStore.Files.FileColumns.MEDIA_TYPE}=?",
            arrayOf(MediaStore.Files.FileColumns.MEDIA_TYPE_IMAGE.toString()),
            "${MediaStore.MediaColumns.DATE_MODIFIED} DESC",
        )?.use { cursor ->
            assumeTrue("No image is available for the device test", cursor.moveToFirst())
            val id = cursor.getLong(0)
            val volume = cursor.getString(1) ?: MediaStore.VOLUME_EXTERNAL_PRIMARY
            BackupRecord(
                mediaKey = "$volume:$id",
                contentUri = ContentUris.withAppendedId(MediaStore.Files.getContentUri(volume), id).toString(),
                displayName = "device-test",
                relativePath = "",
                mimeType = cursor.getString(2) ?: "image/jpeg",
                byteLength = 0,
                modifiedAt = 0,
                capturedAt = 0,
                serverId = "device-test",
                assetId = "device-test",
                sha256 = "",
                completedAt = 0,
            )
        }
        assertNotNull(record)
        val deletionUri = MediaRepository(context).deletionUri(requireNotNull(record))
        assertNotNull(MediaStore.createDeleteRequest(resolver, listOf(deletionUri)))
    }
}