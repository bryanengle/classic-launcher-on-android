package app.gamenative.ui.screen.wow

import app.gamenative.wow.addons.AddonArchive
import app.gamenative.wow.addons.AddonDb
import app.gamenative.wow.addons.AddonFlavor
import app.gamenative.wow.addons.InstalledAddon
import app.gamenative.wow.addons.ProviderIds
import app.gamenative.wow.addons.SourceRef
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.security.MessageDigest

/** One-time, offline default for each game folder. Called while WowAddonJobs owns addon writes. */
internal object WowDefaultAddons {
    const val ASSET = "bundled_addons/ConsolePort-3.3.13.zip"
    const val SHA256 = "548994a59cc548f53c26e6530137049129141f138c98d699f78fabe9f07037d2"
    private const val MARKER = ".classic-consoleport-default-v1"

    @Synchronized
    fun install(gameRoot: File, expectedDigest: String = SHA256, archive: () -> InputStream): Boolean {
        val interfaceDir = File(gameRoot, "${WowClientDownloader.FLAVOR_DIR}/Interface")
        val addonsDir = File(interfaceDir, "AddOns")
        val marker = File(interfaceDir, MARKER)
        if (marker.exists()) return false
        if (!interfaceDir.isDirectory && !interfaceDir.mkdirs()) throw IOException("Cannot prepare controller addon folder")
        // Preserve any version, partial installation, disabled setup, or custom ConsolePort module.
        if (addonsDir.listFiles().orEmpty().any { it.name.equals("ConsolePort", true) || it.name.startsWith("ConsolePort_", true) }) {
            marker.writeText("Existing ConsolePort preserved\n")
            return false
        }
        val zip = File.createTempFile(".consoleport-", ".zip", interfaceDir)
        try {
            val digest = MessageDigest.getInstance("SHA-256")
            archive().use { input ->
                java.security.DigestInputStream(input, digest).use { source -> zip.outputStream().use { source.copyTo(it) } }
            }
            val actual = digest.digest().joinToString("") { "%02x".format(it.toInt() and 255) }
            if (actual != expectedDigest) throw IOException("Bundled ConsolePort checksum mismatch")
            val folders = AddonArchive.install(zip, addonsDir)
            val db = AddonDb.forAddonsDir(addonsDir)
            val record = InstalledAddon(
                id = "github:seblindfors/ConsolePort", name = "ConsolePort", folders = folders,
                version = "3.3.13", flavor = AddonFlavor.CLASSIC,
                source = SourceRef(ProviderIds.GITHUB, "seblindfors/ConsolePort", "692519703"),
                installedAt = System.currentTimeMillis(),
            )
            db.save(db.load().filterNot { it.id == record.id } + record)
            // Kept outside AddOns so removing ConsolePort through the manager stays removed.
            marker.writeText("Installed ConsolePort 3.3.13\n")
            return true
        } finally {
            zip.delete()
        }
    }
}
