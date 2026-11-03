package app.gamenative.ui.screen.wow

import java.io.File

/** Game graphics defaults are applied only to a new Config.wtf; existing player choices persist. */
internal object WowGameConfig {
    /**
     * [gameAccount]: 1-based WoW game account (WoW1, WoW2 ...) to preselect when the Battle.net account has several, or null for the
     * one WoW used last. It only moves WoW's own selection mark in an accountList that WoW wrote after a first login.
     */
    fun prepare(root: File, resolution: String? = null, graphicsApi: String = "D3D11", gameAccount: Int? = null, region: String? = null,
                locale: String? = null) {
        require(graphicsApi in setOf("D3D11", "D3D12"))
        val flavorDir = File(root, WowClientDownloader.FLAVOR_DIR)
        flavorDir.mkdirs()
        val flavorInfo = File(flavorDir, ".flavor.info")
        if (!flavorInfo.exists()) {
            flavorInfo.writeText("Product Flavor!STRING:0\n${WowClientDownloader.TARGET_PRODUCT}\n")
        }
        val configWtf = File(flavorDir, "WTF/Config.wtf")
        val defaults = linkedMapOf(
            // Without the Battle.net app the portal comes from here; a missing one stops WoW at a region picker.
            "portal" to "\"${region ?: WowClientDownloader.region(root)}\"",
            "agentUID" to "\"${WowClientDownloader.TARGET_PRODUCT}\"",
            "gxApi" to "\"$graphicsApi\"",
            "textLocale" to "\"${locale ?: "enUS"}\"",
            "audioLocale" to "\"${locale ?: "enUS"}\"",
            "gxMaximize" to "\"1\"",
            "gxWindowedResolution" to "\"1920x1080\"",
            "graphicsQuality" to "\"0\"",
            "ResampleQuality" to "\"0\"",
            "RenderScale" to "\"1\"",
            "farclip" to "\"1200\"",
            "horizonClip" to "\"1200\"",
            "RAIDfarclip" to "\"1200\"",
            "RAIDhorizonClip" to "\"1200\"",
            "shadowMode" to "\"0\"",
            "graphicsShadowQuality" to "\"0\"",
            "raidGraphicsShadowQuality" to "\"0\"",
            "worldBaseMip" to "\"0\"",
            "RAIDworldBaseMip" to "\"0\"",
            "graphicsTextureResolution" to "\"2\"",
            "raidGraphicsTextureResolution" to "\"2\"",
            "componentTextureLevel" to "\"0\"",
            "RAIDcomponentTextureLevel" to "\"0\"",
            "entityShadowFadeScale" to "\"0\"",
            "refraction" to "\"0\"",
            "groundEffectDensity" to "\"16\"",
            // The first-run Screen Narrator prompt otherwise waits for an answer in front of the login (jaredgei 2.3.0).
            "showScreenNarrationDialog" to "\"0\"",
            "accessibilityScreenNarrationEnabled" to "\"0\"",
            "GamePadEnable" to "\"1\"",
            "InputDeviceInterfaceStyle" to "\"1\""
        )
        if (!configWtf.exists()) {
            configWtf.parentFile?.mkdirs()
            writeAtomically(configWtf, defaults.entries.joinToString("\n") { "SET ${it.key} ${it.value}" } + "\n")
        } else {
            val before = configWtf.readLines()
            val after = selectGameAccount(prepareExistingLines(before, defaults, locale != null), gameAccount)
            if (before != after) writeAtomically(configWtf, after.joinToString("\n", postfix = "\n"))
        }
        WowDisplaySettings.alignGameConfig(configWtf, resolution)
    }


    /** What the game uses now, for the Settings Game tab: region code, game language, game account ("1", "2"). */
    data class Current(val region: String, val locale: String, val account: String)

