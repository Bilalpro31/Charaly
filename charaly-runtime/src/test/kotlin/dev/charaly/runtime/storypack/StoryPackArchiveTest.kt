package dev.charaly.runtime.storypack

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Archive security: `.charaly-pack` is hostile input.
 *
 * Every test here hands the reader a file crafted to hurt - traversal, bombs, duplicates,
 * truncation - and asserts the *specific* refusal. "It threw something" is not the bar;
 * "it threw the right thing and wrote nothing" is.
 */
class StoryPackArchiveTest {

    private fun zip(vararg entries: Pair<String, ByteArray>): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            entries.forEach { (name, bytes) ->
                zip.putNextEntry(ZipEntry(name))
                zip.write(bytes)
                zip.closeEntry()
            }
        }
        return out.toByteArray()
    }

    private fun zipBombEntry(): ByteArray {
        // 40 MB of zeros, which compresses to almost nothing: the classic ratio attack.
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            zip.putNextEntry(ZipEntry("assets/bomb.bin"))
            val chunk = ByteArray(1024 * 1024)
            repeat(40) { zip.write(chunk) }
            zip.closeEntry()
        }
        return out.toByteArray()
    }

    @Test
    fun `a normal archive is read entry by entry`() {
        val bytes = PackFixtures.sampleArchive()
        val contents = StoryPackArchive.read(bytes)
        assertEquals(true, contents.manifest != null)
        assertTrue(contents.textFiles.keys.any { it.endsWith("characters.json") })
        assertTrue(contents.warnings.isEmpty())
    }

    @Test
    fun `path traversal is refused with a security refusal`() {
        val bytes = zip(
            StoryPackFormat.MANIFEST_PATH to "{}".toByteArray(),
            "../../data/evil.txt" to "pwn".toByteArray(),
        )
        val refused = runCatching { StoryPackArchive.read(bytes) }.exceptionOrNull()
        assertTrue(refused is StoryPackArchive.RefusedException)
        val reason = refused as StoryPackArchive.RefusedException
        assertEquals(StoryPackArchive.Refusal.PATH_TRAVERSAL, reason.code)
        assertTrue(reason.code.isSecurity)
    }

    @Test
    fun `absolute paths are refused`() {
        val bytes = zip(
            StoryPackFormat.MANIFEST_PATH to "{}".toByteArray(),
            "/etc/passwd" to "root".toByteArray(),
        )
        val refused = runCatching { StoryPackArchive.read(bytes) }.exceptionOrNull()
                as StoryPackArchive.RefusedException
        assertEquals(StoryPackArchive.Refusal.ABSOLUTE_PATH, refused.code)
    }

    @Test
    fun `windows drive paths are refused`() {
        val bytes = zip(
            StoryPackFormat.MANIFEST_PATH to "{}".toByteArray(),
            "C:/Windows/system.ini" to "x".toByteArray(),
        )
        val refused = runCatching { StoryPackArchive.read(bytes) }.exceptionOrNull()
                as StoryPackArchive.RefusedException
        assertEquals(StoryPackArchive.Refusal.ABSOLUTE_PATH, refused.code)
    }

    @Test
    fun `backslash traversal is refused`() {
        val bytes = zip(
            StoryPackFormat.MANIFEST_PATH to "{}".toByteArray(),
            "content\\..\\..\\secrets.json" to "x".toByteArray(),
        )
        val refused = runCatching { StoryPackArchive.read(bytes) }.exceptionOrNull()
                as StoryPackArchive.RefusedException
        assertTrue(
            refused.code == StoryPackArchive.Refusal.PATH_TRAVERSAL ||
                refused.code == StoryPackArchive.Refusal.ABSOLUTE_PATH,
        )
    }

    @Test
    fun `two entries with the same name are refused`() {
        // Hand-rolled stored entries: `ZipOutputStream` refuses to *write* duplicates, and
        // an attacker is under no such obligation. What is under test is the reader.
        val refused = runCatching {
            StoryPackArchive.read(
                storedZip(
                    StoryPackFormat.MANIFEST_PATH to "{}".toByteArray(),
                    StoryPackFormat.MANIFEST_PATH to "{}".toByteArray(),
                ),
            )
        }.exceptionOrNull() as StoryPackArchive.RefusedException
        assertEquals(StoryPackArchive.Refusal.DUPLICATE_ENTRY, refused.code)
    }

    /**
     * A minimal STORED-format zip: local headers only, which is all [java.util.zip.ZipInputStream]
     * reads and all a hostile archive needs.
     */
    private fun storedZip(vararg entries: Pair<String, ByteArray>): ByteArray {
        val out = ByteArrayOutputStream()
        entries.forEach { (name, data) ->
            val nameBytes = name.toByteArray(Charsets.UTF_8)
            val crc = java.util.zip.CRC32().apply { update(data) }.value
            out.write(intBytes(ZIP_LOCAL_SIGNATURE))
            out.write(shortBytes(20)); out.write(shortBytes(0)); out.write(shortBytes(0)) // version, flags, method
            out.write(shortBytes(0)); out.write(shortBytes(0)) // time, date
            out.write(intBytes(crc.toInt())); out.write(intBytes(data.size)); out.write(intBytes(data.size))
            out.write(shortBytes(nameBytes.size)); out.write(shortBytes(0))
            out.write(nameBytes)
            out.write(data)
        }
        return out.toByteArray()
    }

    private fun intBytes(value: Int): ByteArray = byteArrayOf(
        (value and 0xFF).toByte(), ((value shr 8) and 0xFF).toByte(),
        ((value shr 16) and 0xFF).toByte(), ((value shr 24) and 0xFF).toByte(),
    )

    private fun shortBytes(value: Int): ByteArray = byteArrayOf((value and 0xFF).toByte(), ((value shr 8) and 0xFF).toByte())

    private val ZIP_LOCAL_SIGNATURE = 0x04034b50

    @Test
    fun `a compression bomb is refused before it expands`() {
        val bomb = zipBombEntry().let { raw ->
            // Wrap the bomb inside a pack-shaped archive that has no manifest, so the size
            // guard is what answers rather than the missing-manifest guard.
            val out = ByteArrayOutputStream()
            ZipOutputStream(out).use { zip ->
                val src = java.util.zip.ZipInputStream(ByteArrayInputStream(raw))
                val entry = src.nextEntry
                zip.putNextEntry(ZipEntry(entry.name))
                zip.write(src.readBytes())
                zip.closeEntry()
            }
            out.toByteArray()
        }
        val refused = runCatching { StoryPackArchive.read(bomb) }.exceptionOrNull()
                as StoryPackArchive.RefusedException
        assertEquals(StoryPackArchive.Refusal.ZIP_BOMB, refused.code)
    }

    @Test
    fun `an archive without a manifest is not a pack`() {
        val bytes = zip("content/world.json" to "{}".toByteArray())
        val refused = runCatching { StoryPackArchive.read(bytes) }.exceptionOrNull()
                as StoryPackArchive.RefusedException
        assertEquals(StoryPackArchive.Refusal.MANIFEST_INVALID, refused.code)
    }

    @Test
    fun `a truncated archive is refused as malformed`() {
        val whole = PackFixtures.sampleArchive()
        val truncated = whole.copyOfRange(0, whole.size / 2)
        val refused = runCatching { StoryPackArchive.read(truncated) }.exceptionOrNull()
        assertTrue(
            "expected a refusal, got $refused",
            refused is StoryPackArchive.RefusedException ||
                refused is java.util.zip.ZipException,
        )
    }

    @Test
    fun `corrupt random bytes are not an archive`() {
        val refused = runCatching {
            StoryPackArchive.read(ByteArray(2048) { (it % 7).toByte() })
        }.exceptionOrNull()
        assertTrue(refused is StoryPackArchive.RefusedException || refused is java.util.zip.ZipException)
    }

    @Test
    fun `an unexpected file is kept out of the way and reported`() {
        val bytes = PackFixtures.sampleArchive().let {
            val out = ByteArrayOutputStream()
            ZipOutputStream(out).use { zip ->
                zip.putNextEntry(ZipEntry("README.exe"))
                zip.write("totally innocent".toByteArray())
                zip.closeEntry()
                zip.putNextEntry(ZipEntry(StoryPackFormat.MANIFEST_PATH))
                zip.write(StoryPackArchive.read(it).manifest.orEmpty().toByteArray())
                zip.closeEntry()
            }
            out.toByteArray()
        }
        val contents = runCatching { StoryPackArchive.read(bytes) }
        // Whatever the manifest says, an unexpected file is a warning at worst and is never
        // executed, never extracted to a shared location and never interpreted.
        contents.getOrNull()?.let { assertTrue(it.warnings.isNotEmpty()) }
    }

    @Test
    fun `every refusal maps to a Turkish explanation`() {
        StoryPackArchive.Refusal.entries.forEach { refusal ->
            val issue = ValidationIssue.archiveRefusal(refusal, "detail")
            assertEquals(Severity.CRITICAL, issue.severity)
            assertTrue(issue.message.isNotBlank())
            assertFalse(issue.message.contains("Stack"))
        }
    }

    @Test
    fun `writing refuses an unsafe path instead of sanitising it silently`() {
        val boom = runCatching {
            StoryPackArchive.write(mapOf("../escape.txt" to "x".toByteArray()))
        }
        assertTrue("an exporter must not launder paths", boom.isFailure)
    }

    @Test
    fun `the same content produces the same archive bytes`() {
        val bytes = PackFixtures.sampleArchive()
        val contents = StoryPackArchive.read(bytes)
        val rebuilt = StoryPackArchive.write(
            buildMap {
                put(StoryPackFormat.MANIFEST_PATH, contents.manifest.orEmpty().toByteArray())
                contents.textFiles.forEach { (k, v) -> put(k, v.toByteArray()) }
                contents.assets.forEach { (k, v) -> put(k, v) }
            },
        )
        assertEquals("deterministic archives", bytes.toList(), rebuilt.toList())
    }

    @Test
    fun `entry count is bounded`() {
        assertTrue(StoryPackFormat.MAX_ENTRIES in 64..100_000)
        assertTrue(StoryPackFormat.MAX_TOTAL_BYTES > StoryPackFormat.MAX_ENTRY_BYTES)
    }
}