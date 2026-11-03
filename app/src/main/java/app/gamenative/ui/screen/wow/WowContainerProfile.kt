package app.gamenative.ui.screen.wow

import com.winlator.container.Container
import org.json.JSONObject

/** Ownership boundary between the WoW launcher and persistent container settings. */
internal object WowContainerProfile {
    const val CONTAINER_ID = "wow_forever"
    const val EXECUTABLE_PATH = "G:\\${WowClientDownloader.FLAVOR_DIR}\\${WowClientDownloader.EXECUTABLE}"

    /** Complete creation profile. Never pass this JSON to an existing container's loadData. */
    fun defaultsForNewContainer(gamePath: String, isAdreno8xx: Boolean, isSamsung: Boolean, fitsRedMagic: Boolean = false, fullscreen: String? = null): JSONObject {
        // Any phone starts at its Full screen resolution (half of its display); fitsRedMagic is the older RedMagic-only form.
        val start = fullscreen ?: if (fitsRedMagic) WowDisplaySettings.REDMAGIC else null
        val driverVersion = WowBundledComponents.DRIVER_NAME
        val samsungEnv = if (isSamsung) " FD_DEV_FEATURES=enable_tp_ubwc_flag_hint=1" else ""
        val envVars = "WRAPPER_MAX_IMAGE_COUNT=0 MESA_SHADER_CACHE_DISABLE=false MESA_SHADER_CACHE_MAX_SIZE=512MB WINEESYNC=0 WINEFSYNC=0 WINENTSYNC=1 PROTON_NO_NTSYNC=0 PULSE_LATENCY_MSEC=40$samsungEnv"

        return JSONObject().apply {
            // A display with the RedMagic aspect starts at its fullscreen resolution (managed, so the game config follows it).
            put("extraData", JSONObject().put(WowQuickSettings.FPS_ENABLED_EXTRA, "1").put(WowQuickSettings.FPS_TARGET_EXTRA, "30").put("wowTuDebugMigration", "1").put("pulseLatencyMigration", "1").put("redmagicHalfMigration", "1").apply { if (start != null) put(WowDisplaySettings.MANAGED, "1") })
            put("id", CONTAINER_ID)
            put("name", "WoW Classic Era")
            put("screenSize", start ?: "1280x720")
            put("envVars", envVars)
            put("graphicsDriver", "Wrapper")
            put("graphicsDriverVersion", driverVersion)
            put("graphicsDriverConfig", "version=$driverVersion,adrenotoolsTurnip=1,resourceType=buffer,bcnEmulation=auto,quality=high")
            put("displayRenderer", "vulkan")
            put("dxwrapper", WowBundledComponents.DXVK_ID)
            put("dxwrapperConfig", "version=${WowBundledComponents.DXVK_VERSION}")
            put("wineVersion", WowBundledComponents.PROTON_ID)
            put("containerVariant", Container.BIONIC)
            put("drives", "D:/storage/emulated/0/DownloadG:$gamePath")
            put("executablePath", EXECUTABLE_PATH)
            put("execArgs", "-d3d11")
            put("startupSelection", Container.STARTUP_SELECTION_AGGRESSIVE.toInt())
            put("wow64Mode", true)
        }
    }

    fun hasLegacyManagedTuDebug(container: Container): Boolean =
        container.getExtra("wowTuDebugMigration") != "1" &&
        com.winlator.core.envvars.EnvVars(container.envVars).get("TU_DEBUG") == "noconform,sysmem"

    /** Updates only launcher-owned fields; returns whether the caller needs to save. */
    /**
     * Once: a container the launcher manages that still sits on an earlier full-screen value (1344x608 or 1792x810, the RedMagic
     * presets) moves to this phone's Full screen resolution (720 high in the display's aspect, see WowDisplaySettings.fullscreenFor).
     * A resolution the player picked by hand is not touched. Returns true when the container changed.
     */
    fun moveToFullscreen(container: Container, fullscreen: String?): Boolean {
        if (fullscreen == null || container.getExtra("fullscreen720Migration") == "1") return false
        container.putExtra("fullscreen720Migration", "1")
        if (container.getExtra(WowDisplaySettings.MANAGED) == "1" &&
            container.screenSize in setOf(WowDisplaySettings.REDMAGIC, WowDisplaySettings.LEGACY_REDMAGIC)) container.screenSize = fullscreen
        return true
    }

    fun prepareExistingContainer(container: Container, gamePath: String): Boolean {

        val drives = buildString {
            var gameDriveAdded = false
            for (drive in container.drivesIterator()) {
                if (drive[0].equals("G", ignoreCase = true)) {
                    if (!gameDriveAdded) append("G:$gamePath")
                    gameDriveAdded = true
                } else {
                    append(drive[0]).append(':').append(drive[1])
                }
            }
            if (!gameDriveAdded) append("G:$gamePath")
        }
        var changed = false
        // The RedMagic preset was 1792x810 (two thirds of the display); it is 1344x608 now, exactly half, which the display scales by a
        // whole number. Once: only a managed container that still holds the exact old preset moves; every other resolution is kept.
        if (container.getExtra("redmagicHalfMigration") != "1") {
            if (container.getExtra(WowDisplaySettings.MANAGED) == "1" && container.screenSize == WowDisplaySettings.LEGACY_REDMAGIC) {
                container.screenSize = WowDisplaySettings.REDMAGIC
            }
            container.putExtra("redmagicHalfMigration", "1")
            changed = true
        }
        // One-time migration of the exact legacy launcher-owned default.
        // Modified user flags and later explicit edits are preserved.
        if (container.getExtra("wowTuDebugMigration") != "1") {
            val environment = com.winlator.core.envvars.EnvVars(container.envVars)
            if (environment.get("TU_DEBUG") == "noconform,sysmem") {
                environment.remove("TU_DEBUG")
                container.envVars = environment.toString()
            }
            container.putExtra("wowTuDebugMigration", "1")
            changed = true
        }
        // v1.0.1 beta5: PulseAudio client latency 40 ms (was 144). 144 made the voice-chat capture arrive in 288 ms bursts, the
        // voice SDK then restarted the capture every ~3 s. Only the exact old managed default is changed, once; later edits are kept.
        if (container.getExtra("pulseLatencyMigration") != "1") {
            val environment = com.winlator.core.envvars.EnvVars(container.envVars)
            if (environment.get("PULSE_LATENCY_MSEC") == "144") {
                environment.put("PULSE_LATENCY_MSEC", "40")
                container.envVars = environment.toString()
            }
            container.putExtra("pulseLatencyMigration", "1")
            changed = true
        }
        if (container.drives != drives) {
            container.drives = drives
            changed = true
        }
        if (container.executablePath != EXECUTABLE_PATH) {
            container.executablePath = EXECUTABLE_PATH
            changed = true
        }
        if (container.containerVariant != Container.BIONIC) {
            container.containerVariant = Container.BIONIC
            changed = true
        }
        // The ALSA server is gone: PulseAudio is the only audio backend (also carries the microphone).
        if (container.audioDriver != Container.DEFAULT_AUDIO_DRIVER) {
            container.audioDriver = Container.DEFAULT_AUDIO_DRIVER
            changed = true
        }
        // execArgs belongs to the saved profile. D3D11 remains the creation default and
        // the existing ensureGameConfig path still prepares Config.wtf for D3D11.
        return changed

    }
}
