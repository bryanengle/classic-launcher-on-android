package app.gamenative.ui.screen.wow

import android.app.Application
import android.content.ContextWrapper
import com.winlator.container.Container
import com.winlator.container.ContainerManager
import com.winlator.contents.ContentsManager
import com.winlator.contents.ContentProfile
import com.winlator.core.envvars.EnvVars
import com.winlator.xenvironment.ImageFs
import org.json.JSONArray
import org.json.JSONObject
import org.junit.*
import org.junit.Assert.*
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [29])
class WowLaunchEnvironmentTest {
    @get:Rule val temp = TemporaryFolder()
    private val files by lazy { temp.newFolder("files") }
    private val context by lazy { object : ContextWrapper(RuntimeEnvironment.getApplication()) {
        override fun getFilesDir() = files
    } }
    private var previousImage: Any? = null
    @Before fun before() {
        val field = ImageFs::class.java.getDeclaredField("INSTANCE").apply { isAccessible = true }
        previousImage = field.get(null); field.set(null, ImageFs.find(File(files,"imagefs")))
    }
    @After fun after() {
        ImageFs::class.java.getDeclaredField("INSTANCE").apply { isAccessible = true }.set(null,previousImage)
    }
    private fun container(): Container = Container("wow_forever").apply {
        rootDir = File(ImageFs.find(context).rootDir,"home/xuser-wow_forever").apply { mkdirs() }
        loadData(WowContainerProfile.defaultsForNewContainer("/games/wow",false,false))
        drives = "G:/games/wowD:/downloads"
        // v1.0: the default location is the app's private cache; most tests here describe the game-directory mode.
        putExtra(WowLaunchEnvironment.LOCATION_KEY, WowLaunchEnvironment.GAME_DIRECTORY)
        saveData()
    }
    private fun installDxvk(c: Container, variables: List<String>): File {
        val dir = File(ContentsManager.getContentTypeDir(context,ContentProfile.ContentType.CONTENT_TYPE_DXVK),"custom").apply { mkdirs() }
        val dll = File(dir,"system32/d3d11.dll").apply { parentFile!!.mkdirs(); writeBytes(variables.joinToString("\u0000",postfix="\u0000").toByteArray()) }
        File(dir,"profile.json").writeText(JSONObject().put("type","DXVK").put("versionName","custom").put("versionCode",1).put("description","test DXVK payload")
            .put("files",JSONArray().put(JSONObject().put("source","system32/d3d11.dll").put("target", "\${system32}/d3d11.dll"))).toString())
        assertNotNull("Fixture must be a valid existing-backend profile", ContentsManager(context).readProfile(File(dir,"profile.json")))
        c.dxWrapper = "dxvk-custom-1"
        return dll
    }
    @Test fun diagnosticsLeaveWinegstreamerLoadVisibleUnlessTheUserSavedTheSameValue() {
        val c = container()
        val env = EnvVars()
        WowLaunchEnvironment.applyRuntimeDefaults(c, env)
        WowLaunchEnvironment.keepWinegstreamerVisibleForDiagnostics(c, env, false)
        assertEquals("winegstreamer=d", env.get("WINEDLLOVERRIDES"))
        WowLaunchEnvironment.keepWinegstreamerVisibleForDiagnostics(c, env, true)
        assertFalse(env.has("WINEDLLOVERRIDES"))
        c.envVars = "WINEDLLOVERRIDES=winegstreamer=d"; c.saveData()
        val saved = EnvVars()
        WowLaunchEnvironment.applyRuntimeDefaults(c, saved)
        WowLaunchEnvironment.keepWinegstreamerVisibleForDiagnostics(c, saved, true)
        assertEquals("winegstreamer=d", saved.get("WINEDLLOVERRIDES"))
    }
    @Test fun winegstreamerIsDisabledByDefaultButASavedOverrideWins() {
        val c = container()
        val env = EnvVars("UNRELATED=base")
        WowLaunchEnvironment.applyRuntimeDefaults(c, env)
        assertEquals("winegstreamer=d", env.get("WINEDLLOVERRIDES"))
        c.envVars = "WINEDLLOVERRIDES=mscoree=d"; c.saveData()
        val saved = EnvVars()
        WowLaunchEnvironment.applyRuntimeDefaults(c, saved)
        assertEquals("mscoree=d", saved.get("WINEDLLOVERRIDES"))
    }
    @Test fun bundledRuntimeDefaultsEnableUserspaceNtsyncAndVegasAsyncWithoutSaving() {
        val c = container().apply { envVars = "CUSTOM=keep"; saveData() }
        val before = c.configFile.readBytes()
        val env = EnvVars("WINEESYNC=1 WINEFSYNC=1 UNRELATED=base")
        WowLaunchEnvironment.applyRuntimeDefaults(c, env)
        assertEquals("1", env.get("WINENTSYNC"))
        assertEquals("0", env.get("PROTON_NO_NTSYNC"))
        assertEquals("0", env.get("WINEESYNC"))
        assertEquals("0", env.get("WINEFSYNC"))
        assertEquals("1", env.get("DXVK_ASYNC"))
        assertEquals("keep", env.get("CUSTOM"))
        assertEquals("base", env.get("UNRELATED"))
        assertArrayEquals(before, c.configFile.readBytes())
        assertEquals("CUSTOM=keep", c.envVars)
    }

