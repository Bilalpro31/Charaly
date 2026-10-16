package dev.charaly.runtime.storypack

import dev.charaly.runtime.net.HttpTextResponse
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The Story Catalog: what is available, as opposed to what is installed.
 *
 * And the one rule that keeps discovery from becoming a dependency: **the catalog screen
 * never fails**. Offline, 404, garbage - it degrades to the listings it ships with and says
 * so in a footnote.
 */
class StoryPackCatalogTest {

    private fun item(
        id: String = "entry.paris.1",
        packId: String = "pack.paris.afterdark",
        version: String = "1.0.0",
    ) = StoryCatalogItem(
        id = id,
        packId = packId,
        packVersion = version,
        title = "Paris After Dark",
        description = "Bir gece.",
        author = "Charaly",
        sizeBytes = 2_400_000,
        sha256 = "a".repeat(64),
        minEngineVersion = "0.8.0",
        tags = listOf("aksiyon"),
        download = StoryPackDownloadMetadata(
            source = StoryPackDownloadSource.HTTP,
            url = "https://catalog.charaly.dev/packs/paris.charaly-pack",
            fileName = "paris.charaly-pack",
            sizeBytes = 2_400_000,
        ),
    )

    @Test
    fun `a catalog document is metadata and never a pack`() {
        val manifest = StoryCatalogManifest(version = "7", items = listOf(item()))
        val json = StoryPackFormat.manifestJson.encodeToString(StoryCatalogManifest.serializer(), manifest)
        val parsed = StoryCatalogManifest.parse(json)!!

        assertEquals("7", parsed.version)
        assertEquals("pack.paris.afterdark", parsed.items.first().packId)
        assertTrue(parsed.items.first().isDownloadable)
        assertTrue("a listing is a promise, not content", !json.contains("characters.json"))
    }

    @Test
    fun `parsing is strict about the envelope and forgiving about the entries`() {
        assertNull(StoryCatalogManifest.parse("not json"))
        assertNull(StoryCatalogManifest.parse("""{"version":"1","items":[]}"""))

        val mixed = """
            {"version":"1","generatedAt":"now","items":[
              {"id":"good","packId":"p","packVersion":"1.0.0","title":"Fine"},
              {"id":"broken","packVersion":"missing a title"}
            ]}
        """.trimIndent()
        val parsed = StoryCatalogManifest.parse(mixed)!!
        assertEquals("one bad listing does not blank the shop window", listOf("good"), parsed.ids)
    }

    @Test
    fun `remote refresh degrades to the built-in listings and says why`() = runBlocking {
        val fallback = BuiltInStoryCatalog(listOf(item(id = "entry.builtin")))
        val transport = FakeHttpTransport(text = mapOf("https://c/m.json" to HttpTextResponse(500, "")))
        val catalog = RemoteStoryCatalog(transport, "https://c/m.json", fallback)

        val source = catalog.refresh()

        assertEquals(StoryCatalogSource.Fallback("katalog sunucusu 500 yanıtı verdi"), source)
        assertEquals(listOf("entry.builtin"), catalog.items.map { it.id })
        assertEquals(
            "Çevrimdışı katalog · katalog sunucusu 500 yanıtı verdi",
            storyCatalogSourceNote(source),
        )
    }

    @Test
    fun `an unreachable catalog is not an error`() = runBlocking {
        val fallback = BuiltInStoryCatalog(listOf(item(id = "entry.builtin")))
        val catalog = RemoteStoryCatalog(FakeHttpTransport.offline(), "https://c/m.json", fallback)
        val source = catalog.refresh()
        assertTrue(source is StoryCatalogSource.Fallback)
        assertEquals(1, catalog.items.size)
    }

    @Test
    fun `remote listings win on a shared id and built-ins fill the gaps`() = runBlocking {
        val fallback = BuiltInStoryCatalog(
            listOf(
                item(id = "entry.shared", packId = "pack.a"),
                item(id = "entry.builtin", packId = "pack.b"),
            ),
        )
        val remoteManifest = StoryCatalogManifest(
            version = "3",
            items = listOf(item(id = "entry.shared", packId = "pack.a").copy(title = "Yeni Başlık")),
        )
        val body = StoryPackFormat.manifestJson.encodeToString(StoryCatalogManifest.serializer(), remoteManifest)
        val catalog = RemoteStoryCatalog(
            FakeHttpTransport(text = mapOf("https://c/m.json" to HttpTextResponse(200, body))),
            "https://c/m.json",
            fallback,
        )

        val source = catalog.refresh()
        assertEquals(StoryCatalogSource.Remote("3", 1), source)
        assertEquals("Yeni Başlık", catalog.byId("entry.shared")!!.title)
        assertNotNull(catalog.byId("entry.builtin"))
        assertEquals("Çevrimiçi katalog · 1 paket", storyCatalogSourceNote(source))
    }

    @Test
    fun `a catalog knows the newest listing of a pack`() {
        val catalog = BuiltInStoryCatalog(
            listOf(
                item(id = "old", version = "1.1.0"),
                item(id = "new", version = "1.10.0"),
                item(id = "mid", version = "1.2.0"),
                item(id = "other", packId = "pack.other", version = "9.9.9"),
            ),
        )
        assertEquals("new", catalog.forPack("pack.paris.afterdark")!!.id)
        assertEquals("other", catalog.forPack("pack.other")!!.id)
    }

    @Test
    fun `size labels are honest about missing sizes`() {
        assertEquals("boyut belirtilmemiş", item().copy(sizeBytes = 0).sizeLabel)
        assertTrue(item().copy(sizeBytes = 2_400_000).sizeLabel.contains("MB"))
        assertEquals("2.4 GB", StoryCatalogItem.formatBytes(2_400_000_000))
    }

    @Test
    fun `the built-in catalog can present itself as a manifest document`() {
        val fallback = BuiltInStoryCatalog(listOf(item()))
        val json = RemoteStoryCatalog.builtinManifestJson(fallback)
        val parsed = StoryCatalogManifest.parse(json)!!
        assertEquals("builtin", parsed.version)
        assertEquals(1, parsed.items.size)
    }

    @Test
    fun `a listing without a download source is visible but not downloadable`() {
        val item = item().copy(download = null)
        assertFalse(item.isDownloadable)
        val catalog = BuiltInStoryCatalog(listOf(item))
        assertNull(catalog.byId("entry.paris.1")!!.download)
    }

    @Test
    fun `an untouched catalog reports nothing rather than lying about source`() {
        val catalog = RemoteStoryCatalog(FakeHttpTransport(), "https://c/m.json", BuiltInStoryCatalog.EMPTY)
        assertEquals(StoryCatalogSource.NotLoaded, catalog.catalogSource)
        assertEquals("", storyCatalogSourceNote(catalog.catalogSource))
    }
}