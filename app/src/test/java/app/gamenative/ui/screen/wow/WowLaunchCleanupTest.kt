package app.gamenative.ui.screen.wow

import android.app.Application
import com.winlator.container.Container
import com.winlator.core.envvars.EnvVars
import com.winlator.xenvironment.components.BionicProgramLauncherComponent
import io.mockk.mockk
import org.junit.Assert.*
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [29])
class WowLaunchCleanupTest {
    @get:Rule val temp = TemporaryFolder()
    @Test fun `removed desktop and browser entry points are absent while shell entry point remains`() {
        val source = listOf(File("src/main/java"), File("app/src/main/java")).first { it.isDirectory }
        val paths = listOf("app/gamenative/ui/PluviaMain.kt", "app/gamenative/ui/model/MainViewModel.kt",
            "app/gamenative/ui/screen/wow/WoWForeverScreen.kt", "app/gamenative/ui/screen/wow/WowComponentSelection.kt",
            "app/gamenative/ui/screen/xserver/XServerScreen.kt", "app/gamenative/ui/component/dialog/WowSettingsDialog.kt")
        val content = paths.joinToString("\n") { File(source, it).readText() }
        for (removed in listOf("launchDesktop", "desktopOnly", "WowDesktopLaunch", "onOpenContainer", "Browse Container Files", "Text(\"Open Container\")")) {
            assertFalse("Removed feature leaked into production: $removed", content.contains(removed))
        }
        assertFalse(File(source,"app/gamenative/ui/screen/wow/DeveloperContainerFiles.kt").exists())
        assertFalse(File(source,"app/gamenative/ui/screen/wow/DeveloperContainerScreen.kt").exists())
        assertTrue(content.contains("DeveloperShellDialog"))
    }
    @Test fun `normal XServer Wine start command still targets native ARM64 WoW without winhandler`() {
        val c = Container("wow_forever").apply {
            rootDir = temp.newFolder("container")
            executablePath = "G:\\_classic_era_\\WowClassic-arm64.exe"
            execArgs = "-d3d11"
            drives = "G:${temp.newFolder("game").path}"
        }
        val method = Class.forName("app.gamenative.ui.screen.xserver.XServerScreenKt").declaredMethods
            .single { it.name == "getWineStartCommand" }.apply { isAccessible = true }
        val command = method.invoke(null, RuntimeEnvironment.getApplication(), c.id, c, EnvVars(),
            mockk<BionicProgramLauncherComponent>(relaxed = true)) as String
        assertEquals(c.executablePath, command)
        assertFalse(command.contains("winhandler"))
        assertEquals("-d3d11", c.execArgs)
    }
}
