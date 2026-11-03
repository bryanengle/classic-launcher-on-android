package app.gamenative.ui.screen.wow

import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class WowGameConfigTest {
    @get:Rule val temp = TemporaryFolder()

    private fun config(root: File) = File(root, "_classic_era_/WTF/Config.wtf")

    @Test fun theGameTabShowsTheRegionLanguageAndAccountTheGameUsesNow() {
        val root = temp.newFolder()
        assertEquals("1", WowGameConfig.current(root).account)
        assertEquals("enUS", WowGameConfig.current(root).locale)
        config(root).apply { parentFile!!.mkdirs() }.writeText("SET textLocale \"deDE\"\nSET accountList \"WoW1|!WoW2|\"\n")
        File(root, ".build.info").writeText("Branch!STRING:0|Product!STRING:0\neu|wow_classic_era\n")
        assertEquals(WowGameConfig.Current("eu", "deDE", "2"), WowGameConfig.current(root))
    }

    @Test fun playPreservesSavedRenderScaleFsrAndSharpnessAcrossRestarts() {
        val root = temp.newFolder()
        WowGameConfig.prepare(root, "1344x608")
        val file = config(root)
        // Simulate WoW saving the player's choices after its first session.
        file.writeText(file.readText()
            .replace("SET RenderScale \"1\"", "SET RenderScale \"0.75\"")
            .replace("SET ResampleQuality \"0\"", "SET ResampleQuality \"3\"") +
            "SET ResampleSharpness \"0.4\"\n")
        val saved = file.readBytes()
        repeat(2) {
            WowGameConfig.prepare(root, "1344x608")
            assertArrayEquals("Play must preserve the saved game settings", saved, file.readBytes())
        }
    }

    @Test fun changingOutputResolutionPreservesInternalRenderingAndOtherGraphics() {
        val root = temp.newFolder()
        val file = config(root).apply { parentFile!!.mkdirs() }
        val choices = listOf("set renderscale \"0.75\"", "  SET RESAMPLEQUALITY \"3\"",
            "SET ResampleSharpness \"0.4\"", "SET farclip \"3000\"",
            "SET worldBaseMip \"2\"", "SET graphicsTextureResolution \"0\"",
            "SET componentTextureLevel \"1\"", "SET uiScale \"0.65\"", "SET gxWindow \"1\"")
        file.writeText((choices + "SET gxResolution \"1920x1080\"").joinToString("\n", postfix = "\n"))
        WowGameConfig.prepare(root, "1344x608")
        choices.forEach { assertTrue("Lost user setting: $it", it in file.readLines()) }
        assertTrue("SET gxResolution \"1344x608\"" in file.readLines())
        assertTrue("SET gxWindowedResolution \"1344x608\"" in file.readLines())
        WowGameConfig.prepare(root, "1280x720")
        choices.forEach { assertTrue("Lost user setting: $it", it in file.readLines()) }
        assertTrue("SET gxResolution \"1280x720\"" in file.readLines())
        assertFalse(file.readText().contains("1344x608"))
    }

    @Test fun newConfigKeepsOriginalDefaultsAndSelectedOutputMode() {
        val root = temp.newFolder()
        WowGameConfig.prepare(root, "1344x608")
        val lines = config(root).readLines()
        for (line in listOf("SET gxApi \"D3D11\"", "SET RenderScale \"1\"",
            "SET ResampleQuality \"0\"", "SET graphicsQuality \"0\"",
            "SET GamePadEnable \"1\"", "SET gxResolution \"1344x608\""))
            assertTrue("Missing initial default: $line", line in lines)
        val flavor = File(root, "_classic_era_/.flavor.info")
        assertEquals("Product Flavor!STRING:0\nwow_classic_era\n", flavor.readText())
    }

    @Test fun omittedGraphicsCvarsAreNotReintroducedIntoExistingConfig() {
        val root = temp.newFolder()
        val file = config(root).apply { parentFile!!.mkdirs() }
        file.writeText("SET gxApi \"D3D11\"\nSET custom \"keep\"\n")
        WowGameConfig.prepare(root, "1344x608")
        assertFalse(file.readText().contains("RenderScale", ignoreCase = true))
        assertFalse(file.readText().contains("Resample", ignoreCase = true))
        assertFalse(file.readText().contains("graphicsQuality", ignoreCase = true))
        assertTrue(file.readText().contains("SET custom \"keep\""))
    }

    @Test fun unmanagedDisplayPreservesGameResolutionAndFsrChoices() {
        val root = temp.newFolder()
        val file = config(root).apply { parentFile!!.mkdirs() }
        val choices = listOf("SET gxResolution \"1600x900\"", "SET RenderScale \"0.75\"",
            "SET ResampleQuality \"3\"", "SET ResampleSharpness \"0.4\"")
        file.writeText(choices.joinToString("\n", postfix = "\n"))
        WowGameConfig.prepare(root, null)
        choices.forEach { assertTrue(it in file.readLines()) }
        assertFalse(file.readText().contains("1920x1080"))
    }

    @Test fun requiredD3d11AndInputSettingsStillApplyWithoutDuplicateKeys() {
        val root = temp.newFolder()
        val file = config(root).apply { parentFile!!.mkdirs() }
        file.writeText("set GXAPI \"D3D12\"\nSET gxApi \"D3D12\"\n" +
            "  set GAMEPADENABLE \"0\"\nSET InputDeviceInterfaceStyle \"0\"\n")
        WowGameConfig.prepare(root)
        val lines = file.readLines()
        assertEquals(1, lines.count { it == "SET gxApi \"D3D11\"" })
        assertEquals(1, lines.count { it == "SET GamePadEnable \"1\"" })
        assertEquals(1, lines.count { it == "SET InputDeviceInterfaceStyle \"1\"" })
        val saved = file.readBytes()
        WowGameConfig.prepare(root)
        assertArrayEquals(saved, file.readBytes())
    }
}