    @Test fun newContainersPersistTheSupportedRuntimeSwitches() {
        val c = container()
        val saved = EnvVars(ContainerManager(context).getContainerById(c.id)!!.envVars)
        assertEquals("1", saved.get("WINENTSYNC"))
        assertEquals("0", saved.get("PROTON_NO_NTSYNC"))
        // v1.0: DXVK_ASYNC is no longer a default container variable; the Hybrid mode is chosen at launch.
        assertFalse(saved.has("DXVK_ASYNC"))
        assertEquals("0", saved.get("WINEESYNC"))
        assertEquals("0", saved.get("WINEFSYNC"))
    }

    @Test fun explicitOffAndOtherSavedRuntimeChoicesOverrideTheDefaults() {
        val c = container().apply { envVars = "WINENTSYNC=0 PROTON_NO_NTSYNC=1 DXVK_ASYNC=0 WINEESYNC=1 WINEFSYNC=1 WINEDLLOVERRIDES=mscoree=d" }
        val env = EnvVars()
        WowLaunchEnvironment.applyRuntimeDefaults(c, env)
        assertEquals(c.envVars, env.toString())
    }

    @Test fun explicitDxvkConfigAsyncOffRemainsOffWhenEnvSwitchIsAbsent() {
        val c = container().apply { envVars = ""; dxWrapperConfig += ",async=0" }
        val env = EnvVars()
        WowLaunchEnvironment.applyRuntimeDefaults(c, env)
        assertEquals("0", env.get("DXVK_ASYNC"))
    }

    @Test fun previouslyBundledR9RetainsSelectionAsyncDefaultsAndExplicitOff() {
        val c = container().apply {
            dxWrapper = "dxvk-Vegas-2.7.3-wow-arm64-async-gamenative-r9-9"
            dxWrapperConfig = "version=Vegas-2.7.3-wow-arm64-async-gamenative-r9-9"
            envVars = "CUSTOM=keep"
            saveData()
        }
        val before = c.configFile.readBytes()
        val env = EnvVars()
        WowLaunchEnvironment.applyRuntimeDefaults(c, env)
        assertEquals("1", env.get("DXVK_ASYNC"))
        assertArrayEquals(before, c.configFile.readBytes())
        c.envVars = "DXVK_ASYNC=0"
        WowLaunchEnvironment.applyRuntimeDefaults(c, env)
        assertEquals("0", env.get("DXVK_ASYNC"))
        assertEquals("dxvk-Vegas-2.7.3-wow-arm64-async-gamenative-r9-9", c.dxWrapper)
    }

    @Test fun previouslyBundledR10RetainsSelectionAsyncDefaultsAndExplicitOff() {
        val c = container().apply {
            dxWrapper = "dxvk-Vegas-2.7.3-wow-arm64-async-gamenative-r10-10"
            dxWrapperConfig = "version=Vegas-2.7.3-wow-arm64-async-gamenative-r10-10"
            envVars = "CUSTOM=keep"
            saveData()
        }
        val before = c.configFile.readBytes()
        val env = EnvVars()
        WowLaunchEnvironment.applyRuntimeDefaults(c, env)
        assertEquals("1", env.get("DXVK_ASYNC"))
        assertArrayEquals(before, c.configFile.readBytes())
        c.envVars = "DXVK_ASYNC=0"
        WowLaunchEnvironment.applyRuntimeDefaults(c, env)
        assertEquals("0", env.get("DXVK_ASYNC"))
        assertEquals("dxvk-Vegas-2.7.3-wow-arm64-async-gamenative-r10-10", c.dxWrapper)
    }

    @Test fun existingR9KeepsSelectionNtsyncDefaultsAndExplicitOffAfterR10Update() {
        val c = container().apply {
            wineVersion = "proton-11.0-90639.9-arm64ec-9"
            envVars = "CUSTOM=keep"; saveData()
        }
        val before = c.configFile.readBytes()
        val env = EnvVars()
        WowLaunchEnvironment.applyRuntimeDefaults(c, env)
        assertEquals("1", env.get("WINENTSYNC"))
        assertEquals("0", env.get("PROTON_NO_NTSYNC"))
        assertEquals("1", env.get("DXVK_ASYNC"))
        assertArrayEquals(before, c.configFile.readBytes())
        c.envVars = "WINENTSYNC=0 PROTON_NO_NTSYNC=1"
        WowLaunchEnvironment.applyRuntimeDefaults(c, env)
        assertEquals("0", env.get("WINENTSYNC"))
        assertEquals("1", env.get("PROTON_NO_NTSYNC"))
        assertEquals("proton-11.0-90639.9-arm64ec-9", c.wineVersion)
    }

