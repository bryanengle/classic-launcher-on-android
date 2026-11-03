package app.gamenative.ui.screen.wow

import android.app.Application
import com.winlator.container.Container
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [29])
class WowContainerProfileTest {
    @get:Rule
    val tempFolder = TemporaryFolder()

    private val oldPath = "/storage/emulated/0/WoW Forever"
    private val newPath = "/storage/emulated/0/Games/WoW Forever"

    @Test
    fun legacyFexAndBoxKeysInAnExistingContainerAreIgnored() {
        val config = WowContainerProfile.defaultsForNewContainer(oldPath, false, false)
            .put("fexcoreVersion", "2609-0").put("fexcorePreset", "INTERMEDIATE").put("emulator", "FEXCore")
            .put("box64Version", "0.4.2").put("box64Preset", "COMPATIBILITY").put("box86Version", "0.3.2").put("box86Preset", "COMPATIBILITY")
        val container = Container(WowContainerProfile.CONTAINER_ID).apply { loadData(config) }
        assertEquals(WowBundledComponents.DXVK_ID, container.getDXWrapper())
        assertEquals(WowBundledComponents.PROTON_ID, container.wineVersion)
        assertEquals("G:\\_classic_era_\\WowClassic-arm64.exe", container.executablePath)
    }

    @Test
    fun newContainerUsesTheCurrentBundledStack() {
        val config = WowContainerProfile.defaultsForNewContainer(oldPath, false, false)
        val container = Container(WowContainerProfile.CONTAINER_ID).apply { loadData(config) }
        assertEquals("WoW Classic Era", container.name)
        assertEquals(WowBundledComponents.PROTON_ID, container.wineVersion)
        assertEquals(WowBundledComponents.DXVK_ID, container.getDXWrapper())
        assertEquals("version=${WowBundledComponents.DXVK_VERSION}", container.getDXWrapperConfig())
        assertEquals("Wrapper", container.graphicsDriver)
        assertEquals(WowBundledComponents.DRIVER_NAME, container.graphicsDriverVersion)
        assertEquals("version=${WowBundledComponents.DRIVER_NAME},adrenotoolsTurnip=1,resourceType=buffer,bcnEmulation=auto,quality=high", container.graphicsDriverConfig)
        assertEquals("vulkan", container.displayRenderer)
        assertEquals("1280x720", container.screenSize)
        assertEquals(Container.BIONIC, container.containerVariant)
        assertEquals("G:\\_classic_era_\\WowClassic-arm64.exe", container.executablePath)
        assertEquals("D:/storage/emulated/0/DownloadG:$oldPath", container.drives)
        assertEquals("-d3d11", container.execArgs)
        // v1.0.1: FEX is gone; a new container carries no FEX/emulator keys at all.
        for (key in listOf("fexcoreVersion", "fexcorePreset", "emulator", "box64Version", "box64Preset", "box86Version", "box86Preset")) assertFalse(key, config.has(key))
        assertTrue(container.isWoW64Mode)
        assertEquals(Container.STARTUP_SELECTION_AGGRESSIVE, container.startupSelection)
        assertEquals(
            "WRAPPER_MAX_IMAGE_COUNT=0 MESA_SHADER_CACHE_DISABLE=false MESA_SHADER_CACHE_MAX_SIZE=512MB WINEESYNC=0 WINEFSYNC=0 WINENTSYNC=1 PROTON_NO_NTSYNC=0 PULSE_LATENCY_MSEC=40",
            container.envVars,
        )
    }

    @Test
    fun newAdreno8xxSamsungContainerKeepsDeviceSpecificDefaults() {
        val config = WowContainerProfile.defaultsForNewContainer(newPath, true, true)
        assertEquals("1280x720", config.getString("screenSize"))
        assertEquals(WowBundledComponents.DRIVER_NAME, config.getString("graphicsDriverVersion"))
        assertEquals("version=${WowBundledComponents.DRIVER_NAME},adrenotoolsTurnip=1,resourceType=buffer,bcnEmulation=auto,quality=high", config.getString("graphicsDriverConfig"))
        assertFalse(config.getString("envVars").contains("TU_DEBUG="))
        assertTrue(config.getString("envVars").endsWith(" FD_DEV_FEATURES=enable_tp_ubwc_flag_hint=1"))
    }

    private fun existingContainer(): Container = Container(WowContainerProfile.CONTAINER_ID).apply {
        rootDir = tempFolder.newFolder()
        loadData(WowContainerProfile.defaultsForNewContainer(oldPath, false, false))
    }

    @Test
    fun playPreparationPreservesCustomWine() {
        val container = existingContainer().apply { wineVersion = "proton-10.0-4-arm64ec-1" }
        assertFalse(WowContainerProfile.prepareExistingContainer(container, oldPath))
        assertEquals("proton-10.0-4-arm64ec-1", container.wineVersion)
    }

    @Test
    fun playPreparationPreservesCustomDxvkAndConfig() {
        val container = existingContainer().apply {
            setDXWrapper("dxvk-2.6.1-arm64-7")
            setDXWrapperConfig("version=2.6.1-arm64-7,async=0,maxDeviceMemory=4096")
        }
        assertFalse(WowContainerProfile.prepareExistingContainer(container, oldPath))
        assertEquals("dxvk-2.6.1-arm64-7", container.getDXWrapper())
        assertEquals("version=2.6.1-arm64-7,async=0,maxDeviceMemory=4096", container.getDXWrapperConfig())
    }

