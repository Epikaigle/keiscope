package io.github.epikaigle.keiscope

import org.junit.Assert.*
import org.junit.Test

class CatalogParserTest {
    @Test fun currentKeiyoushiIndex() {
        val json = """
            {
              "name":"Keiyoushi",
              "extensionList":{"extensions":[
                {"name":"MangaDex","packageName":"eu.kanade.tachiyomi.extension.all.mangadex",
                 "versionCode":"106005","versionName":"1.6.5","extensionLib":"1.6",
                 "contentWarning":"CONTENT_WARNING_NONE","sources":[{"language":"en"}]},
                {"name":"AHottie","packageName":"eu.kanade.tachiyomi.extension.all.ahottie",
                 "versionCode":"106011","versionName":"1.6.11",
                 "contentWarning":"CONTENT_WARNING_NSFW"}
              ]}
            }
        """.trimIndent()
        val results = CatalogParser.parse(json)
        assertEquals(2, results.size)
        val manga = results.first { it.pkg.endsWith(".mangadex") }
        assertEquals("all", manga.lang)
        assertEquals(106005L, manga.code)
        assertEquals("1.6.5", manga.version)
        assertFalse(manga.nsfw)
        assertTrue(results.first { it.name == "AHottie" }.nsfw)
    }

    @Test fun legacyList() {
        val json = """[{"name":"Tachiyomi: Foo","pkg":"eu.kanade.tachiyomi.extension.fr.foo",
          "lang":"fr","code":48,"version":"1.4.48","nsfw":0}]"""
        val parsed = CatalogParser.parse(json)
        assertEquals(1, parsed.size)
        assertEquals("Foo", parsed.single().name)
        assertEquals(48L, parsed.single().code)
    }

    @Test fun rejectsUnknownFormat() {
        try {
            CatalogParser.parse("""{"unrecognized":true}""")
            fail("Should reject incompatible catalog formats")
        } catch (_: IllegalArgumentException) { }
    }

    @Test fun ignoresForeignApps() {
        val results = CatalogParser.parse("""{"extensionList":{"extensions":[
          {"name":"Fake","packageName":"com.fake.extension","versionCode":"1"},
          {"name":"Valid","packageName":"eu.kanade.tachiyomi.extension.en.valid",
           "versionCode":"106001","versionName":"1.6.1"}]}}""")
        assertEquals(1, results.size)
        assertEquals("Valid", results.single().name)
    }
}
