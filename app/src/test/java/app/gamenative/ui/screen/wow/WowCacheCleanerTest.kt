package app.gamenative.ui.screen.wow

import com.winlator.container.Container
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.nio.file.Files

@org.junit.runner.RunWith(org.robolectric.RobolectricTestRunner::class)
@org.robolectric.annotation.Config(application = android.app.Application::class, sdk = [29])
class WowCacheCleanerTest {
    @get:Rule val tmp = TemporaryFolder()
    private fun plan(): WowCacheCleaner.Plan {
        val root = tmp.newFolder()
        return WowCacheCleaner.Plan(listOf(root), listOf(File(root, "mesa_shader_cache")), File(root, "Cache"))
    }
    @Test fun dxvkClearsStateAndVegasShaderFilesOnly() {
        val p = plan(); val root = p.dxvkRoots.single()
        val names = listOf("Wow-ARM64.dxvk-cache", "1234.dxvk.bin", "1234.dxvk.lut")
        names.forEach { File(root,it).writeText("cache") }
        File(root,"WowClassic-arm64.exe").writeText("game")
        File(root,"Config.wtf").writeText("settings")
        val result = WowCacheCleaner.clear(p,setOf(WowCacheCleaner.Kind.DXVK)) { false }
        assertEquals(3,result.entries)
        names.forEach { assertFalse(File(root,it).exists()) }
        assertTrue(File(root,"WowClassic-arm64.exe").exists())
        assertTrue(File(root,"Config.wtf").exists())
    }
    @Test fun vkd3dClearsOnlyItsPipelineCacheFiles() {
        val p = plan().let { it.copy(vkd3dRoots = it.dxvkRoots) }; val root = p.vkd3dRoots.single()
        File(root,"vkd3d-proton.cache").writeText("c"); File(root,"vkd3d-proton.cache.write").writeText("c")
        File(root,"Wow-ARM64.dxvk-cache").writeText("dx11"); File(root,"WowClassic-arm64.exe").writeText("game")
        assertEquals(2, WowCacheCleaner.clear(p,setOf(WowCacheCleaner.Kind.VKD3D)) { false }.entries)
        assertFalse(File(root,"vkd3d-proton.cache").exists()); assertFalse(File(root,"vkd3d-proton.cache.write").exists())
        assertTrue(File(root,"Wow-ARM64.dxvk-cache").exists()); assertTrue(File(root,"WowClassic-arm64.exe").exists())
    }
    @Test fun gpuAndWowSelectionsRemainIndependent() {
        val p=plan()
        p.gpuRoots.single().mkdirs(); File(p.gpuRoots.single(),"shader").writeText("x")
        p.wowCache.mkdirs(); File(p.wowCache,"data").writeText("x")
        WowCacheCleaner.clear(p,setOf(WowCacheCleaner.Kind.GPU)) { false }
        assertFalse(p.gpuRoots.single().exists()); assertTrue(p.wowCache.exists())
        WowCacheCleaner.clear(p,setOf(WowCacheCleaner.Kind.WOW)) { false }
        assertFalse(p.wowCache.exists())
    }
    @Test fun activeSessionIsRejectedWithoutDeleting() {
        val p=plan();p.wowCache.mkdirs()
        assertThrows(IllegalStateException::class.java) { WowCacheCleaner.clear(p,setOf(WowCacheCleaner.Kind.WOW)) { true } }
        assertTrue(p.wowCache.exists())
    }
    @Test fun cleanupStopsWhenSessionBecomesActive() {
        val p=plan();p.wowCache.mkdirs();var checks=0
        assertThrows(IllegalStateException::class.java) { WowCacheCleaner.clear(p,setOf(WowCacheCleaner.Kind.WOW)) { ++checks > 1 } }
        assertTrue(p.wowCache.exists())
    }
    @Test fun symlinkCannotDeleteUnrelatedDirectory() {
        val p=plan();val outside=tmp.newFolder();File(outside,"keep").writeText("x")
        Files.createSymbolicLink(p.wowCache.toPath(),outside.toPath())
        assertThrows(IllegalStateException::class.java) { WowCacheCleaner.clear(p,setOf(WowCacheCleaner.Kind.WOW)) { false } }
        assertTrue(File(outside,"keep").exists())
    }
    @Test fun absentCachesAreSuccessfulNoOp() {
        assertEquals(0,WowCacheCleaner.clear(plan(),WowCacheCleaner.Kind.entries.toSet()) { false }.entries)
    }
    @Test fun pathPlanHonorsCacheLocationAndExplicitOverride() {
        val game=tmp.newFolder("game");File(game,"_classic_era_").mkdirs()
        val c=Container("wow_forever").apply {
            rootDir=tmp.newFolder("container")
            executablePath="G:\\_classic_era_\\WowClassic-arm64.exe"
            drives="G:${game.path}"
        }
        val appCache=tmp.newFolder("appcache")
        assertEquals(WowLaunchEnvironment.GAMENATIVE_DEFAULT,WowLaunchEnvironment.location(c))
        c.putExtra(WowLaunchEnvironment.LOCATION_KEY,WowLaunchEnvironment.GAME_DIRECTORY)
        val gamePlan=WowCacheCleaner.plan(c,appCache)
        assertEquals(File(game,"_classic_era_"),gamePlan.dxvkRoots.single())
        c.putExtra(WowLaunchEnvironment.LOCATION_KEY,WowLaunchEnvironment.GAMENATIVE_DEFAULT)
        assertTrue(WowCacheCleaner.plan(c,appCache).dxvkRoots.contains(File(c.rootDir,".cache")))
        c.envVars=com.winlator.core.envvars.EnvVars().apply { put("DXVK_SHADER_CACHE_PATH", "G:\\_classic_era_\\custom") }.toString()
        assertTrue(WowCacheCleaner.plan(c,appCache).dxvkRoots.any { it.name=="custom" })
    }
    @Test fun cachePlanRejectsTraversalWithoutTouchingConfig() {
        val game=tmp.newFolder("mapped")
        File(game,"_classic_era_").mkdirs()
        val c=Container("wow_forever").apply {
            rootDir=tmp.newFolder("prefix")
            executablePath="G:\\_classic_era_\\WowClassic-arm64.exe"
            drives="G:${game.path}"
            wineVersion="custom-proton"
            graphicsDriver="custom-turnip"
            envVars=com.winlator.core.envvars.EnvVars().apply { put("DXVK_SHADER_CACHE_PATH", "G:\\..\\outside") }.toString()
        }
        assertThrows(IllegalArgumentException::class.java) { WowCacheCleaner.plan(c,tmp.newFolder("cache")) }
        assertEquals("custom-proton", c.wineVersion)
        assertEquals("custom-turnip", c.graphicsDriver)
    }
}