    /**
     * The install's region (.build.info), Config.wtf's textLocale (else enUS) and the account WoW marks with "!" in accountList
     * (else WoW1, the only one an account with a single game account has).
     */
    fun current(root: File): Current {
        val lines = runCatching { File(root, "${WowClientDownloader.FLAVOR_DIR}/WTF/Config.wtf").readLines() }.getOrDefault(emptyList())
        fun value(name: String) = lines.firstNotNullOfOrNull { line ->
            Regex("^\\s*SET\\s+$name\\s+\"([^\"]*)\"", RegexOption.IGNORE_CASE).find(line)?.groupValues?.get(1)
        }
        val locale = value("textLocale")?.takeIf { it in WowRuntimeTuning.GAME_LOCALES } ?: "enUS"
        val account = value("accountList")?.split("|")?.firstOrNull { it.startsWith("!") }
            ?.let { Regex("^!WoW(\\d+)$", RegexOption.IGNORE_CASE).find(it)?.groupValues?.get(1) }?.takeIf { it == "1" || it == "2" } ?: "1"
        return Current(WowClientDownloader.region(root), locale, account)
    }

    /** A crash or power loss while writing must not leave a half-written Config.wtf (it holds the player's settings). */
    private fun writeAtomically(file: File, text: String) {
        val temp = File(file.parentFile, "${file.name}.tmp")
        temp.writeText(text)
        java.nio.file.Files.move(temp.toPath(), file.toPath(),
            java.nio.file.StandardCopyOption.ATOMIC_MOVE, java.nio.file.StandardCopyOption.REPLACE_EXISTING)
    }

    /**
     * WoW remembers the game account it logged into last as `SET accountList "!WoW1|"` (the "!" marks the one it picks; a player's
     * file had only that one entry). Marks WoW<[account]> instead, adding the entry (or the line) when it is not there yet; the
     * other entries stay. A list in an unexpected format is left as it is.
     */
    internal fun selectGameAccount(lines: List<String>, account: Int?): List<String> {
        if (account == null) return lines
        val wanted = "WoW$account"
        val pattern = Regex("^(\\s*SET\\s+accountList\\s+\")([^\"]*)(\".*)$", RegexOption.IGNORE_CASE)
        if (lines.none { pattern.matches(it) }) return lines + "SET accountList \"!$wanted|\""
        return lines.map { line ->
            val match = pattern.matchEntire(line) ?: return@map line
            val (prefix, list, suffix) = match.destructured
            val entries = if (list.isEmpty()) emptyList() else list.split("|").let { if (list.endsWith("|")) it.dropLast(1) else it }
            if (entries.any { it.removePrefix("!").isBlank() }) return@map line
            val names = entries.map { it.removePrefix("!") }.let { if (it.none { n -> n.equals(wanted, true) }) it + wanted else it }
            prefix + names.joinToString("") { (if (it.equals(wanted, true)) "!" else "") + it + "|" } + suffix
        }
    }

    /** [ownLocale]: a game language chosen in the launcher replaces the one in the file; otherwise the file's own stays. */
    private fun prepareExistingLines(lines: List<String>, defaults: Map<String, String>, ownLocale: Boolean = false): List<String> {
        // The portal follows the install's region (.build.info branch), so a region chosen in the launcher reaches the game.
        val required = linkedMapOf("gxApi" to defaults.getValue("gxApi"), "GamePadEnable" to "\"1\"",
            "InputDeviceInterfaceStyle" to "\"1\"", "portal" to defaults.getValue("portal"))
        if (ownLocale) { required["textLocale"] = defaults.getValue("textLocale"); required["audioLocale"] = defaults.getValue("audioLocale") }
        val metadata = listOf("agentUID", "textLocale", "audioLocale", "showScreenNarrationDialog", "accessibilityScreenNarrationEnabled")
            .filter { it !in required }
        val setting = Regex("^\\s*SET\\s+(\\S+)\\s+.*$", RegexOption.IGNORE_CASE)
        val seenRequired = mutableSetOf<String>()
        val seenMetadata = mutableSetOf<String>()
        val result = mutableListOf<String>()
        for (line in lines) {
            val key = setting.matchEntire(line)?.groupValues?.get(1)
            val owned = required.keys.firstOrNull { it.equals(key, ignoreCase = true) }
            metadata.firstOrNull { it.equals(key, ignoreCase = true) }?.let { seenMetadata += it }
            if (owned == null) result += line
            else if (seenRequired.add(owned)) result += "SET $owned ${required.getValue(owned)}"
        }
        required.forEach { (key, value) -> if (key !in seenRequired) result += "SET $key $value" }
        metadata.forEach { key -> if (key !in seenMetadata) result += "SET $key ${defaults.getValue(key)}" }
        return result
    }
}