    @Test fun previouslyBundledR12RetainsSelectionAsyncDefaultsAndExplicitOff() {
        val c = container().apply {
            wineVersion = "proton-11.0-90639.8-arm64ec-8"
            dxWrapper = "dxvk-Vegas-2.7.3-wow-arm64-async-gamenative-r12-12"
            dxWrapperConfig = "version=Vegas-2.7.3-wow-arm64-async-gamenative-r12-12"
            envVars = "CUSTOM=keep"
            saveData()
        }
        val before = c.configFile.readBytes()
        val env = EnvVars()
        WowLaunchEnvironment.applyRuntimeDefaults(c, env)
        assertEquals("1", env.get("WINENTSYNC"))
        assertEquals("0", env.get("PROTON_NO_NTSYNC"))
        assertEquals("1", env.get("DXVK_ASYNC"))
        assertEquals("keep", env.get("CUSTOM"))
        assertArrayEquals(before, c.configFile.readBytes())
        c.envVars = "DXVK_ASYNC=0 WINENTSYNC=0 PROTON_NO_NTSYNC=1"
        WowLaunchEnvironment.applyRuntimeDefaults(c, env)
        assertEquals("0", env.get("DXVK_ASYNC"))
        assertEquals("0", env.get("WINENTSYNC"))
        assertEquals("1", env.get("PROTON_NO_NTSYNC"))
        assertEquals("proton-11.0-90639.8-arm64ec-8", c.wineVersion)
        assertEquals("dxvk-Vegas-2.7.3-wow-arm64-async-gamenative-r12-12", c.dxWrapper)
    }

    @Test fun previouslyBundledR11RetainsSelectionAsyncDefaultsAndExplicitOff() {
        val c = container().apply {
            dxWrapper = "dxvk-Vegas-2.7.3-wow-arm64-async-gamenative-r11-11"
            dxWrapperConfig = "version=Vegas-2.7.3-wow-arm64-async-gamenative-r11-11"
            envVars = "CUSTOM=keep"
            saveData()
        }
        val before = c.configFile.readBytes()
        val env = EnvVars()
        WowLaunchEnvironment.applyRuntimeDefaults(c, env)
        assertEquals("1", env.get("DXVK_ASYNC"))
        assertArrayEquals(before, c.configFile.readBytes())
        c.envVars = "DXVK_ASYNC=0"
        WowLaunchEnvironment.applyRuntimeDefaults(c, env)
        assertEquals("0", env.get("DXVK_ASYNC"))
        assertEquals("dxvk-Vegas-2.7.3-wow-arm64-async-gamenative-r11-11", c.dxWrapper)
    }

    @Test fun upgradingTheApkPreservesRuntimeDefaultsForThePreviouslyBundledR8() {
        val c = container().apply {
            wineVersion = "proton-11.0-90639.8-arm64ec-8"
            dxWrapper = "dxvk-Vegas-2.7.3-wow-arm64-async-gamenative-r8-8"
            dxWrapperConfig = "version=Vegas-2.7.3-wow-arm64-async-gamenative-r8-8,async=1"
            envVars = "CUSTOM=keep"
            saveData()
        }
        val before = c.configFile.readBytes()
        val env = EnvVars()
        WowLaunchEnvironment.applyRuntimeDefaults(c, env)
        assertEquals("1", env.get("WINENTSYNC"))
        assertEquals("0", env.get("PROTON_NO_NTSYNC"))
        assertEquals("1", env.get("DXVK_ASYNC"))
        assertEquals("keep", env.get("CUSTOM"))
        assertArrayEquals(before, c.configFile.readBytes())
        assertEquals("proton-11.0-90639.8-arm64ec-8", c.wineVersion)
        assertEquals("dxvk-Vegas-2.7.3-wow-arm64-async-gamenative-r8-8", c.dxWrapper)
    }

    @Test fun previouslyBundledR8KeepsExplicitRuntimeOffSwitches() {
        val c = container().apply {
            wineVersion = "proton-11.0-90639.8-arm64ec-8"
            dxWrapper = "dxvk-Vegas-2.7.3-wow-arm64-async-gamenative-r8-8"
            dxWrapperConfig = "version=Vegas-2.7.3-wow-arm64-async-gamenative-r8-8"
            envVars = "WINENTSYNC=0 PROTON_NO_NTSYNC=1 DXVK_ASYNC=0"
        }
        val env = EnvVars()
        WowLaunchEnvironment.applyRuntimeDefaults(c, env)
        assertEquals("0", env.get("WINENTSYNC"))
        assertEquals("1", env.get("PROTON_NO_NTSYNC"))
        assertEquals("0", env.get("DXVK_ASYNC"))
    }

