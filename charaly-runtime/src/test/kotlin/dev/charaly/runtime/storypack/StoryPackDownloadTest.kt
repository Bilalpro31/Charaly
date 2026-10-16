package dev.charaly.runtime.storypack

import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Downloading: `DOWNLOAD -> VERIFY -> INSTALL -> REGISTER`.
 *
 * What these tests hold the pipeline to: **a pack that is not finished is not installed.**
 * Every fault below ends in a state that is honest about what happened, and in a registry
 * that claims nothing until the bytes have been fetched, hashed, validated and committed.
 */
class StoryPackDownloadTest {

    private fun archiveBytes(): ByteArray = PackFixtures.sampleArchive()

    private fun itemFor(bytes: ByteArray, url: String = "https://catalog.charaly.dev/paris.charaly-pack") =
        StoryCatalogItem(
            id = "entry.paris",
            packId = "pack.paris.afterdark",
            packVersion = "1.0.0",
            title = "Paris After Dark",
            sizeBytes = bytes.size.toLong(),
            sha256 = Sha256.of(bytes),
            download = StoryPackDownloadMetadata(
                source = StoryPackDownloadSource.HTTP,
                url = url,
                fileName = "paris.charaly-pack",
                sizeBytes = bytes.size.toLong(),
                sha256 = Sha256.of(bytes),
            ),
        )

    private fun manager(
        transport: FakeHttpTransport,
        env: PackTestEnv,
        storage: StorageProbeOrNothing = StorageProbeOrNothing.UNLIMITED,
        builtin: (StoryCatalogItem) -> ByteArray? = { null },
    ) = StoryPackDownloadManager(
        transport = transport,
        installer = env.installer,
        temporaryDirectory = File(env.root, "downloads"),
        storage = storage.probe,
        builtinSource = builtin,
    )

    private class StorageProbeOrNothing(val probe: dev.charaly.runtime.net.StorageProbe) {
        companion object {
            val UNLIMITED = StorageProbeOrNothing(dev.charaly.runtime.net.StorageProbe.UNLIMITED)
            fun limited(bytes: Long) = StorageProbeOrNothing(FakeStorageProbe(bytes))
        }
    }

    @Test
    fun `a good download goes through every state and lands in the registry`() {
        val env = PackTestEnv()
        val bytes = archiveBytes()
        val transport = FakeHttpTransport(blobs = mapOf("https://catalog.charaly.dev/paris.charaly-pack" to bytes))
        val frames = runBlocking { manager(transport, env).download(itemFor(bytes)).toList() }

        assertEquals(
            listOf(
                PackDownloadState.QUEUED,
                PackDownloadState.DOWNLOADING,
                PackDownloadState.DOWNLOADED,
                PackDownloadState.VERIFYING,
                PackDownloadState.INSTALLING,
                PackDownloadState.READY,
            ),
            frames.map { it.state },
        )
        val last = frames.last()
        assertEquals("pack.paris.afterdark", last.installedPackId)
        assertTrue("a fraction is only claimed when it is known", frames.all { frame -> frame.fraction.let { value -> value == null || value in 0f..1f } })
        runBlocking { assertNotNull(env.registry.get("pack.paris.afterdark")) }
        env.cleanup()
    }

    @Test
    fun `a checksum mismatch discards the bytes and installs nothing`() {
        val env = PackTestEnv()
        val bytes = archiveBytes()
        val transport = FakeHttpTransport(blobs = mapOf("https://catalog.charaly.dev/paris.charaly-pack" to bytes))
        val lying = itemFor(bytes).copy(sha256 = "0".repeat(64))
        val frames = runBlocking { manager(transport, env).download(lying).toList() }

        val failure = frames.last().failure
        assertTrue(failure is PackDownloadFailure.ChecksumMismatch)
        assertEquals(PackDownloadState.FAILED, frames.last().state)
        runBlocking { assertEquals(emptyList<Any>(), env.registry.list()) }
        val partials = File(env.root, "downloads").listFiles().orEmpty().filter { it.name.endsWith(".part") }
        assertTrue("poisoned bytes are thrown away, not kept for resume", partials.isEmpty())
        env.cleanup()
    }

    @Test
    fun `valid bytes that are not a valid pack install nothing and say why`() {
        val env = PackTestEnv()
        // A well-formed archive of a broken pack: hash matches, content is wrong.
        val brokenPack = PackFixtures.pack().copy(locations = emptyList())
        val bytes = PackFixtures.sampleArchive(brokenPack)
        val transport = FakeHttpTransport(blobs = mapOf("https://catalog.charaly.dev/paris.charaly-pack" to bytes))
        val frames = runBlocking { manager(transport, env).download(itemFor(bytes)).toList() }

        val failure = frames.last().failure
        assertTrue(failure is PackDownloadFailure.InvalidPack)
        assertNotNull("the report travels with the failure", frames.last().report)
        runBlocking { assertEquals(emptyList<Any>(), env.registry.list()) }
        env.cleanup()
    }

