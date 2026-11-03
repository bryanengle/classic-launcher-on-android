package app.gamenative.ui.screen.wow

import android.content.Context
import com.winlator.container.Container
import com.winlator.contents.ContentsManager
import com.winlator.core.envvars.EnvVars
import com.winlator.xenvironment.ImageFs
import java.io.File
import java.security.MessageDigest

internal object WowVkd3dRuntime {
    private const val BASELINE = ".frontmage-original-d3d12"
    fun prepare(context: Context, container: Container, imageFs: ImageFs) = synchronized(ContentsManager.getLocalInstallLock()) {
        if (container.id != WowContainerProfile.CONTAINER_ID) return@synchronized
        val root = container.rootDir
        val system32 = File(imageFs.rootDir, "${ImageFs.WINEPREFIX}/drive_c/windows/system32")
        val baseline = File(root, BASELINE)
        if (WowVkd3dComponents.api(container) == WowVkd3dComponents.DX11 && !baseline.exists() &&
            !File(root, ".frontmage-dll-transaction").exists()) return@synchronized
        WowVkd3dComponents.idle { false } // The launcher owns this preparation; reject actual Wine processes.
        WowD3d12DllTransaction.recover(root, system32)
        retireStaleBaseline(root, system32, container.wineVersion)
        if (WowVkd3dComponents.api(container) == WowVkd3dComponents.DX11) {
            restoreBaseline(root, system32)
            return@synchronized
        }
        WowVkd3dComponents.checkRuntime(context, container)
        val (_, payloads) = WowVkd3dComponents.selected(context, container)
        val report = WowVkd3dCapabilities.probe(context, container)
        require(report.compatible) { report.summary() }
        val client = File(WowLaunchEnvironment.gameDirectory(container).host, WowClientDownloader.EXECUTABLE)
        WowArm64Pe.validate(client, dll = false)
        val dxgiProfile = WowDxvkSelector.currentProfile(container.dxWrapper, container.dxWrapperConfig, WowDxvkSelector.installedProfiles(context))
            ?: error("Selected DXGI is not installed; no fallback was used.")
        val dxgiSource = dxgiProfile.fileList.singleOrNull { it.target == "\${system32}/dxgi.dll" } ?: error("Selected DXVK has no DXGI.")
        WowVkd3dComponents.validateDxgi(context, dxgiProfile, dxgiSource.source)
        WowArm64Pe.validate(File(system32, "dxgi.dll"), setOf("CreateDXGIFactory1"))
        takeBaseline(root, system32, container.wineVersion)
        fun hash(file: File): ByteArray {
            val digest = MessageDigest.getInstance("SHA-256")
            file.inputStream().use { input -> val b = ByteArray(65536); while (true) { val n = input.read(b); if (n < 0) break; digest.update(b, 0, n) } }
            return digest.digest()
        }
        if (payloads.any { (name, source) -> !File(system32, name).isFile || !hash(source).contentEquals(hash(File(system32, name))) }) {
            WowD3d12DllTransaction.replace(root, system32, payloads)
        }
        File(root, "frontmage-vkd3d-prerequisites.json").writeText(report.json.toString(2))
    }
    /**
     * A snapshot of another Wine version is retired, but first its DLLs go back (AUDIT13-APP-01): a runtime switch leaves the thin
     * prefix's upper system32 as it was, VKD3D included, so a new snapshot taken over it would record VKD3D as the original and a
     * later DX11 launch would put VKD3D back instead of Wine's own D3D12.
     */
    internal fun retireStaleBaseline(root: File, system32: File, wineVersion: String) {
        val baseline = File(root, BASELINE)
        val baselineVersion = File(baseline, "wine-version")
        if (!baseline.exists() || (baselineVersion.exists() && baselineVersion.readText() == wineVersion)) return
        if (File(baseline, "dlls/state.json").isFile) WowD3d12DllTransaction.replace(root, system32, originals(baseline))
        check(com.winlator.container.ContainerFiles.deleteRecursively(baseline)) { "Unable to retire the old runtime DLL snapshot." }
    }

    /** DX11: the original DLLs go back and the snapshot is dropped. */
    internal fun restoreBaseline(root: File, system32: File) {
        val baseline = File(root, BASELINE)
        if (!baseline.exists()) return
        WowD3d12DllTransaction.replace(root, system32, originals(baseline))
        check(com.winlator.container.ContainerFiles.deleteRecursively(baseline))
    }

    /**
     * The snapshot's DLLs, except a recorded file that is not Wine's own: the bundled Proton and its prefix carry no d3d12.dll or
     * d3d12core.dll, so such a file in system32 was VKD3D, recorded by a snapshot taken over it before this fix; it goes, not back.
     */
    private fun originals(baseline: File): Map<String, File?> =
        WowD3d12DllTransaction.fromSnapshot(File(baseline, "dlls")).mapValues { (_, file) ->
            file?.takeIf { java.nio.file.Files.isSymbolicLink(it.toPath()) || isWineDll(it) }
        }

    /** Wine's builtin and placeholder PE files carry their marker right after the DOS header. */
    internal fun isWineDll(file: File): Boolean {
        val head = ByteArray(96)
        val read = file.inputStream().use { it.readNBytes(head, 0, head.size) }
        if (read < 96) return false
        val text = String(head, 64, 32, Charsets.ISO_8859_1)
        return text.startsWith("Wine builtin DLL") || text.startsWith("Wine placeholder DLL")
    }

    /** DX12: keeps the original DLLs of this Wine version once, before VKD3D replaces them. */
    internal fun takeBaseline(root: File, system32: File, wineVersion: String) {
        val baseline = File(root, BASELINE)
        if (baseline.exists()) return
        val stage = File(root, "$BASELINE.pending")
        if (stage.exists()) check(com.winlator.container.ContainerFiles.deleteRecursively(stage))
        check(stage.mkdir()); WowD3d12DllTransaction.snapshot(system32, File(stage, "dlls"))
        File(stage, "wine-version").writeText(wineVersion)
        check(stage.renameTo(baseline)) { "Unable to retain original Wine D3D12 DLLs." }
    }

    fun environment(container: Container, env: EnvVars) {
        if (container.id != WowContainerProfile.CONTAINER_ID || WowVkd3dComponents.api(container) != WowVkd3dComponents.DX12) return
        // Use actual driver version/identity for DX12 instead of the generic wrapper's
        // configured presentation values. This affects only this launch, not saved settings.
        listOf("WRAPPER_VK_VERSION", "WRAPPER_DEVICE_NAME", "WRAPPER_DEVICE_ID", "WRAPPER_VENDOR_ID").forEach(env::remove)
        env.put("WINEDLLOVERRIDES", nativeOverrides(env.get("WINEDLLOVERRIDES")))
        if (!env.has("VKD3D_SHADER_CACHE_PATH") && WowLaunchEnvironment.location(container) == WowLaunchEnvironment.GAME_DIRECTORY) {
            env.put("VKD3D_SHADER_CACHE_PATH", WowLaunchEnvironment.gameDirectory(container).windows)
        }
    }
    fun nativeOverrides(current: String): String {
        val required = setOf("d3d12", "d3d12core", "dxgi")
        val kept = current.split(';').mapNotNull { group ->
            val parts = group.split('=', limit = 2)
            if (parts.size != 2) return@mapNotNull group.takeIf { it.isNotBlank() }
            val names = parts[0].split(',').filter { it.trim().removePrefix("*").lowercase() !in required }
            if (names.isEmpty()) null else names.joinToString(",") + "=" + parts[1]
        }
        return (kept + "d3d12,d3d12core,dxgi=n").joinToString(";")
    }
}