    @Test fun upgradingTheApkPreservesRuntimeDefaultsForThePreviouslyBundledR7() {
        val c = container().apply {
            wineVersion = "proton-11.0-90639.7-arm64ec-7"
            dxWrapper = "dxvk-Vegas-2.7.3-wow-arm64-async-gamenative-r7-7"
            dxWrapperConfig = "version=Vegas-2.7.3-wow-arm64-async-gamenative-r7-7,async=1"
            envVars = "CUSTOM=keep"
            saveData()
        }
        val before = c.configFile.readBytes()
        val env = EnvVars()
        WowLaunchEnvironment.applyRuntimeDefaults(c, env)
        assertEquals("1", env.get("WINENTSYNC"))
        assertEquals("0", env.get("PROTON_NO_NTSYNC"))
        assertEquals("1", env.get("DXVK_ASYNC"))
        assertEquals("keep", env.get("CUSTOM"))
        assertArrayEquals(before, c.configFile.readBytes())
        assertEquals("proton-11.0-90639.7-arm64ec-7", c.wineVersion)
        assertEquals("dxvk-Vegas-2.7.3-wow-arm64-async-gamenative-r7-7", c.dxWrapper)
    }

    @Test fun previouslyBundledR7KeepsExplicitRuntimeOffSwitches() {
        val c = container().apply {
            wineVersion = "proton-11.0-90639.7-arm64ec-7"
            dxWrapper = "dxvk-Vegas-2.7.3-wow-arm64-async-gamenative-r7-7"
            dxWrapperConfig = "version=Vegas-2.7.3-wow-arm64-async-gamenative-r7-7"
            envVars = "WINENTSYNC=0 PROTON_NO_NTSYNC=1 DXVK_ASYNC=0"
        }
        val env = EnvVars()
        WowLaunchEnvironment.applyRuntimeDefaults(c, env)
        assertEquals("0", env.get("WINENTSYNC"))
        assertEquals("1", env.get("PROTON_NO_NTSYNC"))
        assertEquals("0", env.get("DXVK_ASYNC"))
    }

    @Test fun upgradingTheApkPreservesSupportedDefaultsForThePreviouslyBundledR6() {
        val c = container().apply {
            envVars = "CUSTOM=keep"
            dxWrapper = "dxvk-Vegas-2.7.3-wow-arm64-async-gamenative-r6-6"
            dxWrapperConfig = "version=Vegas-2.7.3-wow-arm64-async-gamenative-r6-6,async=1"
            saveData()
        }
        val before = c.configFile.readBytes()
        val env = EnvVars()
        WowLaunchEnvironment.applyRuntimeDefaults(c, env)
        assertEquals("1", env.get("DXVK_ASYNC"))
        assertEquals("keep", env.get("CUSTOM"))
        assertArrayEquals(before, c.configFile.readBytes())
        c.envVars = "DXVK_ASYNC=0"
        WowLaunchEnvironment.applyRuntimeDefaults(c, env)
        assertEquals("0", env.get("DXVK_ASYNC"))
        c.envVars = ""; c.dxWrapperConfig = "version=Vegas-2.7.3-wow-arm64-async-gamenative-r6-6,async=0"
        WowLaunchEnvironment.applyRuntimeDefaults(c, env)
        assertEquals("0", env.get("DXVK_ASYNC"))
    }

    @Test fun mismatchedVegasIdentifierAndConfigDoNotEnableAsync() {
        val c = container().apply {
            envVars = ""
            dxWrapperConfig = "version=Vegas-2.7.3-wow-arm64-async-gamenative-r6-6"
        }
        val env = EnvVars()
        WowLaunchEnvironment.applyRuntimeDefaults(c, env)
        assertFalse(env.has("DXVK_ASYNC"))
    }

    @Test fun newD3d11ProfileOmitsUnusedOpenGlAndD3d12Overrides() {
        val env = EnvVars(WowContainerProfile.defaultsForNewContainer("/games/wow", false, false).getString("envVars"))
        for (name in listOf("ZINK_DESCRIPTORS", "ZINK_DEBUG", "mesa_glthread", "VKD3D_SHADER_MODEL")) {
            assertFalse(name, env.has(name))
        }
    }

    @Test fun importedRuntimesDoNotReceiveBundledOnlyDefaults() {
        val c = container().apply { envVars = "CUSTOM=keep"; wineVersion = "other-proton-1"; dxWrapper = "dxvk-other-1" }
        val env = EnvVars("BASE=keep")
        WowLaunchEnvironment.applyRuntimeDefaults(c, env)
        assertFalse(env.has("WINENTSYNC")); assertFalse(env.has("PROTON_NO_NTSYNC")); assertFalse(env.has("DXVK_ASYNC"))
        assertEquals("keep", env.get("BASE")); assertEquals("keep", env.get("CUSTOM"))
    }

    @Test fun runtimeDefaultsAreAppliedForEitherCacheLocationAndIgnoreOtherContainers() {
        val c = container().apply { envVars = ""; putExtra(WowLaunchEnvironment.LOCATION_KEY, WowLaunchEnvironment.GAMENATIVE_DEFAULT) }
        val env = EnvVars()
        WowLaunchEnvironment.apply(context, c, ContentsManager(context), env)
        assertEquals("1", env.get("WINENTSYNC")); assertEquals("1", env.get("DXVK_ASYNC"))
        val other = Container("other")
        val untouched = EnvVars("CUSTOM=keep")
        WowLaunchEnvironment.applyRuntimeDefaults(other, untouched)
        assertEquals("CUSTOM=keep", untouched.toString())
    }