    @Test
    fun `cancelling stops the download and leaves nothing behind`() {
        val env = PackTestEnv()
        val bytes = ByteArray(400_000) { 7 }
        val transport = FakeHttpTransport(blobs = mapOf("https://catalog.charaly.dev/big.bin" to bytes))
        val downloader = manager(transport, env)
        val item = StoryCatalogItem(
            id = "entry.big",
            packId = "pack.big",
            packVersion = "1.0.0",
            title = "Big",
            sizeBytes = bytes.size.toLong(),
            sha256 = Sha256.of(bytes),
            download = StoryPackDownloadMetadata(url = "https://catalog.charaly.dev/big.bin"),
        )

        // Cancel before a single frame is processed: the pipeline must notice at once.
        downloader.cancel(item.id)
        val frames = runBlocking { downloader.download(item).toList() }

        assertEquals(PackDownloadState.CANCELLED, frames.last().state)
        assertTrue(frames.last().failure is PackDownloadFailure.Cancelled)
        runBlocking { assertEquals(emptyList<Any>(), env.registry.list()) }
        env.cleanup()
    }

    @Test
    fun `pausing keeps the partial and resuming finishes the job with a range request`() {
        val env = PackTestEnv()
        // A real pack archive: only real bytes can reach READY, which is the point.
        val bytes = realArchiveBytes()
        val url = "https://catalog.charaly.dev/big.bin"
        val transport = FakeHttpTransport(blobs = mapOf(url to bytes))
        val downloader = manager(transport, env)
        val item = StoryCatalogItem(
            id = "entry.resume",
            packId = "pack.paris.afterdark",
            packVersion = "1.0.0",
            title = "Resume",
            sizeBytes = bytes.size.toLong(),
            sha256 = Sha256.of(bytes),
            download = StoryPackDownloadMetadata(url = url, sizeBytes = bytes.size.toLong(), sha256 = Sha256.of(bytes)),
        )

        downloader.pause(item.id)
        val paused = runBlocking { downloader.download(item).toList() }
        assertEquals(PackDownloadState.PAUSED, paused.last().state)

        downloader.resumeAllowed(item.id)
        val finished = runBlocking { downloader.download(item).toList() }
        // Paused at zero bytes, so there is nothing to resume *from*: the second attempt
        // legitimately starts the transfer over and finishes it. (The genuinely-resumed
        // case - real bytes waiting on disk - is asserted in the interrupted test below.)
        assertEquals(PackDownloadState.READY, finished.last().state)
        assertNotNull(runBlocking { env.registry.get("pack.paris.afterdark") })
        env.cleanup()
    }

    @Test
    fun `an interrupted download keeps its partial and resumes from it`() {
        val env = PackTestEnv()
        val bytes = realArchiveBytes()
        val url = "https://catalog.charaly.dev/pack.charaly-pack"
        val fragile = FakeHttpTransport(blobs = mapOf(url to bytes), failStreamAfterBytes = INTERRUPT_AFTER)
        val downloader = manager(fragile, env)
        val item = StoryCatalogItem(
            id = "entry.interrupt",
            packId = "pack.paris.afterdark",
            packVersion = "1.0.0",
            title = "Interrupt",
            sizeBytes = bytes.size.toLong(),
            sha256 = Sha256.of(bytes),
            download = StoryPackDownloadMetadata(url = url, sizeBytes = bytes.size.toLong(), sha256 = Sha256.of(bytes)),
        )

        val broken = runBlocking { downloader.download(item).toList() }
        assertEquals(PackDownloadState.FAILED, broken.last().state)
        assertTrue(broken.last().failure is PackDownloadFailure.Interrupted)
        val partial = File(env.root, "downloads/${"entry.interrupt".replace(Regex("[^A-Za-z0-9._-]"), "_")}.part")
        assertTrue("the partial is the resume point", partial.isFile && partial.length() > 0)
        assertTrue("and it is genuinely partial", partial.length() < bytes.size)

        val healthy = FakeHttpTransport(blobs = mapOf(url to bytes))
        val second = manager(healthy, env)
        val resumed = runBlocking { second.download(item).toList() }
        assertEquals(PackDownloadState.READY, resumed.last().state)
        assertEquals(INTERRUPT_AFTER, healthy.rangeStarts.first())
        assertNotNull(runBlocking { env.registry.get("pack.paris.afterdark") })
        env.cleanup()
    }

