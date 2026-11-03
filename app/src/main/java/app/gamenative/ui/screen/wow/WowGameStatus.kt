package app.gamenative.ui.screen.wow

import java.io.File

/**
 * Filesystem snapshot; caller runs this on IO and publishes the result on Main. [buildInfo] says the file is there (launch
 * readiness, unchanged); [buildInfoValid] says it also reads as a build row with a product, which the Environment card needs
 * before it shows the line as ready (Codex UI16-02: an empty or broken file used to count as "All set").
 */
internal data class WowGameStatus(val executable: Boolean, val data: Boolean, val buildInfo: Boolean, val product: String,
                                  val buildInfoValid: Boolean = buildInfo && product.isNotBlank()) {
    val wrongGame get() = buildInfo && product.isNotBlank() && product != WowClientDownloader.TARGET_PRODUCT
    companion object {
        fun scan(root: File): WowGameStatus {
            val build = File(root, ".build.info")
            val present = build.exists()
            val product = if (build.isFile) WowClientDownloader.readBuildInfo(build)?.get("Product").orEmpty().trim() else ""
            return WowGameStatus(File(root, "${WowClientDownloader.FLAVOR_DIR}/${WowClientDownloader.EXECUTABLE}").exists(),
                File(root, "Data").let { it.exists() && it.listFiles()?.isNotEmpty() == true },
                present, product)
        }
    }
}
