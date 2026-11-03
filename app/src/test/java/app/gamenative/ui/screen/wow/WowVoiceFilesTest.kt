package app.gamenative.ui.screen.wow

import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import java.security.MessageDigest

class WowVoiceFilesTest {
    @get:Rule val temp = TemporaryFolder()
    private val tags = setOf("Windows", "arm64")
    private fun pe(dll: Boolean = false, machine: Int = 0xaa64): ByteArray {
        val data = ByteArray(512)
        val b = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN)
        b.putShort(0, 0x5a4d.toShort()); b.putInt(60, 64); b.putInt(64, 0x4550)
        b.putShort(68, machine.toShort()); b.putShort(70, 1); b.putShort(84, 240)
        b.putShort(86, (if (dll) 0x2022 else 0x22).toShort()); b.putShort(88, 0x20b)
        return data
    }
    private fun md5(bytes: ByteArray) = MessageDigest.getInstance("MD5").digest(bytes).joinToString("") { "%02x".format(it.toInt() and 255) }
    private fun entry(name: String, bytes: ByteArray = pe(name.endsWith(".dll"))) = WowClientDownloader.InstallEntry(name, md5(bytes), tags)
    private fun blte(bytes: ByteArray) = byteArrayOf(66, 76, 84, 69, 0, 0, 0, 0, 78) + bytes
    private fun install(root: File, entries: List<WowClientDownloader.InstallEntry>, active: () -> Boolean = { false },
        data: (WowClientDownloader.InstallEntry) -> ByteArray = { pe(it.relativePath.endsWith(".dll")) }): Int =
        WowClientDownloader.installVoiceEntries(root, entries, active, {}) { blte(data(it)) }

    @Test fun installedBuildVoiceSelectionIncludesActualTProxyAndOnlyArm64() {
        val valid = listOf(entry("Utils\\WowVoiceProxyT.exe"), entry("Utils\\vivoxsdk.dll"), entry("Utils\\discord_partner_sdk.dll"))
        val all = valid + listOf(WowClientDownloader.InstallEntry("Utils\\WowVoiceProxyT.exe", "other", setOf("Windows", "x86_64")), entry("WowClassic-arm64.exe"), entry("Utils\\WowVoiceProxy-China.exe"))
        assertEquals(valid, WowClientDownloader.voiceEntries(all))
        assertTrue(valid.all { it.isClientBinary() })
    }
    @Test fun incompleteOrAmbiguousManifestIsRejected() {
        assertThrows(IllegalStateException::class.java) { WowClientDownloader.voiceEntries(listOf(entry("Utils\\vivoxsdk.dll"))) }
        val proxy = entry("Utils\\WowVoiceProxyT.exe")
        assertThrows(IllegalStateException::class.java) { WowClientDownloader.voiceEntries(listOf(proxy, entry("Utils\\vivoxsdk.dll"))) }
        assertThrows(IllegalStateException::class.java) { WowClientDownloader.voiceEntries(listOf(proxy, proxy, entry("Utils\\vivoxsdk.dll"))) }
    }
    @Test fun verifiedPayloadInstallsWithoutTouchingExeConfigOrCaches() {
        val root = temp.newFolder(); File(root, "WTF").mkdirs()
        val untouched = listOf("WowClassic-arm64.exe", "WTF/Config.wtf", "shader.dxvk-cache").map { File(root, it).apply { writeText("unchanged:$it") } }
        val old = untouched.associateWith { it.readBytes() }
        val entries = listOf(entry("Utils\\WowVoiceProxyT.exe"), entry("Utils\\vivoxsdk.dll"), entry("Utils\\discord_partner_sdk.dll"))
        assertEquals(3, install(root, entries))
        for (e in entries) assertEquals(e.ckey, md5(File(root, e.relativePath).readBytes()))
        old.forEach { (file, data) -> assertArrayEquals(data, file.readBytes()) }
        assertFalse(root.listFiles()!!.any { it.name.startsWith(".wow-voice-") })
    }
    @Test fun invalidChecksumDoesNotReplaceExistingFileAndRemovesStage() {
        val root = temp.newFolder(); val dest = File(root, "Utils/WowVoiceProxyT.exe").apply { parentFile!!.mkdirs(); writeText("old") }
        assertThrows(IllegalStateException::class.java) { install(root, listOf(entry("Utils\\WowVoiceProxyT.exe"))) { pe().apply { this[4] = 1 } } }
        assertEquals("old", dest.readText()); assertEquals(listOf("Utils"), root.list()!!.toList())
    }
    @Test fun x64PayloadIsRejectedEvenIfManifestHashMatchesAndNothingIsPublished() {
        val root = temp.newFolder(); val bytes = pe(machine = 0x8664)
        assertThrows(IllegalArgumentException::class.java) { install(root, listOf(entry("Utils\\WowVoiceProxyT.exe", bytes))) { bytes } }
        assertTrue(root.list()!!.isEmpty())
    }
    @Test fun allFilesAreValidatedBeforeAnyExistingFileIsReplaced() {
        val root = temp.newFolder(); val old = File(root, "Utils/WowVoiceProxyT.exe").apply { parentFile!!.mkdirs(); writeText("old") }
        val entries = listOf(entry("Utils\\WowVoiceProxyT.exe"), entry("Utils\\vivoxsdk.dll"))
        assertThrows(IllegalStateException::class.java) { install(root, entries) { if (it.name.endsWith(".dll")) byteArrayOf(1) else pe() } }
        assertEquals("old", old.readText()); assertFalse(File(root, "Utils/vivoxsdk.dll").exists())
    }
    @Test fun activeSessionAndRaceBeforePublishAreRejected() {
        val root = temp.newFolder(); val entries = listOf(entry("Utils\\WowVoiceProxyT.exe"))
        assertThrows(IllegalStateException::class.java) { install(root, entries, { true }) }
        var checks = 0
        assertThrows(IllegalStateException::class.java) { install(root, entries, { ++checks >= 3 }) }
        assertTrue(root.list()!!.isEmpty())
    }
    @Test fun traversalAndSymlinkOutsideGameAreRejected() {
        val root = temp.newFolder(); val outside = temp.newFolder()
        assertThrows(IllegalArgumentException::class.java) { install(root, listOf(entry("Utils\\..\\WowClassic-arm64.exe"))) }
        Files.createSymbolicLink(File(root, "Utils").toPath(), outside.toPath())
        assertThrows(IllegalArgumentException::class.java) { install(root, listOf(entry("Utils\\WowVoiceProxyT.exe"))) }
        assertTrue(outside.list()!!.isEmpty())
    }
}
