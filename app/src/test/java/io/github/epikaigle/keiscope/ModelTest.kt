package io.github.epikaigle.keiscope

import org.junit.Assert.*
import org.junit.Test

class ModelTest {
    @Test fun standardSourcePath() {
        val extension = ExtensionInfo("MangaDex", "eu.kanade.tachiyomi.extension.all.mangadex", "all", 1, "1.6.1", false)
        assertEquals("src/all/mangadex", extension.sourcePath)
    }

    @Test fun unrelatedPackagesCannotMapToKeiyoushi() {
        val extension = ExtensionInfo("Fake", "com.other.extension.all.fake", "all", 1, "1.0", false)
        assertNull(extension.sourcePath)
    }

    @Test fun prReferenceIsExtractedOnlyWhenPresent() {
        val commit = CommitInfo("1234567890", "Fix MangaDex (#12345)", "2026-11-07", "https://github.com/keiyoushi/extensions-source/commit/123", "1.6.1")
        assertEquals("https://github.com/keiyoushi/extensions-source/pull/12345", commit.pullRequest)
        assertEquals("1234567", commit.shortSha)
        assertNull(commit.copy(title = "Fix MangaDex").pullRequest)
    }
}
