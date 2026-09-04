package io.github.nightlemon.photobackup.sync

import android.content.Context

class SyncStateStore(context: Context) {
    private val preferences = context.getSharedPreferences("sync-state", Context.MODE_PRIVATE)

    fun set(state: String, message: String) {
        preferences.edit()
            .putString("state", state)
            .putString("message", message)
            .putLong("updatedAt", System.currentTimeMillis())
            .apply()
    }

    fun read(): SyncState = SyncState(
        preferences.getString("state", "idle") ?: "idle",
        preferences.getString("message", "等待同步") ?: "等待同步",
        preferences.getLong("updatedAt", 0),
    )

    fun interruptedMediaKeys(serverId: String, checkpoint: String): Set<String> = synchronized(currentMediaLock) {
        val scoped = preferences.getStringSet(scopedKey(serverId, checkpoint), emptySet()).orEmpty()
        if (checkpoint != ManualSyncScope.AUTOMATIC_CHECKPOINT || preferences.getString("currentServerId", null) != serverId) {
            return@synchronized scoped.toSet()
        }
        buildSet {
            addAll(scoped)
            addAll(preferences.getStringSet("currentMediaKeys", emptySet()).orEmpty())
            preferences.getString("currentMediaKey", null)?.let(::add)
        }
    }

    fun markCurrentMedia(serverId: String, checkpoint: String, mediaKey: String) = synchronized(currentMediaLock) {
        val key = scopedKey(serverId, checkpoint)
        val keys = interruptedMediaKeys(serverId, checkpoint).toMutableSet()
        keys += mediaKey
        preferences.edit()
            .putStringSet(key, keys)
            .remove("currentServerId")
            .remove("currentMediaKey")
            .remove("currentMediaKeys")
            .apply()
    }

    fun clearCurrentMedia(serverId: String, checkpoint: String, mediaKey: String) = synchronized(currentMediaLock) {
        val key = scopedKey(serverId, checkpoint)
        val keys = interruptedMediaKeys(serverId, checkpoint).toMutableSet().apply { remove(mediaKey) }
        val editor = preferences.edit()
        if (keys.isEmpty()) editor.remove(key) else editor.putStringSet(key, keys)
        editor.apply()
    }

    fun retainCurrentMedia(serverId: String, checkpoint: String, mediaKeys: Set<String>) = synchronized(currentMediaLock) {
        val key = scopedKey(serverId, checkpoint)
        val retained = interruptedMediaKeys(serverId, checkpoint).intersect(mediaKeys)
        val editor = preferences.edit()
            .remove("currentServerId")
            .remove("currentMediaKey")
            .remove("currentMediaKeys")
        if (retained.isEmpty()) editor.remove(key) else editor.putStringSet(key, retained)
        editor.apply()
    }

    fun clearCurrentMedia(serverId: String, checkpoint: String) = synchronized(currentMediaLock) {
        preferences.edit().remove(scopedKey(serverId, checkpoint)).apply()
    }

    private fun scopedKey(serverId: String, checkpoint: String) = "currentMediaKeys:$serverId:$checkpoint"

    private companion object {
        val currentMediaLock = Any()
    }
}

data class SyncState(val state: String, val message: String, val updatedAt: Long)