    @Test fun dxvkHelperUsesInlineConfigAndPreservesExplicitConfigFile() {
        val env = EnvVars()
        com.winlator.core.DXVKHelper.setEnvVars(context, com.winlator.core.KeyValueSet("version=custom,maxDeviceMemory=2048"), env)
        assertFalse(env.has("DXVK_CONFIG_FILE"))
        assertFalse(env.has("DXVK_FRAME_RATE"))
        assertEquals("dxgi.maxDeviceMemory = 2048;dxgi.maxSharedMemory = 2048;", env.get("DXVK_CONFIG"))
        env.put("DXVK_CONFIG_FILE", "G:\\dxvk.conf")
        com.winlator.core.DXVKHelper.setEnvVars(context, com.winlator.core.KeyValueSet("version=custom"), env)
        assertEquals("G:\\dxvk.conf", env.get("DXVK_CONFIG_FILE"))
    }

    @Test fun presentationDefaultsUseRendererModeWithoutSavingContainer() {
        val env = EnvVars("MESA_VK_WSI_PRESENT_MODE=mailbox UNRELATED=keep")
        WowLaunchEnvironment.applyPresentation("fifo", "", env)
        assertEquals("fifo", env.get("MESA_VK_WSI_PRESENT_MODE"))
        assertEquals("keep", env.get("UNRELATED"))
        WowLaunchEnvironment.applyPresentation("relaxed", "", env)
        assertEquals("relaxed", env.get("MESA_VK_WSI_PRESENT_MODE"))
    }
    @Test fun explicitDriverPresentationModeIsPreserved() {
        val env = EnvVars()
        WowLaunchEnvironment.applyPresentation("fifo", "", env, "mailbox")
        assertEquals("mailbox", env.get("MESA_VK_WSI_PRESENT_MODE"))
        WowLaunchEnvironment.applyPresentation("fifo", "MESA_VK_WSI_PRESENT_MODE=immediate", env, "mailbox")
        assertEquals("immediate", env.get("MESA_VK_WSI_PRESENT_MODE"))
    }
    @Test fun explicitPresentationOverrideWinsOverRendererMode() {
        val env = EnvVars()
        WowLaunchEnvironment.applyPresentation("fifo", "MESA_VK_WSI_PRESENT_MODE=mailbox", env)
        assertEquals("mailbox", env.get("MESA_VK_WSI_PRESENT_MODE"))
    }
    @Test fun newProfileHasNoHiddenMailboxOverride() {
        val defaults = WowContainerProfile.defaultsForNewContainer("/games/wow", false, false)
        assertFalse(EnvVars(defaults.getString("envVars")).has("MESA_VK_WSI_PRESENT_MODE"))
    }

    @Test fun explicitInvalidationDetectsSameSizeSameTimestampPayloadReplacement() {
        val file = temp.newFile().apply { writeText("DXVK_STATE_CACHE_PATH\u0000"); setLastModified(100000) }
        assertEquals(setOf("DXVK_STATE_CACHE_PATH"), WowLaunchEnvironment.supportedDxvkCacheVariables(listOf(file)))
        file.writeText("x".repeat(file.length().toInt())); file.setLastModified(100000)
        WowLaunchEnvironment.invalidateDxvkCapabilities()
        assertTrue(WowLaunchEnvironment.supportedDxvkCacheVariables(listOf(file)).isEmpty())
    }

