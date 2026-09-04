package io.github.nightlemon.photobackup.sync

import io.github.nightlemon.photobackup.data.LocalMedia
import java.util.Locale

private val screenshotNames = setOf("screenshot", "screenshots", "screencapture", "screencaptures", "截图", "截屏", "屏幕截图")

internal fun List<LocalMedia>.selectedAndOrderedForBackup(
    interruptedMediaKeys: Set<String>,
    settings: BackupSettings,
    manualScope: ManualSyncScope? = null,
    zoneId: java.time.ZoneId = java.time.ZoneId.systemDefault(),
): List<LocalMedia> = filter {
    if (manualScope == null) it.isSelected(settings) else manualScope.accepts(it, zoneId)
}.sortedWith { first, second ->
    compareValues(first.interruptedRank(interruptedMediaKeys), second.interruptedRank(interruptedMediaKeys))
        .takeIf { it != 0 }
        ?: compareBySetting(first, second, settings.sortOrder).takeIf { it != 0 }
        ?: compareValues(first.mediaKey, second.mediaKey)
}

internal fun LocalMedia.isScreenshot(): Boolean {
    if (!mimeType.startsWith("image/", ignoreCase = true)) return false
    val pathMatch = relativePath
        .replace('\\', '/')
        .split('/')
        .any { normalizeScreenshotName(it) in screenshotNames }
    val fileName = normalizeScreenshotName(displayName.substringBeforeLast('.'))
    return pathMatch || screenshotNames.any(fileName::startsWith)
}

private fun LocalMedia.isSelected(settings: BackupSettings): Boolean = when {
    isScreenshot() -> settings.includeScreenshots
    mimeType.startsWith("image/", ignoreCase = true) -> settings.includeOtherImages
    mimeType.startsWith("video/", ignoreCase = true) -> settings.includeVideos
    else -> false
}

private fun LocalMedia.interruptedRank(interruptedMediaKeys: Set<String>): Int = if (mediaKey in interruptedMediaKeys) 0 else 1

private fun compareBySetting(first: LocalMedia, second: LocalMedia, order: BackupSortOrder): Int = when (order) {
    BackupSortOrder.TYPE_PRIORITY -> compareValues(first.typeRank(), second.typeRank())
        .takeIf { it != 0 } ?: compareTime(first, second, newestFirst = false)
    BackupSortOrder.OLDEST_FIRST -> compareTime(first, second, newestFirst = false)
    BackupSortOrder.NEWEST_FIRST -> compareTime(first, second, newestFirst = true)
    BackupSortOrder.SMALLEST_FIRST -> compareValues(first.byteLength, second.byteLength)
        .takeIf { it != 0 } ?: compareTime(first, second, newestFirst = false)
    BackupSortOrder.LARGEST_FIRST -> compareValues(second.byteLength, first.byteLength)
        .takeIf { it != 0 } ?: compareTime(first, second, newestFirst = false)
}

private fun LocalMedia.typeRank(): Int = when {
    isScreenshot() -> 0
    mimeType.startsWith("image/", ignoreCase = true) -> 1
    else -> 2
}

private fun compareTime(first: LocalMedia, second: LocalMedia, newestFirst: Boolean): Int {
    val firstTime = first.backupTimestamp()
    val secondTime = second.backupTimestamp()
    if (firstTime == null) return if (secondTime == null) 0 else 1
    if (secondTime == null) return -1
    return if (newestFirst) secondTime.compareTo(firstTime) else firstTime.compareTo(secondTime)
}

private fun LocalMedia.backupTimestamp(): Long? = capturedAt.takeIf { it > 0 } ?: modifiedAt.takeIf { it > 0 }

private fun normalizeScreenshotName(value: String): String = value
    .trim()
    .lowercase(Locale.ROOT)
    .replace(" ", "")
    .replace("_", "")
    .replace("-", "")
