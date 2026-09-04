package io.github.nightlemon.photobackup.sync

import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicInteger

internal suspend fun runBoundedWorkers(
    itemCount: Int,
    parallelism: Int,
    process: suspend (Int) -> Unit,
) = coroutineScope {
    val nextIndex = AtomicInteger(0)
    repeat(minOf(itemCount, parallelism.coerceAtLeast(1))) {
        launch {
            while (true) {
                val index = nextIndex.getAndIncrement()
                if (index >= itemCount) break
                process(index)
            }
        }
    }
}