    @Test fun `host directory uses executable subdirectory and the correct mapping among multiple drives`() {
        val c=container()
        val directory=WowLaunchEnvironment.gameDirectory(c)
        assertEquals(File("/games/wow/_classic_era_").canonicalFile,directory.host)
        assertEquals("G:\\_classic_era_",directory.windows)
        assertEquals("G:\\_classic_era_\\WowClassic-arm64.exe",c.executablePath)
    }
    @Test fun `resolver accepts root exe and lowercase drive with slash separators`() {
        val c=container().apply { executablePath="g:/WowClassic-arm64.exe" }
        assertEquals(File("/games/wow"),WowLaunchEnvironment.gameDirectory(c).host)
        assertEquals("G:\\",WowLaunchEnvironment.gameDirectory(c).windows)
    }
    @Test fun `unmapped relative and traversing executable paths are rejected`() {
        val c=container()
        for (bad in listOf("X:\\WowClassic-arm64.exe","WowClassic-arm64.exe","G:\\..\\WowClassic-arm64.exe")) {
            c.executablePath=bad
            assertThrows(Exception::class.java) { WowLaunchEnvironment.gameDirectory(c) }
        }
        c.executablePath="G:\\WowClassic-arm64.exe";c.drives="G:relative"
        assertThrows(IllegalArgumentException::class.java) { WowLaunchEnvironment.gameDirectory(c) }
    }
    @Test fun `supported DXVK cache variables target mapped game directory and Mesa uses host directory`() {
        val env=WowLaunchEnvironment.defaults(container(),setOf("DXVK_STATE_CACHE_PATH","DXVK_SHADER_CACHE_PATH"))
        assertEquals("G:\\_classic_era_",env.get("DXVK_STATE_CACHE_PATH"))
        assertEquals("G:\\_classic_era_",env.get("DXVK_SHADER_CACHE_PATH"))
        assertEquals("/games/wow/_classic_era_",env.get("MESA_SHADER_CACHE_DIR"))
        assertFalse(env.toString().contains("GameNativeCache"))
    }
    @Test fun `game location change recomputes cache paths without stale saved automatic env`() {
        val c=container();val before=WowLaunchEnvironment.defaults(c,setOf("DXVK_SHADER_CACHE_PATH"))
        c.drives="G:/new/game";c.executablePath="G:\\beta2\\WowClassic-arm64.exe"
        val after=WowLaunchEnvironment.defaults(c,setOf("DXVK_SHADER_CACHE_PATH"))
        assertNotEquals(before.get("MESA_SHADER_CACHE_DIR"),after.get("MESA_SHADER_CACHE_DIR"))
        assertEquals("/new/game/beta2",after.get("MESA_SHADER_CACHE_DIR"))
        assertEquals("G:\\beta2",after.get("DXVK_SHADER_CACHE_PATH"))
        assertFalse(c.envVars.contains("CACHE_PATH"))
    }
    @Test fun `merge gives user values including empty values priority over automatic and base env`() {
        val base=EnvVars("DXVK_STATE_CACHE_PATH=old WINEDEBUG=-all")
        val auto=WowLaunchEnvironment.defaults(container(),setOf("DXVK_STATE_CACHE_PATH","DXVK_SHADER_CACHE_PATH"))
        val user=EnvVars().apply { put("DXVK_STATE_CACHE_PATH","Z:\\my cache");put("DXVK_SHADER_CACHE_PATH","");put("MESA_SHADER_CACHE_DIR","/my gpu");put("WINEDEBUG","+all") }
        val env=WowLaunchEnvironment.merge(base,auto,user.toString())
        for(key in user) assertEquals(user.get(key),env.get(key))
    }
    @Test fun `selected shader cache build gets shader path and explicit state path is also retained`() {
        val c=container();installDxvk(c,listOf("DXVK_SHADER_CACHE_PATH"))
        c.envVars="DXVK_STATE_CACHE_PATH=custom-state"
        val env=EnvVars("DXVK_STATE_CACHE_PATH=legacy-default")
        WowLaunchEnvironment.apply(context,c,ContentsManager(context).apply { syncContents() },env)
        assertEquals("G:\\_classic_era_",env.get("DXVK_SHADER_CACHE_PATH"))
        assertEquals("custom-state",env.get("DXVK_STATE_CACHE_PATH"))
    }
    @Test fun `selected legacy build only gets state path and a changed payload invalidates capability cache`() {
        val c=container();val dll=installDxvk(c,listOf("DXVK_STATE_CACHE_PATH"))
        val manager=ContentsManager(context).apply { syncContents() }
        val env=EnvVars();WowLaunchEnvironment.apply(context,c,manager,env)
        assertEquals("G:\\_classic_era_",env.get("DXVK_STATE_CACHE_PATH"));assertFalse(env.has("DXVK_SHADER_CACHE_PATH"))
        dll.writeBytes("different payload DXVK_SHADER_CACHE_PATH\u0000".toByteArray())
        assertEquals(setOf("DXVK_SHADER_CACHE_PATH"),WowLaunchEnvironment.supportedDxvkCacheVariables(listOf(dll)))
    }
    @Test fun `capability scan recognizes names spanning read chunks and ignores unsupported names`() {
        val dll=temp.newFile("d3d11.dll")
        dll.writeBytes(ByteArray(65530) + "DXVK_SHADER_CACHE_PATH\u0000 DXVK_FAKE_CACHE_PATH\u0000".toByteArray())
        assertEquals(setOf("DXVK_SHADER_CACHE_PATH"),WowLaunchEnvironment.supportedDxvkCacheVariables(listOf(dll)))
    }
    @Test fun `GameNative default preserves original base cache behavior and explicit overrides`() {
        val c=container();WowLaunchEnvironment.saveLocation(context,WowLaunchEnvironment.GAMENATIVE_DEFAULT) { false }
        val saved=ContainerManager(context).getContainerById(c.id)
        assertEquals(WowLaunchEnvironment.GAMENATIVE_DEFAULT,WowLaunchEnvironment.location(saved))
        assertTrue(WowLaunchEnvironment.defaults(saved,setOf("DXVK_STATE_CACHE_PATH")).isEmpty)
        val env=EnvVars("DXVK_STATE_CACHE_PATH=original-app-cache")
        saved.envVars="MESA_SHADER_CACHE_DIR=/explicit-user"
        WowLaunchEnvironment.apply(context,saved,ContentsManager(context),env)
        assertEquals("original-app-cache",env.get("DXVK_STATE_CACHE_PATH"))
        assertEquals("/explicit-user",env.get("MESA_SHADER_CACHE_DIR"))
        assertEquals(c.wineVersion,saved.wineVersion);assertEquals(c.graphicsDriverConfig,saved.graphicsDriverConfig)
    }
    @Test fun `new containers default to the private GameNative cache location`() {
        val fresh = Container("wow_forever")
        fresh.loadData(WowContainerProfile.defaultsForNewContainer("/games/wow", false, false))
        assertEquals(WowLaunchEnvironment.GAMENATIVE_DEFAULT, WowLaunchEnvironment.location(fresh))
    }
    @Test fun `cache location is independent of env and survives Play preparation`() {
        val c=container()
        assertEquals(WowLaunchEnvironment.GAME_DIRECTORY,WowLaunchEnvironment.location(c))
        WowLaunchEnvironment.saveLocation(context,WowLaunchEnvironment.GAMENATIVE_DEFAULT) { false }
        val saved=ContainerManager(context).getContainerById(c.id)
        WowContainerProfile.prepareExistingContainer(saved,"/games/wow")
        assertEquals(WowLaunchEnvironment.GAMENATIVE_DEFAULT,WowLaunchEnvironment.location(saved))
        assertEquals(c.envVars,saved.envVars)
        assertThrows(IllegalStateException::class.java) { WowLaunchEnvironment.saveLocation(context,WowLaunchEnvironment.GAME_DIRECTORY) { true } }
    }
    @Test fun `non WoW container cache environment is untouched`() {
        val c=Container("other");val env=EnvVars("DXVK_STATE_CACHE_PATH=existing")
        WowLaunchEnvironment.apply(context,c,ContentsManager(context),env)
        assertEquals("DXVK_STATE_CACHE_PATH=existing",env.toString())
    }
    @Test fun `env add edit rename delete and escaping round trip`() {
        var entries=WowEnvironmentEditor.load("FIRST=old")
        entries=WowEnvironmentEditor.add(entries,WowEnvironmentEditor.Entry("SECOND","C:\\a b=1"))
        entries=WowEnvironmentEditor.edit(entries,"FIRST",WowEnvironmentEditor.Entry("RENAMED","new"))
        entries=WowEnvironmentEditor.delete(entries,"RENAMED")
        assertEquals(listOf(WowEnvironmentEditor.Entry("SECOND","C:\\a b=1")),WowEnvironmentEditor.load(WowEnvironmentEditor.serialize(entries)))
    }
    @Test fun `invalid env names and NUL are rejected`() {
        for(name in listOf("","1BAD","A B","A=B","A-B","NAME\n")) assertThrows(IllegalArgumentException::class.java) {
            WowEnvironmentEditor.add(emptyList(),WowEnvironmentEditor.Entry(name,"value"))
        }
        assertThrows(IllegalArgumentException::class.java) { WowEnvironmentEditor.validate(WowEnvironmentEditor.Entry("VALID","a\u0000b")) }
    }
    @Test fun `duplicate add rename and duplicate serialization are rejected`() {
        val entries=listOf(WowEnvironmentEditor.Entry("A","1"),WowEnvironmentEditor.Entry("B","2"))
        assertThrows(IllegalArgumentException::class.java) { WowEnvironmentEditor.add(entries,WowEnvironmentEditor.Entry("A","3")) }
        assertThrows(IllegalArgumentException::class.java) { WowEnvironmentEditor.edit(entries,"B",WowEnvironmentEditor.Entry("A","3")) }
        assertThrows(IllegalArgumentException::class.java) { WowEnvironmentEditor.serialize(entries+entries.first()) }
        assertEquals("2",WowEnvironmentEditor.load("A=1 A=2").single().value) // Legacy duplicates follow existing parser's last-value rule.
    }
    @Test fun `env Save persists only envVars and Play retains it`() {
        val c=container();val before=JSONObject(c.configFile.readText())
        val draft=WowEnvironmentEditor.add(WowEnvironmentEditor.load(c.envVars),WowEnvironmentEditor.Entry("CUSTOM_FLAG","a b"))
        val savedText=WowEnvironmentEditor.save(context,draft,c.envVars) { false }
        val after=JSONObject(c.configFile.readText())
        before.keys().forEach { key -> if(key!="envVars") assertEquals(key,before.get(key).toString(),after.get(key).toString()) }
        val saved=ContainerManager(context).getContainerById(c.id)
        assertEquals("a b",EnvVars(saved.envVars).get("CUSTOM_FLAG"))
        WowContainerProfile.prepareExistingContainer(saved,"/games/wow")
        assertEquals(savedText,saved.envVars)
        assertThrows(IllegalStateException::class.java) { WowEnvironmentEditor.save(context,draft,savedText) { true } }
        assertThrows(IllegalStateException::class.java) { WowEnvironmentEditor.save(context,draft,"stale-value") { false } }
    }
    @Test fun `final user overrides beat late runtime defaults and shell keeps user env`() {
        val c=container().apply { envVars="DISPLAY=:7 CUSTOM=chosen DXVK_SHADER_CACHE_PATH=G:\\\\cache" }
        val late=EnvVars("DISPLAY=:0 CUSTOM=base")
        WowLaunchEnvironment.mergeUserOverrides(c,late)
        assertEquals(":7",late.get("DISPLAY"));assertEquals("chosen",late.get("CUSTOM"))
        val shell=DeveloperShell().androidRequest(c,"echo test")
        assertEquals(":7",shell.environment["DISPLAY"]);assertEquals("chosen",shell.environment["CUSTOM"])
        assertEquals(listOf("/system/bin/sh","-c","echo test"),shell.command)
    }
    @Test fun `sensitive names are redacted in launch logs and diagnostics`() {
        val env="API_TOKEN=private-value NORMAL=public-value"
        assertFalse(com.winlator.core.envvars.EnvVarRedaction.redact(env).contains("private-value"))
        assertFalse(DeveloperShell.redactedEnvironment(env).contains("private-value"))
        assertTrue(DeveloperShell.redactedEnvironment(env).contains("public-value"))
    }

