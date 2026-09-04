package io.github.nightlemon.photobackup.sync

import androidx.work.Data
import androidx.work.workDataOf
import io.github.nightlemon.photobackup.data.LocalMedia
import java.time.Instant
import java.time.ZoneId

data class ManualSyncScope(
    val includeScreenshots: Boolean,
    val includeOtherImages: Boolean,
    val includeVideos: Boolean,
    val startEpochDay: Long? = null,
    val endEpochDay: Long? = null,
) {
    init {
        require(includeScreenshots || includeOtherImages || includeVideos) { "至少选择一种媒体类型" }
        require(startEpochDay == null || endEpochDay == null || startEpochDay <= endEpochDay) { "开始日期不能晚于结束日期" }
    }

    fun toWorkData(): Data = workDataOf(
        KEY_MODE to MODE_MANUAL,
        KEY_SCREENSHOTS to includeScreenshots,
        KEY_OTHER_IMAGES to includeOtherImages,
        KEY_VIDEOS to includeVideos,
        KEY_START_DAY to (startEpochDay ?: NO_DATE),
        KEY_END_DAY to (endEpochDay ?: NO_DATE),
    )

    fun checkpointKey(): String = buildString {
        append("manual:")
        if (includeScreenshots) append('s')
        if (includeOtherImages) append('i')
        if (includeVideos) append('v')
        append(':').append(startEpochDay ?: "any")
        append(':').append(endEpochDay ?: "any")
    }

    internal fun accepts(media: LocalMedia, zoneId: ZoneId = ZoneId.systemDefault()): Boolean {
        val typeAccepted = when {
            media.isScreenshot() -> includeScreenshots
            media.mimeType.startsWith("image/", ignoreCase = true) -> includeOtherImages
            media.mimeType.startsWith("video/", ignoreCase = true) -> includeVideos
            else -> false
        }
        if (!typeAccepted) return false
        if (startEpochDay == null && endEpochDay == null) return true
        val timestamp = media.capturedAt.takeIf { it > 0 } ?: media.modifiedAt.takeIf { it > 0 } ?: return false
        val epochDay = Instant.ofEpochMilli(timestamp).atZone(zoneId).toLocalDate().toEpochDay()
        return (startEpochDay == null || epochDay >= startEpochDay) &&
            (endEpochDay == null || epochDay <= endEpochDay)
    }

    companion object {
        const val AUTOMATIC_CHECKPOINT = "automatic"
        private const val MODE_MANUAL = "manual"
        private const val KEY_MODE = "sync.mode"
        private const val KEY_SCREENSHOTS = "sync.includeScreenshots"
        private const val KEY_OTHER_IMAGES = "sync.includeOtherImages"
        private const val KEY_VIDEOS = "sync.includeVideos"
        private const val KEY_START_DAY = "sync.startEpochDay"
        private const val KEY_END_DAY = "sync.endEpochDay"
        private const val NO_DATE = Long.MIN_VALUE

        fun fromWorkData(data: Data): ManualSyncScope? {
            val mode = data.getString(KEY_MODE) ?: return null
            require(mode == MODE_MANUAL) { "不支持的同步模式" }
            return ManualSyncScope(
                includeScreenshots = data.getBoolean(KEY_SCREENSHOTS, false),
                includeOtherImages = data.getBoolean(KEY_OTHER_IMAGES, false),
                includeVideos = data.getBoolean(KEY_VIDEOS, false),
                startEpochDay = data.getLong(KEY_START_DAY, NO_DATE).takeUnless { it == NO_DATE },
                endEpochDay = data.getLong(KEY_END_DAY, NO_DATE).takeUnless { it == NO_DATE },
            )
        }
    }
}
