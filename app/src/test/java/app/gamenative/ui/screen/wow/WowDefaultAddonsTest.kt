package app.gamenative.ui.screen.wow

import app.gamenative.wow.addons.AddonDb
import app.gamenative.wow.addons.AddonFlavor
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class WowDefaultAddonsTest {
    @get:Rule val temp = TemporaryFolder()
    private fun archive() = ByteArrayOutputStream().also { buffer ->
        ZipOutputStream(buffer).use { zip ->
            for (folder in listOf("ConsolePort", "ConsolePort_Bar")) {
                zip.putNextEntry(ZipEntry("$folder/${folder}_Vanilla.toc"))
                zip.write("## Interface: 11509\n## Title: $folder\n".toByteArray())
                zip.closeEntry()
            }
        }
    }.toByteArray()
    private fun hash(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it.toInt() and 255) }
    private fun addons(root: File) = File(root, "_classic_era_/Interface/AddOns")

    @Test fun installsOfflineRegistersClassicSourceAndDoesNotUndoRemoval() {
        val root = temp.newFolder(); val bytes = archive()
        val settings = File(root, "_classic_era_/WTF/SavedVariables/ConsolePort.lua")
        settings.parentFile!!.mkdirs(); settings.writeText("my bindings")
        assertTrue(WowDefaultAddons.install(root, hash(bytes)) { bytes.inputStream() })
        val records = AddonDb.forAddonsDir(addons(root)).load()
        assertEquals(1, records.size)
        assertEquals(AddonFlavor.CLASSIC, records.single().flavor)
        assertEquals("seblindfors/ConsolePort", records.single().source!!.projectId)
        assertEquals(listOf("ConsolePort", "ConsolePort_Bar"), records.single().folders)
        assertEquals("my bindings", settings.readText())
        records.single().folders.forEach { File(addons(root), it).deleteRecursively() }
        assertFalse(WowDefaultAddons.install(root) { error("must not open bundle again") })
        assertFalse(File(addons(root), "ConsolePort").exists())
    }

    @Test fun existingPartialOrCustomInstallIsUntouched() {
        val root = temp.newFolder()
        val custom = File(addons(root), "consoleport_Bar/custom.lua")
        custom.parentFile!!.mkdirs(); custom.writeText("custom")
        assertFalse(WowDefaultAddons.install(root) { error("must not load bundle") })
        assertEquals("custom", custom.readText())
        assertFalse(File(addons(root), "ConsolePort").exists())
    }

    @Test fun corruptBundleLeavesNoInstallationOrMarkerAndCanRetry() {
        val root = temp.newFolder(); val bytes = archive()
        assertThrows(IOException::class.java) { WowDefaultAddons.install(root) { bytes.inputStream() } }
        assertFalse(File(addons(root), "ConsolePort").exists())
        assertTrue(WowDefaultAddons.install(root, hash(bytes)) { bytes.inputStream() })
        assertFalse(File(root, "_classic_era_/Interface").listFiles()!!.any { it.extension == "zip" })
    }
}
