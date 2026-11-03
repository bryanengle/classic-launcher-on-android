package app.gamenative.ui.screen.wow

import org.junit.Assert.*
import org.junit.Test

/** Forever Launcher 1.0.0: players see upstream versions, not internal revisions. */
class WowComponentNamesTest {
    @Test fun bundledComponentsShowTheirUpstreamVersionOnly() {
        assertEquals("DXVK 3.1.1", WowComponentNames.display(WowBundledComponents.DXVK_NAME))
        assertEquals("DXVK 2.7.3", WowComponentNames.display("dxvk-Vegas-2.7.3-wow-arm64-hybrid-gamenative-r51-51"))
        assertEquals("VKD3D 3.0.1", WowComponentNames.display(WowBundledComponents.VKD3D_NAME))
        assertEquals("VKD3D 3.0.1", WowComponentNames.display("vkd3d-VKD3D-Proton-3.0.1-ARM64-r10-10"))
        assertEquals("Turnip 26.0.0 R8", WowComponentNames.display(WowBundledComponents.DRIVER_NAME))
        assertEquals("Proton 11.0 ARM64EC", WowComponentNames.display("proton-11.0-90639.31-arm64ec"))
        assertEquals("Proton 11.0 ARM64EC", WowComponentNames.display("proton-11.0-90639.31-arm64ec-31"))
        // Imported components keep their own names and descriptions.
        assertEquals("dxvk-custom-1", WowComponentNames.display("dxvk-custom-1"))
        assertEquals("their text", WowComponentNames.description("My DXVK 9", "their text", false))
        listOf(WowBundledComponents.DXVK_NAME, WowBundledComponents.VKD3D_NAME, WowBundledComponents.DRIVER_NAME,
            "proton-11.0-90639.31-arm64ec").forEach { id ->
            listOf(true, false).forEach { hu ->
                val d = WowComponentNames.description(id, "r99 build log", hu)
                assertFalse("$id: $d", Regex("""\br\d+\b|Codex|1\.1\.\d+""").containsMatchIn(d))
                assertTrue(WowComponentNames.isBundled(WowComponentNames.display(id)))
            }
        }
    }
}
