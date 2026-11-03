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
import java.util.Collections

/** Exercises the production CDN/BLTE/install/encoding/archive path using a local HTTP server. */
class WowVoiceRepairCdnTest {
    @get:Rule val temp = TemporaryFolder()
    private fun md5(b: ByteArray) = MessageDigest.getInstance("MD5").digest(b).joinToString("") { "%02x".format(it.toInt() and 255) }
    private fun unhex(s: String) = s.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
    private fun blte(b: ByteArray) = byteArrayOf(66, 76, 84, 69, 0, 0, 0, 0, 78) + b
    private fun pe(dll: Boolean, salt: Int): ByteArray = ByteArray(512).also {
        val b = ByteBuffer.wrap(it).order(ByteOrder.LITTLE_ENDIAN)
        b.putShort(0, 0x5a4d.toShort()); it[5] = salt.toByte(); b.putInt(60, 64); b.putInt(64, 0x4550)
        b.putShort(68, 0xaa64.toShort()); b.putShort(70, 1); b.putShort(84, 240)
        b.putShort(86, (if (dll) 0x2022 else 0x22).toShort()); b.putShort(88, 0x20b)
    }
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
    @Test fun repairUsesInstalledBuildAndArchivesWithoutUpdatingGameAndIsIdempotent() {
        val files = linkedMapOf("Utils\\WowVoiceProxyT.exe" to pe(false, 1), "Utils\\vivoxsdk.dll" to pe(true, 2),
            "Utils\\discord_partner_sdk.dll" to pe(true, 3), "WowClassic-arm64.exe" to pe(false, 4))
        val installKey = "c".repeat(32)
        val encodingKey = "d".repeat(32); val archiveKey = "e".repeat(32)
        // Config files are named after their own MD5; the downloader verifies that.
        val buildBytes = "install = $installKey $installKey\nencoding = $encodingKey $encodingKey\n".toByteArray()
        val cdnBytes = "archives = $archiveKey\n".toByteArray()
        val build = md5(buildBytes); val cdn = md5(cdnBytes)
        val index = ByteArray(4096); val archive = ByteArrayOutputStream()
        val ib = ByteBuffer.wrap(index).order(ByteOrder.BIG_ENDIAN)
        files.values.forEachIndexed { i, bytes ->
            val encoded = blte(bytes); unhex(md5(encoded)).copyInto(index, i * 24)
            ib.putInt(i * 24 + 16, encoded.size); ib.putInt(i * 24 + 20, archive.size()); archive.write(encoded)
        }
        val payloads = mapOf(build to buildBytes,
            cdn to cdnBytes, installKey to blte(install(files)), encodingKey to encoding(files),
            "$archiveKey.index" to index, archiveKey to archive.toByteArray())
        val calls = Collections.synchronizedList(mutableListOf<String>())
        val server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val key = request.path!!.substringAfterLast('/'); calls += key
                val data = payloads[key] ?: return MockResponse().setResponseCode(404)
                val range = request.getHeader("Range")?.removePrefix("bytes=")?.split('-')
                val response = if (range == null) data else data.copyOfRange(range[0].toInt(), range[1].toInt() + 1)
                return MockResponse().setResponseCode(if (range == null) 200 else 206).setBody(Buffer().write(response))
            }
        }
        server.start()
        try {
            val root = temp.newFolder(); val client = File(root, "_classic_era_").apply { mkdirs() }
            val exe = File(client, "WowClassic-arm64.exe").apply { writeText("keep installed EXE") }
            val config = File(client, "WTF/Config.wtf").apply { parentFile!!.mkdirs(); writeText("keep graphics") }
            val info = File(root, ".build.info").apply {
                writeText("Product|Active|Version|Build Key|CDN Key|CDN Hosts|CDN Path\n" +
                    "wow_classic_era|1|installed-version|$build|$cdn|127.0.0.1:${server.port}|tpr/wow\n")
            }
            val originalInfo = info.readBytes()
            val statuses = mutableListOf<String>()
            assertEquals(3, WowClientDownloader.repairVoiceFiles(root, { statuses += it }, { false }) { _, hu -> hu })
            // The progress lines follow the UI language: Hungarian here, with the build and the file names unchanged.
            assertEquals("A telepített installed-version build jegyzékének olvasása...", statuses.first())
            assertEquals(3, statuses.count { it.contains("letöltése (") })
            assertFalse(statuses.any { it.startsWith("Reading") || it.startsWith("Downloading") })
            files.filterKeys { it.startsWith("Utils") }.forEach { (name, bytes) -> assertArrayEquals(bytes, File(client, name.replace('\\', '/')).readBytes()) }
            assertEquals("keep installed EXE", exe.readText()); assertEquals("keep graphics", config.readText()); assertArrayEquals(originalInfo, info.readBytes())
            assertTrue(File(root, "Data/indices/$archiveKey.index").isFile)
            assertFalse(calls.contains(md5(blte(files.getValue("WowClassic-arm64.exe")))))
            calls.clear()
            assertEquals(0, WowClientDownloader.repairVoiceFiles(root, {}, { false }))
            assertEquals(listOf(build, installKey), calls.toList())
        } finally { server.shutdown() }
    }

    @Test fun substitutedConfigFileIsRejectedBeforeAnythingIsInstalled() {
        val server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest) = MockResponse().setResponseCode(200).setBody("install = x y\n")
        }
        server.start()
        try {
            val root = temp.newFolder(); File(root, "_classic_era_").mkdirs()
            val key = md5("the real config".toByteArray())
            File(root, ".build.info").writeText("Product|Active|Version|Build Key|CDN Key|CDN Hosts|CDN Path\n" +
                "wow_classic_era|1|v|$key|$key|127.0.0.1:${server.port}|tpr/wow\n")
            val error = assertThrows(java.io.IOException::class.java) { WowClientDownloader.repairVoiceFiles(root, {}, { false }) }
            assertTrue(error.message!!.contains("checksum", ignoreCase = true))
            assertFalse(File(root, "_classic_era_/Utils").exists())
        } finally { server.shutdown() }
    }

    @Test fun onlyThirtyTwoDigitHexIsAcceptedAsAServerProvidedKey() {
        assertTrue(WowClientDownloader.isKey("0123456789abcdefABCDEF0123456789"))
        for (bad in listOf("", "../../etc/passwd", "a".repeat(31), "a".repeat(33), "g".repeat(32), "aa/../" + "a".repeat(26), "a".repeat(32) + ".index"))
            assertFalse(bad, WowClientDownloader.isKey(bad))
    }
}