    @Test fun everyShippedProtonAndVegasBuildKeepsItsLaunchDefaults() {
        // r10/r11 Proton and r13 Vegas were missing from the hand-written lists in v0.1.47–v0.1.51.
        val protons = (7..12).map { "proton-11.0-90639.$it-arm64ec-$it" } + WowBundledComponents.PROTON_ID
        val vegas = (6..13).map { "Vegas-2.7.3-wow-arm64-async-gamenative-r$it-$it" } +
            (14..17).map { "Vegas-2.7.3-wow-arm64-hybrid-gamenative-r$it-$it" } + WowBundledComponents.DXVK_VERSION
        for (proton in protons) {
            val c = container().apply { envVars = ""; wineVersion = proton }
            val env = EnvVars()
            WowLaunchEnvironment.applyRuntimeDefaults(c, env)
            assertEquals(proton, "1", env.get("WINENTSYNC")); assertEquals(proton, "0", env.get("PROTON_NO_NTSYNC"))
            assertEquals(proton, "0", env.get("WINEESYNC")); assertEquals(proton, "0", env.get("WINEFSYNC"))
        }
        for (version in vegas) {
            val c = container().apply { envVars = ""; dxWrapper = "dxvk-$version"; dxWrapperConfig = "version=$version" }
            val env = EnvVars()
            WowLaunchEnvironment.applyRuntimeDefaults(c, env)
            assertEquals(version, "1", env.get("DXVK_ASYNC"))
        }
    }

