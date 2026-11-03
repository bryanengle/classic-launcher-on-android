package app.gamenative.ui.screen.wow

import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okio.Buffer
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest

/**
 * World of Warcraft: Forever replaced the Classic beta: a beta install is upgraded in place (same Data/ archives), a new one is set up
 * without a PC. The fake CDN serves the real download path (configs, install and encoding manifests, archive index).
 */
class WowClassicEraInstallTest {
    @get:Rule val temp = TemporaryFolder()
    private fun md5(b: ByteArray) = MessageDigest.getInstance("MD5").digest(b).joinToString("") { "%02x".format(it.toInt() and 255) }
    private fun unhex(s: String) = s.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
    private fun blte(b: ByteArray) = byteArrayOf(66, 76, 84, 69, 0, 0, 0, 0, 78) + b
    private fun install(files: Map<String, ByteArray>): ByteArray = ByteArrayOutputStream().also { stream ->
        DataOutputStream(stream).use { d ->
            d.write(byteArrayOf(73, 78, 1, 16)); d.writeShort(2); d.writeInt(files.size)
            for (tag in listOf("Windows", "arm64")) {
                d.writeBytes(tag); d.writeByte(0); d.writeShort(0)
                repeat((files.size + 7) / 8) { d.writeByte(0xff) }
            }
            files.forEach { (name, bytes) -> d.writeBytes(name); d.writeByte(0); d.write(unhex(md5(bytes))); d.writeInt(bytes.size) }
        }
    }.toByteArray()
    private fun encoding(files: Map<String, ByteArray>): ByteArray {
        val unique = files.values.associateBy { md5(it) }.toSortedMap()
        val decoded = ByteArray(22 + 32 + 1024)
        val b = ByteBuffer.wrap(decoded).order(ByteOrder.BIG_ENDIAN)
        decoded[0] = 69; decoded[1] = 78; decoded[2] = 1; decoded[3] = 16; decoded[4] = 16
        b.putShort(5, 1); b.putInt(9, 1); unhex(unique.firstKey()).copyInto(decoded, 22)
        var offset = 54
        unique.forEach { (ckey, bytes) ->
            decoded[offset] = 1; unhex(ckey).copyInto(decoded, offset + 6)
            unhex(md5(blte(bytes))).copyInto(decoded, offset + 22); offset += 38
        }
        val header = ByteArray(36); val h = ByteBuffer.wrap(header).order(ByteOrder.BIG_ENDIAN)
        h.putInt(0, 0x424c5445); h.putInt(4, 36); h.putInt(8, 0x0f000001)
        h.putInt(12, decoded.size + 1); h.putInt(16, decoded.size)
        return header + byteArrayOf(78) + decoded
    }

    private class FakeCdn(val server: MockWebServer, val build: String, val cdn: String, val archiveKey: String, val client: ByteArray)

