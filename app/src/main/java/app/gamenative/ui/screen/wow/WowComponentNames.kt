package app.gamenative.ui.screen.wow

/**
 * What the player sees for the bundled components (since 1.0.0 of Forever Launcher): the upstream version only, no internal
 * revision. "Vegas-2.7.3-wow-arm64-hybrid-gamenative-r51" -> "DXVK 2.7.3", "proton-11.0-90639.31-arm64ec-31" -> "Proton 11.0 ARM64EC",
 * "VKD3D-Proton-3.0.1-ARM64-r10" -> "VKD3D 3.0.1", "TurnRIP A840 Performance v1.1.20" -> "TurnRIP 1.0". The exact
 * identifiers stay inside (updates, diagnostics, the source packages). Anything not recognised is shown unchanged.
 */
object WowComponentNames {
    private val dxvk = Regex("""(?:dxvk-)?Vegas-(\d+\.\d+\.\d+)-wow-arm64-(?:async|hybrid)-gamenative-r\d+(?:-\d+)?""", RegexOption.IGNORE_CASE)
    private val proton = Regex("""proton-(\d+\.\d+)-(?:\d+\.\d+-)?arm64ec(?:-r?\d+)?(?:-\d+)?""", RegexOption.IGNORE_CASE)
    private val vkd3d = Regex("""(?:vkd3d-)?(?:FrontMage-)?VKD3D-Proton-(\d+\.\d+\.\d+)-ARM64-r\d+(?:-\d+)?""", RegexOption.IGNORE_CASE)
    private val turnrip = Regex("""TurnRIP A840 Performance v1\.1\.\d+""", RegexOption.IGNORE_CASE)

    @JvmStatic
    fun display(id: String): String {
        val name = id.trim()
        if (name == WowBundledComponents.DRIVER_NAME) return "Turnip 26.0.0 R8"
        dxvk.matchEntire(name)?.let { return "DXVK ${it.groupValues[1]}" }
        proton.matchEntire(name)?.let { return "Proton ${it.groupValues[1]} ARM64EC" }
        vkd3d.matchEntire(name)?.let { return "VKD3D ${it.groupValues[1]}" }
        if (turnrip.matches(name)) return "TurnRIP 1.0"
        return id
    }

    /**
     * A short player-facing description for a bundled component, or [original] for anything else (an imported one). The packages'
     * own descriptions are build logs (revisions, test rounds); they stay inside the package.
     */
    @JvmStatic
    /** [code]: the UI language; other than en/hu the English description is translated (WowI18n). */
    fun description(id: String, original: String, hungarian: Boolean, code: String = if (hungarian) "hu" else "en"): String =
        if (code == "hu" || code == "en") describe(id, original, code == "hu") else WowI18n.translate(code, describe(id, original, false))

    private fun describe(id: String, original: String, hungarian: Boolean): String = when (display(id)) {
        "Turnip 26.0.0 R8" -> "Mesa Turnip Vulkan driver for the Pocket 6 Adreno 740. Experimental Classic Era build."
        "DXVK 3.1.1" -> if (hungarian) "DXVK 3.1.1 a DirectX 11-hez, Adrenóra hangolva. Ez a beépített és alapértelmezett."
            else "DXVK 3.1.1 for DirectX 11, tuned for Adreno. The bundled default."
        "DXVK 2.7.3" -> if (hungarian) "DXVK 2.7.3 a DirectX 11-hez. Régebbi változat, már nincs beépítve."
            else "DXVK 2.7.3 for DirectX 11. An older build, no longer bundled."
        "Proton 11.0 ARM64EC" -> if (hungarian) "Proton 11.0 ARM64EC (Wine), Androidra készült ntsync-kel." else "Proton 11.0 ARM64EC (Wine) with ntsync for Android."
        "VKD3D 3.0.1" -> if (hungarian) "VKD3D 3.0.1 a DirectX 12-höz, Windows ARM64-re fordítva." else "VKD3D 3.0.1 for DirectX 12, built for Windows ARM64."
        "TurnRIP 1.0" -> if (hungarian) "Mesa Turnip Vulkan-driver Adreno 8xx-re, a WoW-hoz hangolva." else "Mesa Turnip Vulkan driver for Adreno 8xx, tuned for WoW."
        else -> original
    }

    /** True for the display names of the bundled components (their package descriptions are not shown). */
    @JvmStatic
    fun isBundled(displayName: String): Boolean = displayName in setOf("DXVK 2.7.3", "DXVK 3.1.1", "Proton 11.0 ARM64EC", "VKD3D 3.0.1", "TurnRIP 1.0", "Turnip 26.0.0 R8")
}