    @Test
    fun playPreparationPreservesCustomGraphicsDriver() {
        val container = existingContainer().apply {
            graphicsDriver = "wrapper-custom-2"
            graphicsDriverConfig = "version=Turnip-custom,adrenotoolsTurnip=1,quality=low"
            graphicsDriverVersion = "Turnip-custom"
        }
        assertFalse(WowContainerProfile.prepareExistingContainer(container, oldPath))
        assertEquals("wrapper-custom-2", container.graphicsDriver)
        assertEquals("version=Turnip-custom,adrenotoolsTurnip=1,quality=low", container.graphicsDriverConfig)
        assertEquals("Turnip-custom", container.graphicsDriverVersion)
    }

    @Test
    fun gameLocationChangePersistsOnlyLauncherOwnedFields() {
        val container = existingContainer().apply {
            name = "My WoW profile"
            wineVersion = "proton-10.0-4-arm64ec-1"
            setDXWrapper("dxvk-2.6.1-arm64-7")
            setDXWrapperConfig("version=2.6.1-arm64-7,async=0")
            graphicsDriver = "wrapper-custom-2"
            graphicsDriverConfig = "version=Turnip-custom,quality=low"
            graphicsDriverVersion = "Turnip-custom"
            displayRenderer = "opengl"
            screenSize = "1600x900"
            isWoW64Mode = false
            envVars = "WINEESYNC=1 CUSTOM_FLAG=preserved"
            cpuList = "4,5"
            cpuListWoW64 = "6,7"
            audioDriver = "alsa"
            setPulseaudioLowLatency(true)
            execArgs = "-d3d11 -windowed"
            startupSelection = Container.STARTUP_SELECTION_NORMAL
            basePrefix = "/custom/runtime/base_prefix/.wine"
            containerVariant = "glibc"
            executablePath = "G:\\_classic_era_\\WowB.exe"
            drives = "D:/custom/downloadsG:${oldPath}E:/custom/files"
            putExtra("customRuntimeMarker", "preserved")
        }
        val prefixMarker = File(container.rootDir, ".wine/user.reg").apply {
            parentFile!!.mkdirs()
            writeText("registry must remain untouched")
        }
        container.saveData()
        val before = JSONObject(container.configFile.readText())

        assertTrue(WowContainerProfile.prepareExistingContainer(container, newPath))
        container.saveData()
        val after = JSONObject(container.configFile.readText())
        val owned = setOf("drives", "executablePath", "containerVariant", "audioDriver")
        assertEquals(before.keys().asSequence().toSet(), after.keys().asSequence().toSet())
        for (key in before.keys()) {
            if (key !in owned) assertEquals("Preserve $key", before.get(key).toString(), after.get(key).toString())
        }
        assertEquals("D:/custom/downloadsG:$newPath" + "E:/custom/files", after.getString("drives"))
        assertEquals("G:\\_classic_era_\\WowClassic-arm64.exe", after.getString("executablePath"))
        assertEquals(Container.BIONIC, after.getString("containerVariant"))
        assertEquals("pulseaudio", after.getString("audioDriver"))
        assertEquals("registry must remain untouched", prefixMarker.readText())
        assertFalse(WowContainerProfile.prepareExistingContainer(container, newPath))
    }

    @Test
    fun playPreparationDoesNotForceCreationArgumentsOnExistingContainer() {
        val container = existingContainer().apply { execArgs = "-windowed" }
        assertFalse(WowContainerProfile.prepareExistingContainer(container, oldPath))
        assertEquals("-windowed", container.execArgs)
    }

    @Test
    fun missingGameDriveIsAddedWithoutResettingOtherDrives() {
        val container = existingContainer().apply { drives = "D:/custom/downloadsE:/custom/files" }
        assertTrue(WowContainerProfile.prepareExistingContainer(container, newPath))
        assertEquals("D:/custom/downloadsE:/custom/filesG:$newPath", container.drives)
    }

    @Test fun redMagicAspectStartsAtItsManagedFullscreenResolution() {
        val plain = WowContainerProfile.defaultsForNewContainer("/game", true, false)
        assertEquals("1280x720", plain.getString("screenSize"))
        assertFalse(plain.getJSONObject("extraData").has(WowDisplaySettings.MANAGED))
        val redMagic = WowContainerProfile.defaultsForNewContainer("/game", true, false, fitsRedMagic = true)
        assertEquals(WowDisplaySettings.REDMAGIC, redMagic.getString("screenSize"))
        assertEquals("1", redMagic.getJSONObject("extraData").getString(WowDisplaySettings.MANAGED))
    }

    @Test
    fun oldManagedPulseLatencyIsLoweredOnceAndCustomValuesAreKept() {
        val config = WowContainerProfile.defaultsForNewContainer(oldPath, false, false)
        // an existing container from before beta5: the exact old default, no migration marker yet
        val old = Container(WowContainerProfile.CONTAINER_ID).apply {
            loadData(config)
            envVars = envVars.replace("PULSE_LATENCY_MSEC=40", "PULSE_LATENCY_MSEC=144")
            putExtra("pulseLatencyMigration", null)
        }
        assertTrue(WowContainerProfile.prepareExistingContainer(old, oldPath))
        assertEquals("40", com.winlator.core.envvars.EnvVars(old.envVars).get("PULSE_LATENCY_MSEC"))
        assertFalse(WowContainerProfile.prepareExistingContainer(old, oldPath)) // once only
        // a value the player chose is never touched
        val custom = Container(WowContainerProfile.CONTAINER_ID).apply {
            loadData(config)
            envVars = envVars.replace("PULSE_LATENCY_MSEC=40", "PULSE_LATENCY_MSEC=90")
            putExtra("pulseLatencyMigration", null)
        }
        WowContainerProfile.prepareExistingContainer(custom, oldPath)
        assertEquals("90", com.winlator.core.envvars.EnvVars(custom.envVars).get("PULSE_LATENCY_MSEC"))
    }
}
