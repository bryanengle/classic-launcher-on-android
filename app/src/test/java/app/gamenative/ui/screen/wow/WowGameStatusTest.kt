package app.gamenative.ui.screen.wow

import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class WowGameStatusTest {
    @get:Rule val temp = TemporaryFolder()
    private fun game(product: String): File = temp.newFolder().apply {
        File(this, "_classic_era_/WowClassic-arm64.exe").apply { parentFile!!.mkdirs(); writeText("client") }
        File(this, "Data/data").apply { parentFile!!.mkdirs(); writeText("payload") }
        File(this, ".build.info").writeText("Active!DEC:1|Product!STRING:0\n1|$product\n")
    }
    @Test fun readyGameRetainsOriginalReadinessAndProductRules() {
        val status = WowGameStatus.scan(game("wow_classic_era"))
        assertTrue(status.executable); assertTrue(status.data); assertTrue(status.buildInfo); assertFalse(status.wrongGame)
    }
    @Test fun wrongProductIsRejected() { assertTrue(WowGameStatus.scan(game("wow")).wrongGame) }
    @Test fun incompleteGameReportsMissingFiles() {
        val root = temp.newFolder(); File(root, "Data").mkdir()
        val status = WowGameStatus.scan(root)
        assertFalse(status.executable); assertFalse(status.data); assertFalse(status.buildInfo); assertFalse(status.wrongGame)
    }
    @Test fun brokenBuildInfoIsPresentButNotValid() {
        // Codex UI16-02: these used to pass the Environment card's install-info line (and its "All set" badge).
        val broken = mapOf(
            "empty" to "", "header only" to "Active!DEC:1|Product!STRING:0\n", "garbage" to "\u0000\u0001 not a table",
            "no product column" to "Active!DEC:1|Version!STRING:0\n1|1.15.8\n", "empty product" to "Active!DEC:1|Product!STRING:0\n1|\n",
        )
        for ((name, text) in broken) {
            val root = game("wow_classic_era"); File(root, ".build.info").writeText(text)
            val status = WowGameStatus.scan(root)
            assertTrue(name, status.buildInfo); assertFalse(name, status.buildInfoValid); assertFalse(name, status.wrongGame)
        }
        val root = game("wow_classic_era"); File(root, ".build.info").apply { delete(); mkdir() }
        WowGameStatus.scan(root).let { assertTrue(it.buildInfo); assertFalse(it.buildInfoValid) }
    }
    @Test fun readableBuildInfoIsValidForEitherProduct() {
        assertTrue(WowGameStatus.scan(game("wow_classic_era")).buildInfoValid)
        WowGameStatus.scan(game("wow")).let { assertTrue(it.buildInfoValid); assertTrue(it.wrongGame) }
        val missing = temp.newFolder()
        assertFalse(WowGameStatus.scan(missing).buildInfoValid)
    }
    @Test fun freshSnapshotSeesRemovedPayload() {
        val root = game("wow_classic_era")
        assertTrue(WowGameStatus.scan(root).executable)
        File(root, "_classic_era_/WowClassic-arm64.exe").delete()
        assertFalse(WowGameStatus.scan(root).executable)
    }
}
