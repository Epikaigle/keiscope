package io.github.epikaigle.keiscope

import org.junit.Assert.*
import org.junit.Test

class AppUpdatesTest {
    @Test fun higherPatchVersion() = assertTrue(AppUpdates.isNewer("v1.0.1", "1.0.0"))
    @Test fun equalVersion() = assertFalse(AppUpdates.isNewer("v1.0.0", "1.0.0"))
    @Test fun lowerVersion() = assertFalse(AppUpdates.isNewer("v1.0.0", "1.0.1"))
    @Test fun malformedTag() = assertFalse(AppUpdates.isNewer("anything", "1.0.0"))
    @Test fun versionWithSuffix() = assertTrue(AppUpdates.isNewer("v1.1.0-beta1", "1.0.0"))
    @Test fun versionWithoutVPrefix() = assertTrue(AppUpdates.isNewer("2.0.0", "1.9.9"))
}