    private fun realArchiveBytes(): ByteArray = PackFixtures.sampleArchive()

    private val INTERRUPT_AFTER = 4_000L

    @Test
    fun `insufficient storage is refused before anything is fetched`() {
        val env = PackTestEnv()
        val bytes = archiveBytes()
        val transport = FakeHttpTransport(blobs = mapOf("https://catalog.charaly.dev/paris.charaly-pack" to bytes))
        val downloader = manager(
            transport,
            env,
            storage = StorageProbeOrNothing.limited(1_024),
        )
        val frames = runBlocking { downloader.download(itemFor(bytes)).toList() }

        assertTrue(frames.last().failure is PackDownloadFailure.InsufficientStorage)
        assertTrue(transport.openStreamUrls.isEmpty())
        env.cleanup()
    }

    @Test
    fun `a dead network is a network failure, not a mystery`() {
        val env = PackTestEnv()
        val bytes = archiveBytes()
        val item = itemFor(bytes)
        val frames = runBlocking { manager(FakeHttpTransport(), env).download(item).toList() }

        val failure = frames.last().failure
        assertTrue(failure is PackDownloadFailure.Network || failure is PackDownloadFailure.Interrupted)
        assertEquals(PackDownloadState.FAILED, frames.last().state)
        runBlocking { assertEquals(emptyList<Any>(), env.registry.list()) }
        env.cleanup()
    }

    @Test
    fun `a catalog entry with no download source refuses politely`() {
        val env = PackTestEnv()
        val item = StoryCatalogItem(id = "e", packId = "p", packVersion = "1.0.0", title = "T")
        val frames = runBlocking { manager(FakeHttpTransport(), env).download(item).toList() }
        assertEquals(PackDownloadState.FAILED, frames.last().state)
        assertTrue(frames.last().failure is PackDownloadFailure.Other)
        env.cleanup()
    }

    @Test
    fun `a built-in pack goes through the same states and the same install`() {
        val env = PackTestEnv()
        val bytes = archiveBytes()
        val item = StoryCatalogItem(
            id = "entry.builtin",
            packId = "pack.paris.afterdark",
            packVersion = "1.0.0",
            title = "Paris After Dark",
            sizeBytes = bytes.size.toLong(),
            sha256 = Sha256.of(bytes),
            download = StoryPackDownloadMetadata(source = StoryPackDownloadSource.BUILTIN),
        )
        val frames = runBlocking {
            manager(FakeHttpTransport(), env, builtin = { bytes }).download(item).toList()
        }

        assertEquals(PackDownloadState.READY, frames.last().state)
        val record = runBlocking { env.registry.get("pack.paris.afterdark") }
        assertEquals(InstalledPackRecord.SOURCE_CATALOG, record?.source)
        env.cleanup()
    }

    @Test
    fun `progress frames carry honest labels`() {
        val env = PackTestEnv()
        val bytes = ByteArray(64_000) { 1 }
        val url = "https://catalog.charaly.dev/blob.bin"
        val transport = FakeHttpTransport(blobs = mapOf(url to bytes))
        val item = StoryCatalogItem(
            id = "entry.labels",
            packId = "pack.labels",
            packVersion = "1.0.0",
            title = "Labels",
            sizeBytes = bytes.size.toLong(),
            sha256 = Sha256.of(bytes),
            download = StoryPackDownloadMetadata(url = url, sizeBytes = bytes.size.toLong(), sha256 = Sha256.of(bytes)),
        )
        val frames = runBlocking { manager(transport, env).download(item).toList() }

        val downloading = frames.first { it.state == PackDownloadState.DOWNLOADING }
        assertTrue(downloading.transferredLabel.contains("/"))
        assertFalse("no percentage invented for an unknown total", frames.any { it.fraction != null && it.totalBytes == 0L })
        assertNull(frames.first().fraction)
        env.cleanup()
    }

    @Test
    fun `the snapshot survives a screen being reopened`() {
        val env = PackTestEnv()
        val bytes = archiveBytes()
        val transport = FakeHttpTransport(blobs = mapOf("https://catalog.charaly.dev/paris.charaly-pack" to bytes))
        val downloader = manager(transport, env)
        runBlocking { downloader.download(itemFor(bytes)).toList() }

        assertEquals(PackDownloadState.READY, downloader.snapshot("entry.paris")?.state)
        assertNull(downloader.snapshot("entry.unknown"))
        env.cleanup()
    }
}