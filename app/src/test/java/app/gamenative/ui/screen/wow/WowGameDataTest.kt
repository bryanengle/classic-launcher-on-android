package app.gamenative.ui.screen.wow

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer

@org.junit.runner.RunWith(org.robolectric.RobolectricTestRunner::class)
@org.robolectric.annotation.Config(application = android.app.Application::class, sdk = [29])
class WowGameDataTest {
    @get:Rule val temp = TemporaryFolder()

    /** A version-1 download manifest: 16-byte ekeys, 40-bit sizes, a priority byte, no checksum; masks are big-endian bits. */
    private fun manifest(sizes: List<Long>, tags: List<Triple<String, Int, Set<Int>>>): ByteArray {
        val out = ByteArrayOutputStream()
        out.write('D'.code); out.write('L'.code); out.write(1); out.write(16); out.write(0)
        out.write(ByteBuffer.allocate(4).putInt(sizes.size).array())
        out.write(ByteBuffer.allocate(2).putShort(tags.size.toShort()).array())
        sizes.forEachIndexed { i, size ->
            out.write(ByteArray(16) { i.toByte() })
            out.write((size ushr 32).toInt()); out.write(ByteBuffer.allocate(4).putInt((size and 0xFFFFFFFFL).toInt()).array())
            out.write(1)
        }
        val maskSize = (sizes.size + 7) / 8
        tags.forEach { (name, type, entries) ->
            out.write(name.toByteArray()); out.write(0)
            out.write(ByteBuffer.allocate(2).putShort(type.toShort()).array())
            val mask = ByteArray(maskSize)
            entries.forEach { mask[it / 8] = (mask[it / 8].toInt() or (0x80 ushr (it % 8))).toByte() }
            out.write(mask)
        }
        return out.toByteArray()
    }

    @Test fun downloadManifestSumsOnlyTheSelectedPlatformArchitectureAndLanguage() {
        val big = 6L * 1024 * 1024 * 1024 // above 32 bits: the fifth size byte counts
        val data = manifest(listOf(big, 200, 300, 400, 500, 600, 700, 800, 900),
            listOf(Triple("Windows", 1, setOf(0, 1, 2, 3, 5, 6, 7, 8)), Triple("OSX", 1, setOf(4)),
                Triple("arm64", 2, setOf(0, 2, 3, 5, 6, 7, 8)), Triple("x86_64", 2, setOf(1)),
                Triple("enUS", 3, setOf(0, 1, 2, 4, 5, 6, 7, 8)), Triple("deDE", 3, setOf(3)),
                Triple("HighRes", 16384, setOf(8))))
        // Fed in odd pieces, as a BLTE stream delivers it.
        val counter = WowClientDownloader.DownloadManifestCounter(setOf("Windows", "arm64", "enUS", "EU", "speech", "text"))
        var pos = 0
        val step = 7
        while (pos < data.size) { val n = minOf(step, data.size - pos); counter.write(data, pos, n); pos += n }
        // Entries 0, 2, 5, 6, 7: Windows + arm64 + enUS; entry 8 is HighRes, optional content left out like Battle.net does.
        assertEquals(big + 300 + 600 + 700 + 800, counter.total())
    }

    @Test fun progressComesFromTheGameDataFiles() {
        val root = temp.newFolder()
        File(root, "Data/data").mkdirs()
        File(root, "Data/data/data.000").writeBytes(ByteArray(3000))
        File(root, "Data/data/data.001").writeBytes(ByteArray(1000))
        File(root, "Data/data/0000000001.idx").writeBytes(ByteArray(500)) // indexes are not game data
        assertEquals(4000L, WowGameData.downloaded(root))
        val p = WowGameData.Progress(4000, 16000)
        assertEquals(25, p.percent); assertFalse(p.complete)
        assertTrue(WowGameData.Progress(15990, 16000).complete)
    }

    @Test fun httpsGoesToBlizzardsSslHostForLevel3() {
        // HTTPS straight to level3.blizzard.com measured 2.5 MB/s and broke off; level3.ssl.blizzard.com gave 10.5 MB/s.
        assertEquals("level3.ssl.blizzard.com", WowClientDownloader.httpsHost("level3.blizzard.com"))
        assertEquals("us.cdn.blizzard.com", WowClientDownloader.httpsHost("us.cdn.blizzard.com"))
        // The region's own Blizzard CDN goes first: 15.6 MB/s on the phone against 2.5-8.5 MB/s through level3.
        assertEquals("eu.cdn.blizzard.com", WowClientDownloader.regionalCdn("EU"))
        assertEquals("us.cdn.blizzard.com", WowClientDownloader.regionalCdn("tw"))
        assertEquals(null, WowClientDownloader.regionalCdn(null))
    }

    @Test fun downloaderProgressShowsInHungarianToo() {
        val dir = listOf(File("src/main/assets/i18n"), File("app/src/main/assets/i18n")).first { it.isDirectory }
        WowI18n.install("hu", File(dir, "hu.json").readText())
        val (en, hu) = WowClientDownloader.Text.downloading("WowClassic-arm64.exe", 1, 3, "12.5", "80.1", "6.4")
        assertEquals(hu, WowI18n.translate("hu", en))
        val (enIdx, huIdx) = WowClientDownloader.Text.indexes(733, 1401, "3.2")
        assertEquals(huIdx, WowI18n.translate("hu", enIdx))
    }
}
