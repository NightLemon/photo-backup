package io.github.nightlemon.photobackup.media

import org.junit.Assert.assertEquals
import org.junit.Test

class MediaRepositoryTest {
    @Test fun convertsMediaStoreSecondsWithoutOverflow() {
        assertEquals(0, secondsToMillis(-1))
        assertEquals(1_000, secondsToMillis(1))
        assertEquals(Long.MAX_VALUE, secondsToMillis(Long.MAX_VALUE))
    }
}