    private fun fakeCdn(client: ByteArray = "full release ARM64 client".toByteArray()): FakeCdn {
        val files = linkedMapOf("WowClassic-arm64.exe" to client)
        val installKey = "c".repeat(32); val encodingKey = "d".repeat(32); val archiveKey = "e".repeat(32)
        val buildBytes = "install = $installKey $installKey\nencoding = $encodingKey $encodingKey\n".toByteArray()
        val cdnBytes = "archives = $archiveKey\n".toByteArray()
        val build = md5(buildBytes); val cdn = md5(cdnBytes)
        val payloads = mapOf(build to buildBytes, cdn to cdnBytes, installKey to blte(install(files)), encodingKey to encoding(files),
            md5(blte(client)) to blte(client), "$archiveKey.index" to ByteArray(4096))
        val server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val data = payloads[request.path!!.substringAfterLast('/')] ?: return MockResponse().setResponseCode(404)
                // HEAD (the size before a parallel ranged download): headers only, like Blizzard's CDN.
                if (request.method == "HEAD") return MockResponse().setResponseCode(200).setHeader("Content-Length", data.size)
                val range = request.getHeader("Range")?.removePrefix("bytes=")?.split('-')
                val body = if (range == null) data else data.copyOfRange(range[0].toInt(), range[1].toInt() + 1)
                return MockResponse().setResponseCode(if (range == null) 200 else 206).setBody(Buffer().write(body))
            }
        }
        server.start()
        return FakeCdn(server, build, cdn, archiveKey, client)
    }

    private fun target(f: FakeCdn) = WowClientDownloader.VersionCheckResult("", "1.15.9.70576", true, f.build, f.cdn,
        remoteCdnHosts = listOf("127.0.0.1:${f.server.port}"), remoteCdnPath = "tpr/wow",
        remoteCdnServers = listOf("http://level3.blizzard.com/?maxhosts=8", "https://us.cdn.blizzard.com/?maxhosts=4&fallback=1"))

    private fun rows(buildInfo: File): List<Map<String, String>> {
        val lines = buildInfo.readLines().filter { it.isNotBlank() }
        val header = lines.first().split("|").map { it.substringBefore("!") }
        return lines.drop(1).map { header.zip(it.split("|")).toMap() }
    }

    @Test fun eraInstallPreservesOtherProductsAndDoesNotImportBetaSettings() {
        val f = fakeCdn()
        try {
            val root = temp.newFolder()
            File(root, "Data/data/data.000").apply { parentFile!!.mkdirs(); writeText("shared archive") }
            File(root, "_classic_beta_/WowB-ARM64.exe").apply { parentFile!!.mkdirs(); writeText("beta client") }
            File(root, "_classic_beta_/WTF/Config.wtf").apply {
                parentFile!!.mkdirs()
                writeText("SET portal \"test\"\nSET agentUID \"wow_classic_beta\"\nSET CACHE-WQST-QuestCacheSize \"1\"\nSET farclip \"777\"\n")
            }
            // The Battle.net app's beta row; this header has no "CDN Servers" column.
            val betaRow = "eu|1|${f.build}|${f.cdn}|1.60.1.70532|wow_classic_beta"
            val info = File(root, ".build.info").apply { writeText("Branch!STRING:0|Active!DEC:1|Build Key!HEX:16|CDN Key!HEX:16|Version!STRING:0|Product!STRING:0\n$betaRow\n") }

            WowGameStatus.scan(root).let { assertTrue(it.wrongGame); assertFalse(it.executable) }

            WowClientDownloader.updateGame(root, target(f), {}, region = "eu")

            assertArrayEquals(f.client, File(root, "_classic_era_/WowClassic-arm64.exe").readBytes())
            assertTrue(File(root, "Data/indices/${f.archiveKey}.index").isFile)
            assertEquals("shared archive", File(root, "Data/data/data.000").readText())
            val all = rows(info)
            assertEquals(2, all.size)
            assertEquals("wow_classic_beta", all[0]["Product"]); assertEquals("1.60.1.70532", all[0]["Version"])
            val forever = all.single { it["Product"] == "wow_classic_era" }
            assertEquals("1", forever["Active"]); assertEquals("eu", forever["Branch"]); assertEquals("1.15.9.70576", forever["Version"])
            assertEquals("http://level3.blizzard.com/?maxhosts=8 https://us.cdn.blizzard.com/?maxhosts=4&fallback=1", forever["CDN Servers"])
            assertEquals("Windows arm64 EU? enUS speech?:Windows arm64 EU? enUS text?", forever["Tags"])
            WowGameStatus.scan(root).let { assertFalse(it.wrongGame); assertTrue(it.executable); assertEquals("wow_classic_era", it.product) }

            WowGameConfig.prepare(root)
            val config = File(root, "_classic_era_/WTF/Config.wtf").readText()
            assertTrue(config.contains("SET farclip \"1200\""))                       // beta settings are not imported
            assertTrue(config.contains("SET portal \"eu\"")); assertFalse(config.contains("\"test\""))
            assertFalse(config.contains("CACHE-")); assertTrue(config.contains("SET agentUID \"wow_classic_era\""))
            assertTrue(config.contains("SET showScreenNarrationDialog \"0\""))

            // Another region chosen later moves the branch and the portal with it.
            WowClientDownloader.upsertBuildInfo(info, target(f), "us")
            assertEquals("us", rows(info).single { it["Product"] == "wow_classic_era" }["Branch"])
            WowGameConfig.prepare(root)
            assertTrue(File(root, "_classic_era_/WTF/Config.wtf").readText().contains("SET portal \"us\""))
            // The region switch in Settings overrides the install's region for the next launch, without touching the install.
            WowGameConfig.prepare(root, region = "eu")
            assertTrue(File(root, "_classic_era_/WTF/Config.wtf").readText().contains("SET portal \"eu\""))
            assertEquals("us", rows(info).single { it["Product"] == "wow_classic_era" }["Branch"])
        } finally { f.server.shutdown() }
    }

    @Test fun anEmptyFolderBecomesAnInstallWithoutAPc() {
        val f = fakeCdn()
        try {
            val root = File(temp.newFolder(), "WoW Forever")
            File(root, "Data/indices").mkdirs()
            WowClientDownloader.updateGame(root, target(f), {}, region = "kr")
            val forever = rows(File(root, ".build.info")).single()
            assertEquals("wow_classic_era", forever["Product"]); assertEquals("kr", forever["Branch"]); assertEquals("1", forever["Active"])
            assertTrue(forever["CDN Servers"]!!.isNotBlank())
            assertTrue(WowGameStatus.scan(root).let { it.executable && it.buildInfoValid && !it.wrongGame })
            WowGameConfig.prepare(root)
            assertTrue(File(root, "_classic_era_/WTF/Config.wtf").readText().contains("SET portal \"kr\""))
        } finally { f.server.shutdown() }
    }

    @Test fun aLargeClientFileComesInParallelRangesWithSizeAndSpeedInTheStatus() {
        // 9 MB: above two 4 MB segments, so it is fetched as three ranged requests after a HEAD for its size.
        val client = ByteArray(9 * 1024 * 1024) { (it * 31 + 7).toByte() }
        val f = fakeCdn(client)
        try {
            val root = File(temp.newFolder(), "WoW Forever")
            File(root, "Data/indices").mkdirs()
            val statuses = java.util.Collections.synchronizedList(mutableListOf<String>())
            WowClientDownloader.updateGame(root, target(f), { statuses += it }, region = "eu")
            assertTrue(File(root, "_classic_era_/WowClassic-arm64.exe").readBytes().contentEquals(client))
            val ranged = (0 until f.server.requestCount).map { f.server.takeRequest() }.count { it.getHeader("Range") != null }
            assertTrue("ranged requests: $ranged", ranged >= 3)
            assertTrue(statuses.any { it.startsWith("Downloading WowClassic-arm64.exe (1/1): 9.0 / 9.0 MB") })
        } finally { f.server.shutdown() }
    }

    @Test fun theChosenGameLanguageReplacesTheFilesOneAndKeepLeavesIt() {
        val root = temp.newFolder()
        WowGameConfig.prepare(root)
        val config = File(root, "_classic_era_/WTF/Config.wtf")
        assertTrue(config.readText().contains("SET textLocale \"enUS\""))
        WowGameConfig.prepare(root, locale = "deDE")
        config.readText().let { assertTrue(it.contains("SET textLocale \"deDE\"")); assertTrue(it.contains("SET audioLocale \"deDE\"")); assertFalse(it.contains("enUS")) }
        WowGameConfig.prepare(root)                                                            // "keep the current one"
        assertTrue(config.readText().contains("SET textLocale \"deDE\""))
    }

    @Test fun theChosenGameAccountIsWhatWowSelects() {
        // A player's Config.wtf: WoW keeps only the account it used last, marked with "!". The choice used to change nothing there,
        // so WoW kept logging into the previous account.
        val played = listOf("SET gxApi \"D3D12\"", "SET accountList \"!WoW1|\"")
        assertEquals("SET accountList \"WoW1|!WoW2|\"", WowGameConfig.selectGameAccount(played, 2)[1])
        assertEquals(played, WowGameConfig.selectGameAccount(played, 1))
        assertEquals(played, WowGameConfig.selectGameAccount(played, null))
        val both = listOf("SET accountList \"WoW2|!WoW1|\"")
        assertEquals("SET accountList \"!WoW2|WoW1|\"", WowGameConfig.selectGameAccount(both, 2)[0])
        // Before the first login there is no list yet: the choice is written for WoW to pick up.
        assertEquals("SET accountList \"!WoW2|\"", WowGameConfig.selectGameAccount(listOf("SET gxApi \"D3D12\""), 2)[1])
        val odd = listOf("SET accountList \"WoW1||WoW2\"")
        assertEquals(odd, WowGameConfig.selectGameAccount(odd, 2))                             // unexpected format: untouched
    }
}
