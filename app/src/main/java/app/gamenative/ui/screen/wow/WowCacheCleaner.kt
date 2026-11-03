package app.gamenative.ui.screen.wow

import android.content.Context
import app.gamenative.PluviaApp
import com.winlator.container.Container
import com.winlator.container.ContainerManager
import com.winlator.core.envvars.EnvVars
import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS

/** Explicit maintenance; never called by Play, never deletes a cache's containing directory. */
internal object WowCacheCleaner {
    enum class Kind(val label: String) { DXVK("DXVK"), VKD3D("VKD3D"), GPU("GPU / Mesa"), WOW("World of Warcraft Cache") }
    data class Plan(val dxvkRoots: List<File>, val gpuRoots: List<File>, val wowCache: File, val vkd3dRoots: List<File> = emptyList())
    data class Result(val entries: Int)
    private val mesaNames = listOf("mesa_shader_cache", "mesa_shader_cache_db", "mesa_shader_cache_sf")
    // VKD3D-Proton's pipeline cache: vkd3d-proton.cache (+ .write while a session merges new entries).
    private val vkd3dName = Regex("vkd3d-proton\\.cache(?:\\.write)?(?:\\.(?:tmp|bak))?", RegexOption.IGNORE_CASE)
    private val dxvkName = Regex(".+\\.dxvk(?:-cache|\\.bin|\\.lut)(?:\\.(?:tmp|bak))?", RegexOption.IGNORE_CASE)

    fun plan(container: Container, appCache: File): Plan {
        val game = WowLaunchEnvironment.gameDirectory(container).host
        require(game.name == WowClientDownloader.FLAVOR_DIR) { "Expected the WoW Classic Era executable directory." }
        val env = EnvVars(container.envVars)
        val defaultsInGame = WowLaunchEnvironment.location(container) == WowLaunchEnvironment.GAME_DIRECTORY
        val runtimeCache = File(container.rootDir, ".cache")
        val dxvkRoots = mutableListOf<File>()
        for (key in listOf("DXVK_STATE_CACHE_PATH", "DXVK_SHADER_CACHE_PATH")) {
            if (env.get(key).isNotBlank()) dxvkRoots += resolve(container, env.get(key))
            else if (defaultsInGame) dxvkRoots += game
            else if (key == "DXVK_STATE_CACHE_PATH") dxvkRoots += runtimeCache
            else {
                // Windows DXVK shader-cache default (Vegas) uses LOCALAPPDATA/dxvk.
                val local = if (env.get("LOCALAPPDATA").isNotBlank()) resolve(container, env.get("LOCALAPPDATA"))
                    else File(container.rootDir, ".wine/drive_c/users/xuser/AppData/Local")
                dxvkRoots += File(local, "dxvk")
            }
        }
        val gpuBase = when {
            env.get("MESA_SHADER_CACHE_DIR").isNotBlank() -> resolve(container, env.get("MESA_SHADER_CACHE_DIR"))
            defaultsInGame -> game
            env.get("XDG_CACHE_HOME").isNotBlank() -> resolve(container, env.get("XDG_CACHE_HOME"))
            env.get("HOME").isNotBlank() -> File(resolve(container, env.get("HOME")), ".cache")
            else -> runtimeCache
        }
        // VKD3D-Proton writes its cache to VKD3D_SHADER_CACHE_PATH, else to the working directory: the game's _forever_ folder.
        val vkd3dRoots = (listOfNotNull(env.get("VKD3D_SHADER_CACHE_PATH").takeIf { it.isNotBlank() }?.let { resolve(container, it) }) + game)
            .distinctBy { it.canonicalPath }
        return Plan(dxvkRoots.distinctBy { it.canonicalPath },
            listOf(gpuBase, appCache).flatMap { base -> mesaNames.map { File(base, it) } }.distinctBy { it.canonicalPath },
            File(game, "Cache"), vkd3dRoots)
    }

    private fun resolve(container: Container, value: String): File {
        require('\u0000' !in value) { "Invalid cache path." }
        if (value.startsWith("/")) return File(value)
        val windows = value.replace('/', '\\')
        require(Regex("^[A-Za-z]:\\\\.*").matches(windows)) { "Cache path must be absolute: $value" }
        val letter = windows.substring(0, 1)
        val parts = windows.substring(3).split('\\').filter { it.isNotBlank() && it != "." }
        require(parts.none { it == ".." || ':' in it }) { "Unsafe cache path." }
        val mapping = Container.drivesIterator(container.drives).asSequence().map { it.copyOf() }
            .singleOrNull { it[0].equals(letter, true) }
        val root = when {
            mapping != null -> File(mapping[1])
            letter.equals("C", true) -> File(container.rootDir, ".wine/drive_c")
            else -> error("Cache drive $letter: is not mapped.")
        }.canonicalFile
        val target = File(root, parts.joinToString("/")).canonicalFile
        require(target.toPath().startsWith(root.toPath())) { "Cache path escapes mapped drive." }
        return target
    }

    fun clear(context: Context, selected: Set<Kind>): Result {
        val active = { PluviaApp.keepAlive || PluviaApp.xEnvironment != null }
        check(!active()) { "Exit the game before clearing caches." }
        val container = ContainerManager(context).getContainerById(WowContainerProfile.CONTAINER_ID)
            ?: error("WoW container has not been created yet")
        return clear(plan(container, context.cacheDir), selected, active)
    }

    fun clear(plan: Plan, selected: Set<Kind>, active: () -> Boolean): Result {
        require(selected.isNotEmpty()) { "Select at least one cache." }
        check(!active()) { "Exit the game before clearing caches." }
        var deleted = 0
        fun remove(path: File) {
            check(!active()) { "A session started; cache cleanup stopped." }
            check(!Files.isSymbolicLink(path.toPath())) { "Refusing cache symlink: ${path.name}" }
            if (!Files.exists(path.toPath(), NOFOLLOW_LINKS)) return
            if (Files.isDirectory(path.toPath(), NOFOLLOW_LINKS)) {
                val children = path.listFiles() ?: error("Cannot read cache directory: ${path.path}")
                children.forEach(::remove)
            }
            check(!active()) { "A session started; cache cleanup stopped." }
            check(path.delete()) { "Unable to remove cache entry: ${path.path}" }
            deleted++
        }
        if (Kind.DXVK in selected) plan.dxvkRoots.forEach { root ->
            if (!root.exists()) return@forEach
            check(!Files.isSymbolicLink(root.toPath())) { "Refusing cache root symlink." }
            (root.listFiles() ?: error("Cannot read DXVK cache directory: ${root.path}"))
                .filter { dxvkName.matches(it.name) }.forEach(::remove)
        }
        if (Kind.VKD3D in selected) plan.vkd3dRoots.forEach { root ->
            if (!root.exists()) return@forEach
            check(!Files.isSymbolicLink(root.toPath())) { "Refusing cache root symlink." }
            (root.listFiles() ?: error("Cannot read VKD3D cache directory: ${root.path}"))
                .filter { vkd3dName.matches(it.name) }.forEach(::remove)
        }
        if (Kind.GPU in selected) plan.gpuRoots.forEach(::remove)
        if (Kind.WOW in selected) remove(plan.wowCache)
        return Result(deleted)
    }
}