    @Test fun lookalikeAndMismatchedRuntimeNamesDoNotReceiveBundledDefaults() {
        for (proton in listOf("proton-11.0-90639.6-arm64ec-6", "proton-11.0-90639.12-arm64ec-11",
                "proton-11.0-90639.12-arm64ec", "proton-11.0-90639.12-x86_64-12", "my-proton-11.0-90639.12-arm64ec-12")) {
            val c = container().apply { envVars = ""; wineVersion = proton }
            val env = EnvVars()
            WowLaunchEnvironment.applyRuntimeDefaults(c, env)
            assertFalse(proton, env.has("WINENTSYNC"))
        }
        for ((wrapper, version) in listOf(
                "dxvk-Vegas-2.7.3-wow-arm64-async-gamenative-r5-5" to "Vegas-2.7.3-wow-arm64-async-gamenative-r5-5",
                "dxvk-Vegas-2.7.3-wow-arm64-async-gamenative-r13-12" to "Vegas-2.7.3-wow-arm64-async-gamenative-r13-12",
                "dxvk-Vegas-2.7.3-wow-arm64-async-gamenative-r13-13" to "Vegas-2.7.3-wow-arm64-async-gamenative-r12-12",
                "dxvk-Vegas-2.7.3-other-r13-13" to "Vegas-2.7.3-other-r13-13")) {
            val c = container().apply { envVars = ""; dxWrapper = wrapper; dxWrapperConfig = "version=$version" }
            val env = EnvVars()
            WowLaunchEnvironment.applyRuntimeDefaults(c, env)
            assertFalse(wrapper, env.has("DXVK_ASYNC"))
        }
    }

    @Test fun theDeveloperPresentChoiceReachesTheGameAndTheCompositorAlike() {
        // Codex round 10 OPT10-04: the menu said Mailbox, the game swapchain got the driver config's FIFO.
        assertEquals("mailbox", WowLaunchEnvironment.resolvePresentMode("", "mailbox", "fifo", "fifo"))
        assertEquals("fifo", WowLaunchEnvironment.resolvePresentMode("", "fifo", "mailbox", "mailbox"))
        assertEquals("immediate", WowLaunchEnvironment.resolvePresentMode("MESA_VK_WSI_PRESENT_MODE=immediate", "mailbox", "fifo", "fifo"))
        assertEquals("mailbox", WowLaunchEnvironment.resolvePresentMode("", "", "mailbox", "fifo"))
        assertEquals("relaxed", WowLaunchEnvironment.resolvePresentMode("", "", "", "fifo_relaxed"))
        assertEquals("fifo", WowLaunchEnvironment.resolvePresentMode("", "", "", ""))
        val env = EnvVars()
        WowLaunchEnvironment.applyPresentation("fifo", "", env, "fifo", "mailbox")
        assertEquals("mailbox", env.get("MESA_VK_WSI_PRESENT_MODE"))
    }
}
