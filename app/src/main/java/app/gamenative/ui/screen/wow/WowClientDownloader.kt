package app.gamenative.ui.screen.wow

import app.gamenative.utils.Net
import okhttp3.Request
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.OutputStream
import java.nio.ByteBuffer
import java.security.DigestOutputStream
import java.security.MessageDigest
import java.util.Locale
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.zip.Inflater

object WowClientDownloader {
    const val FLAVOR_DIR = "_classic_era_"
    const val EXECUTABLE = "WowClassic-arm64.exe"
    private const val PLATFORM_ARCH = "arm64"
    private const val EXCLUDED_ARCH = "x86_64"

    const val TARGET_PRODUCT = "wow_classic_era"

    private const val INDEX_DOWNLOAD_THREADS = 16
    /** Large files and archive members download as parallel ranges of this size: one TCP stream to the CDN is far slower than the line. */
    private const val SEGMENT_BYTES = 4L * 1024 * 1024
    private const val SEGMENT_THREADS = 6
    // The columns the Battle.net app writes; a .build.info made here (a new install) gets them all.
    private val BUILD_INFO_HEADER = listOf("Branch!STRING:0", "Active!DEC:1", "Build Key!HEX:16", "CDN Key!HEX:16",
        "Install Key!HEX:16", "IM Size!DEC:4", "CDN Path!STRING:0", "CDN Hosts!STRING:0", "CDN Servers!STRING:0", "Tags!STRING:0",
        "Armadillo!STRING:0", "Last Activated!STRING:0", "Version!STRING:0", "KeyRing!HEX:16", "Product!STRING:0")
    private val EU_COUNTRIES = setOf("AT", "BE", "BG", "CH", "CY", "CZ", "DE", "DK", "EE", "ES", "FI", "FR", "GB", "GR", "HR", "HU",
        "IE", "IS", "IT", "LT", "LU", "LV", "MT", "NL", "NO", "PL", "PT", "RO", "RU", "SE", "SI", "SK", "UA", "TR")

    data class VersionCheckResult(
        val localVersion: String,
        val remoteVersion: String,
        val isOutdated: Boolean,
        val remoteBuildConfig: String = "",
        val remoteCdnConfig: String = "",
        val product: String = TARGET_PRODUCT,
        val remoteCdnHosts: List<String> = emptyList(),
        val remoteCdnPath: String = "tpr/wow",
        /** The game streams missing assets from these URLs; without them in .build.info it crashes at startup. */
        val remoteCdnServers: List<String> = emptyList(),
    )

    /** Products listed in a potentially shared Battle.net installation. */
    fun installedProducts(buildInfo: File): Set<String> = runCatching {
        val lines = buildInfo.readLines().filter { it.isNotBlank() }
        val header = lines.first().split("|").map { it.substringBefore("!") }
        lines.drop(1).mapNotNull { header.zip(it.split("|")).toMap()["Product"]?.takeIf { p -> p.isNotBlank() } }.toSet()
    }.getOrDefault(emptySet())

    /**
     * The install's region: the Branch of the Classic Era row, else the phone's region with Europe as fallback. It
     * names the .build.info branch and the Config.wtf portal; without a portal WoW stops at a region picker.
     */
    fun region(gameRoot: File): String {
        val rows = runCatching {
            val lines = File(gameRoot, ".build.info").readLines().filter { it.isNotBlank() }
            val header = lines.first().split("|").map { it.substringBefore("!") }
            lines.drop(1).map { header.zip(it.split("|")).toMap() }
        }.getOrDefault(emptyList())
        val branch = rows.firstOrNull { it["Product"] == TARGET_PRODUCT }
            ?.get("Branch")?.lowercase(Locale.ROOT)?.takeIf { it in setOf("us", "eu", "kr", "tw", "cn") }
        return branch ?: when (Locale.getDefault().country.uppercase(Locale.ROOT)) {
            "KR" -> "kr"
            "TW", "HK" -> "tw"
            in EU_COUNTRIES -> "eu"
            else -> "eu"
        }
    }

    /**
     * Sets up Classic Era in [gameRoot], preserving other products' build rows. Downloads the build configs, the archive indexes and the ARM64 client (about 250 MB), then writes the
     * [TARGET_PRODUCT] row of .build.info last, so an interrupted install simply runs again. Game assets not on disk are streamed
     * from the CDN by the game while playing.
     */
    /** The regions an install can belong to (its .build.info branch and Config.wtf portal). */
    val REGIONS = listOf("eu", "us", "kr", "tw")

    /**
     * Progress texts as English/Hungarian pairs. The downloader reports the English text; the launcher shows it in the UI
     * language through WowI18n (assets/i18n, Hungarian included), which matches these templates.
     */
    internal object Text {
        fun checking() = "Checking the latest World of Warcraft build..." to "A legfrissebb World of Warcraft build ellenőrzése..."
        fun manifest(version: String) = "Fetching build $version manifest..." to "A(z) $version build jegyzékének letöltése..."
        fun reading(version: String) = "Reading build $version manifest..." to "A(z) $version build jegyzékének olvasása..."
        fun lookingUp() = "Looking up client files..." to "Kliensfájlok keresése..."
        fun indexes(done: Int, total: Int, speed: String) =
            "Syncing archive indexes ($done/$total) · $speed MB/s" to "Csomag-tartalomjegyzékek letöltése ($done/$total) · $speed MB/s"
        fun downloading(name: String, index: Int, count: Int, done: String, size: String, speed: String) =
            "Downloading $name ($index/$count): $done / $size MB · $speed MB/s" to "$name letöltése ($index/$count): $done / $size MB · $speed MB/s"
        fun buildInfo() = "Updating build information..." to "Buildinformáció frissítése..."
    }

    /** Bytes and speed of one download phase; [due] lets the status line update a few times a second, not per packet. */
    internal class TransferMeter(private val clock: () -> Long = System::nanoTime) {
        private val start = clock()
        private val bytes = AtomicLong()
        private val lastEmit = AtomicLong(Long.MIN_VALUE / 2)
        fun add(n: Long) { bytes.addAndGet(n) }
        val total: Long get() = bytes.get()
        fun speed(): String {
            val seconds = (clock() - start) / 1e9
            return mb(if (seconds < 0.2) 0L else (bytes.get() / seconds).toLong())
        }
        fun due(intervalMs: Long = 300): Boolean {
            val now = clock()
            val last = lastEmit.get()
            return now - last >= intervalMs * 1_000_000 && lastEmit.compareAndSet(last, now)
        }
    }

    internal fun mb(bytes: Long): String = String.format(Locale.ROOT, "%.1f", bytes / 1048576.0)

    /**
     * Total size of the game data for Windows ARM64 in [locale] (Blizzard's download manifest of the installed build), in bytes.
     * The game itself streams these files while it runs; the launcher compares this with what is on disk. Live wowf
     * 1.60.1.70707: 47.1 GB (priority 0: 0.39 GB, 1: 36.4 GB, 2: 10.3 GB; optional HighRes textures, 37 GB, are left out like
     * Battle.net does). Downloads the manifest once (about 46 MB).
     */
    fun gameDataSize(gameRoot: File, locale: String): Long {
        val row = activeBuild(File(gameRoot, ".build.info"))
        val cdn = Cdn(row.getValue("CDN Hosts").split(" ").filter { it.isNotBlank() }, row.getValue("CDN Path"), row["Branch"])
        val buildConfig = parseConfig(String(cdn.requireConfig(row.getValue("Build Key"))))
        val encoded = cdn.require("data", buildConfig.getValue("download")[1])
        val counter = DownloadManifestCounter(setOf("Windows", PLATFORM_ARCH, locale, row["Branch"].orEmpty().uppercase(Locale.ROOT), "speech", "text"))
        blteDecode(encoded, counter)
        return counter.total()
    }

    /**
     * Reads a decoded download manifest ("DL") as it streams by and sums the sizes of the entries that carry the [selected] tags:
     * for every tag type that has a selected tag, the entry must have one of them (types without a selected tag do not filter).
     * Streaming keeps the 50+ MB decoded manifest out of memory; only the sizes and the selected tag masks are kept.
     */
    internal class DownloadManifestCounter(private val selected: Set<String>, private val keep: Boolean = false) : OutputStream() {
        /** With [keep]: every entry's 16-byte encoding key and priority, for [selectedEntries]. */
        private var keys = ByteArray(0)
        private var priorities = ByteArray(0)
        private val head = ByteArrayOutputStream()
        private var headerSize = 11
        private var entrySize = 0
        private var ekeySize = 0
        private var count = 0
        private var tagCount = 0
        private var maskSize = 0
        private var sizes = LongArray(0)
        private var entry = ByteArray(0)
        private var entryPos = 0
        private var entryIndex = 0
        private var tagsRead = 0
        private val name = ByteArrayOutputStream()
        private var typeBytes = 0
        private var type = 0
        private var mask: ByteArray? = null
        private var maskPos = 0
        private var stage = 0 // 0 header, 1 entries, 2 tag name, 3 tag type, 4 tag mask, 5 done
        private val masks = HashMap<Int, MutableList<ByteArray>>()
        /** Masks of the tags that are not selected, by type: optional content (HighRes, Alternate) is left out unless chosen. */
        private val otherMasks = HashMap<Int, MutableList<ByteArray>>()

        override fun write(b: Int) = write(byteArrayOf(b.toByte()), 0, 1)

        override fun write(data: ByteArray, off: Int, len: Int) {
            var i = off
            val end = off + len
            while (i < end) {
                when (stage) {
                    0 -> {
                        head.write(data[i++].toInt())
                        val h = head.toByteArray()
                        if (h.size == 11) {
                            check(h[0] == 'D'.code.toByte() && h[1] == 'L'.code.toByte()) { "Bad download manifest" }
                            val version = h[2].toInt() and 0xFF
                            headerSize = 11 + (if (version >= 2) 1 else 0) + (if (version >= 3) 4 else 0)
                        }
                        if (h.size == headerSize) {
                            val buffer = ByteBuffer.wrap(h)
                            ekeySize = h[3].toInt() and 0xFF
                            val checksum = h[4].toInt() != 0
                            count = buffer.getInt(5)
                            tagCount = buffer.getShort(9).toInt() and 0xFFFF
                            val flagBytes = if (headerSize > 11) h[11].toInt() and 0xFF else 0
                            entrySize = ekeySize + 5 + 1 + (if (checksum) 4 else 0) + flagBytes
                            maskSize = (count + 7) / 8
                            sizes = LongArray(count)
                            if (keep) { keys = ByteArray(count * 16); priorities = ByteArray(count) }
                            entry = ByteArray(entrySize)
                            stage = if (count > 0) 1 else 2
                        }
                    }
                    1 -> {
                        val n = minOf(entrySize - entryPos, end - i)
                        System.arraycopy(data, i, entry, entryPos, n)
                        i += n; entryPos += n
                        if (entryPos == entrySize) {
                            val p = ekeySize
                            if (keep) { System.arraycopy(entry, 0, keys, entryIndex * 16, minOf(16, ekeySize)); priorities[entryIndex] = entry[p + 5] }
                            sizes[entryIndex++] = ((entry[p].toLong() and 0xFF) shl 32) or (ByteBuffer.wrap(entry, p + 1, 4).int.toLong() and 0xFFFFFFFFL)
                            entryPos = 0
                            if (entryIndex == count) stage = if (tagCount > 0) 2 else 5
                        }
                    }
                    2 -> {
                        val b = data[i++]
                        if (b.toInt() == 0) { stage = 3; typeBytes = 0; type = 0 } else name.write(b.toInt())
                    }
                    3 -> {
                        type = (type shl 8) or (data[i++].toInt() and 0xFF)
                        if (++typeBytes == 2) {
                            val tag = String(name.toByteArray(), Charsets.UTF_8)
                            name.reset()
                            mask = ByteArray(maskSize).also { (if (tag in selected) masks else otherMasks).getOrPut(type) { mutableListOf() } += it }
                            maskPos = 0
                            stage = 4
                        }
                    }
                    4 -> {
                        val n = minOf(maskSize - maskPos, end - i)
                        mask?.let { System.arraycopy(data, i, it, maskPos, n) }
                        i += n; maskPos += n
                        if (maskPos == maskSize) stage = if (++tagsRead == tagCount) 5 else 2
                    }
                    else -> i = end
                }
            }
        }

        /** The selected entries: 16-byte keys (concatenated), sizes and priorities. Needs [keep]. */
        class Entries(val keys: ByteArray, val sizes: LongArray, val priorities: ByteArray) { val count get() = sizes.size }

        fun selectedEntries(): Entries {
            check(keep && stage == 5) { "Incomplete download manifest" }
            val chosen = (0 until count).filter { included(it) }
            val k = ByteArray(chosen.size * 16); val s = LongArray(chosen.size); val p = ByteArray(chosen.size)
            chosen.forEachIndexed { n, i -> System.arraycopy(keys, i * 16, k, n * 16, 16); s[n] = sizes[i]; p[n] = priorities[i] }
            return Entries(k, s, p)
        }

        /**
         * Battle.net's rule: for a tag type with a selected tag (platform, architecture, language, region, category) the entry
         * needs one of them; for a type with none selected (HighRes, Alternate, Talebound) an entry carrying any of its tags is
         * optional content and left out. Live wowf: 47.1 GB, the size of a Battle.net install on a PC (HighRes alone is 37 GB).
         */
        private fun included(index: Int): Boolean {
            val byte = index ushr 3
            val bit = 0x80 ushr (index and 7)
            for (group in masks.values) if (group.none { it[byte].toInt() and bit != 0 }) return false
            for ((type, group) in otherMasks) if (type !in masks && group.any { it[byte].toInt() and bit != 0 }) return false
            return true
        }

        fun total(): Long {
            check(stage == 5) { "Incomplete download manifest" }
            var sum = 0L
            for (index in 0 until count) if (included(index)) sum += sizes[index]
            return sum
        }
    }

    fun install(gameRoot: File, region: String, onStatus: (String) -> Unit) {
        require(region in REGIONS) { "Unknown region: $region" }
        onStatus(Text.checking().first)
        val target = fetchRemote(fast = false, localRow = null, region = region)
            ?: throw IOException("Couldn't reach Blizzard's version server. Check your connection.")
        check(target.remoteBuildConfig.isNotBlank()) { "Blizzard's version server returned no build." }
        listOf("Data/data", "Data/indices", "Data/config").forEach { File(gameRoot, it).mkdirs() }
        updateGame(gameRoot, target, onStatus, region)
    }

    fun readBuildInfo(buildInfo: File): Map<String, String>? =
        runCatching { activeBuild(buildInfo) }.getOrNull()

    /**
     * @param fast launch-path variant: short timeouts and no separate /cdns request (the installed .build.info already
     * lists the CDN hosts), so an offline or slow network delays an automatic launch by about two seconds at most.
     */
    fun checkVersion(gameRoot: File, fast: Boolean = false): VersionCheckResult? {
        val buildInfo = File(gameRoot, ".build.info")
        if (!buildInfo.isFile) {

            return null
        }
        val row = runCatching { activeBuild(buildInfo) }.getOrElse {

            return null
        }
        val product = row["Product"]?.takeIf { it.isNotBlank() } ?: TARGET_PRODUCT
        if (product != TARGET_PRODUCT) {

            return null
        }
        val localVersion = row["Version"] ?: run {

            return null
        }
        val localBuildKey = row["Build Key"] ?: run {

            return null
        }

        val remote = fetchRemote(fast, row) ?: return null
        val isOutdated = localVersion != remote.remoteVersion ||
            (remote.remoteBuildConfig.isNotBlank() && localBuildKey != remote.remoteBuildConfig)
        return remote.copy(localVersion = localVersion, isOutdated = isOutdated)
    }

    /** The current build on Blizzard's version server; [localRow] fills in CDN details the server does not answer (fast mode). */
    private fun fetchRemote(fast: Boolean, localRow: Map<String, String>?, region: String = localRow?.get("Branch") ?: "eu"): VersionCheckResult? {
        val request = Request.Builder()
            .url("$VERSION_SERVER/$TARGET_PRODUCT/versions")
            .build()
        val client = Net.http.newBuilder()
            .connectTimeout(if (fast) 1500 else 5000, TimeUnit.MILLISECONDS)
            .readTimeout(if (fast) 2000 else 5000, TimeUnit.MILLISECONDS)
            .callTimeout(if (fast) 2500 else 6000, TimeUnit.MILLISECONDS)
            .build()
        val text = runCatching {
            client.newCall(request).execute().use { response ->
                if (response.isSuccessful) response.body?.string() else null
            }
        }.getOrNull() ?: return null

        val lines = text.lines().filter { it.isNotBlank() && !it.startsWith("#") }
        if (lines.size < 2) return null
        val header = lines.first().split("|").map { it.substringBefore("!") }
        val remoteRow = lines.drop(1).map { header.zip(it.split("|")).toMap() }.firstOrNull { it["Region"] == region } ?: return null
        val remoteVersion = remoteRow["VersionsName"] ?: remoteRow["VersionName"] ?: remoteRow["Version"] ?: return null
        val remoteBuildKey = remoteRow["BuildConfig"] ?: ""
        val remoteCdnKey = remoteRow["CDNConfig"] ?: ""
        // Keys become file and URL names: anything but a 32-digit hex key is rejected.
        if ((remoteBuildKey.isNotEmpty() && !isKey(remoteBuildKey)) || (remoteCdnKey.isNotEmpty() && !isKey(remoteCdnKey))) return null

        val cdnsRequest = Request.Builder()
            .url("$VERSION_SERVER/$TARGET_PRODUCT/cdns")
            .build()
        val cdnsText = if (fast) null else runCatching {
            client.newCall(cdnsRequest).execute().use { response ->
                if (response.isSuccessful) response.body?.string() else null
            }
        }.getOrNull()

        val cdnsLines = cdnsText?.lines()?.filter { it.isNotBlank() && !it.startsWith("#") } ?: emptyList()
        val cdnsRow = if (cdnsLines.size >= 2) {
            val cdnsHeader = cdnsLines.first().split("|").map { it.substringBefore("!") }
            cdnsLines.drop(1).map { cdnsHeader.zip(it.split("|")).toMap() }.firstOrNull { it["Name"] == region }
                ?: cdnsLines.drop(1).map { cdnsHeader.zip(it.split("|")).toMap() }.firstOrNull()
        } else null

        val cdnHosts = cdnsRow?.get("Hosts")?.split(" ")?.filter { it.isNotBlank() }
            ?: localRow?.get("CDN Hosts")?.split(" ")?.filter { it.isNotBlank() }
            ?: listOf("level3.blizzard.com", "us.cdn.blizzard.com")
        val cdnPath = cdnsRow?.get("Path") ?: localRow?.get("CDN Path") ?: "tpr/wow"
        val cdnServers = (cdnsRow?.get("Servers") ?: localRow?.get("CDN Servers"))?.split(" ")?.filter { it.isNotBlank() }.orEmpty()

        return VersionCheckResult(
            localVersion = "",
            remoteVersion = remoteVersion,
            isOutdated = true,
            remoteBuildConfig = remoteBuildKey,
            remoteCdnConfig = remoteCdnKey,
            product = TARGET_PRODUCT,
            remoteCdnHosts = cdnHosts,
            remoteCdnPath = cdnPath,
            remoteCdnServers = cdnServers,
        )
    }

    /** [region]: the branch to record (an install picks it); null keeps the one in .build.info. */
    fun updateGame(gameRoot: File, target: VersionCheckResult, onStatus: (String) -> Unit, region: String? = null) {
        require(target.product == TARGET_PRODUCT) { "Expected Classic Era, got ${target.product}" }
        val cdn = Cdn(
            hosts = target.remoteCdnHosts.ifEmpty { listOf("level3.blizzard.com", "us.cdn.blizzard.com") },
            path = target.remoteCdnPath.ifEmpty { "tpr/wow" },
            region = region ?: runCatching { activeBuild(File(gameRoot, ".build.info"))["Branch"] }.getOrNull(),
        )
        requireKey(target.remoteBuildConfig)
        if (target.remoteCdnConfig.isNotBlank()) requireKey(target.remoteCdnConfig)
        onStatus(Text.manifest(target.remoteVersion).first)
        val buildConfigRaw = cdn.requireConfig(target.remoteBuildConfig)
        val buildConfig = parseConfig(String(buildConfigRaw))

        val cdnConfigRaw = if (target.remoteCdnConfig.isNotBlank()) {
            cdn.requireConfig(target.remoteCdnConfig)
        } else null

        val configDir = File(gameRoot, "Data/config/${target.remoteBuildConfig.substring(0, 2)}/${target.remoteBuildConfig.substring(2, 4)}")
        configDir.mkdirs()
        File(configDir, target.remoteBuildConfig).writeBytes(buildConfigRaw)

        if (cdnConfigRaw != null && target.remoteCdnConfig.length >= 4) {
            val cdnDir = File(gameRoot, "Data/config/${target.remoteCdnConfig.substring(0, 2)}/${target.remoteCdnConfig.substring(2, 4)}")
            cdnDir.mkdirs()
            File(cdnDir, target.remoteCdnConfig).writeBytes(cdnConfigRaw)
        }

        val install = parseInstall(blteDecode(cdn.require("data", buildConfig.getValue("install")[1])))
        val files = install.filter { it.isClientBinary() }
        check(files.any { it.relativePath == EXECUTABLE }) { "No Classic Era Windows ARM64 executable in this build" }

        val clientDir = File(gameRoot, FLAVOR_DIR)
        files.forEach { destination(clientDir, it.relativePath) }
        val pending = files.filter { md5Hex(File(clientDir, it.relativePath)) != it.ckey }
        // The archive indexes first and in parallel: a client file kept inside an archive is then found on disk. Looking it up
        // index by index over the network took minutes (one small request after another), the line was not the limit.
        if (cdnConfigRaw != null) {
            val cdnConfig = parseConfig(String(cdnConfigRaw))
            syncIndices(gameRoot, cdn, cdnConfig, onStatus)
        }
        if (pending.isNotEmpty()) {
            onStatus(Text.lookingUp().first)
            val ekeys = EncodingLookup(cdn, buildConfig.getValue("encoding")[1]).find(pending.map { it.ckey }.toSet())
            val archives = if (cdnConfigRaw != null) {
                ArchiveLocator(cdn, gameRoot, parseConfig(String(cdnConfigRaw)).getValue("archives"))
            } else null
            downloadClientFiles(cdn, clientDir, pending, ekeys, archives, onStatus)
        }

        // Last: until this row exists (or points at the new build) the install counts as unfinished and is simply run again.
        onStatus(Text.buildInfo().first)
        upsertBuildInfo(File(gameRoot, ".build.info"), target, region)
    }

    private fun regionTags(branch: String): String {
        val upper = branch.uppercase(Locale.ROOT)
        return listOf("speech", "text").joinToString(":") { "Windows $PLATFORM_ARCH $upper? enUS $it?" }
    }

    // Patch archives never sit on the data path and archive groups are built by the client: only the archive and file indexes.
    private fun syncIndices(gameRoot: File, cdn: Cdn, cdnConfig: Map<String, List<String>>, onStatus: (String) -> Unit) {
        val indicesDir = File(gameRoot, "Data/indices")
        indicesDir.mkdirs()
        val required = (cdnConfig["archives"].orEmpty() + cdnConfig["file-index"].orEmpty()).distinct()
        required.forEach { requireKey(it) }
        val missing = required.filter { !File(indicesDir, "$it.index").isFile }
        if (missing.isEmpty()) return
        val done = AtomicInteger()
        val meter = TransferMeter()
        onStatus(Text.indexes(0, missing.size, "0.0").first)
        val pool = Executors.newFixedThreadPool(INDEX_DOWNLOAD_THREADS)
        try {
            missing.map { hash ->
                pool.submit {
                    cdn.fetch("data", "$hash.index", onBytes = meter::add)?.let { writeAtomically(File(indicesDir, "$hash.index"), it) }
                    val count = done.incrementAndGet()
                    if (meter.due() || count == missing.size) onStatus(Text.indexes(count, missing.size, meter.speed()).first)
                }
            }.forEach { it.get() }
        } catch (e: ExecutionException) {
            throw e.cause ?: e
        } finally {
            pool.shutdownNow()
        }
    }

    /**
     * Points the [TARGET_PRODUCT] row of [buildInfo] at [target], adding the row (and the file, and any missing column) when needed.
     * A new row takes the region of the beta row it upgrades. The game streams missing assets from "CDN Servers" and crashes at
     * startup without them.
     */
    internal fun upsertBuildInfo(buildInfo: File, target: VersionCheckResult, region: String? = null) {
        val lines = buildInfo.takeIf { it.isFile }?.readLines()?.filter { it.isNotBlank() }?.ifEmpty { null }
            ?: listOf(BUILD_INFO_HEADER.joinToString("|"))
        val headerCells = lines.first().split("|").toMutableList()
        val names = headerCells.map { it.substringBefore("!") }.toMutableList()
        val rows = lines.drop(1).map { line -> names.zip(line.split("|")).toMap() }
        for (column in BUILD_INFO_HEADER) {
            val name = column.substringBefore("!")
            if (name !in names) { names += name; headerCells += column }
        }
        val updates = mapOf(
            "Active" to "1",
            "Version" to target.remoteVersion,
            "Build Key" to target.remoteBuildConfig,
            "CDN Key" to target.remoteCdnConfig,
            "CDN Hosts" to target.remoteCdnHosts.joinToString(" "),
            "CDN Path" to target.remoteCdnPath,
            "CDN Servers" to target.remoteCdnServers.joinToString(" "),
            "Product" to TARGET_PRODUCT,
        ).filterValues { it.isNotBlank() } + (region?.let { mapOf("Branch" to it, "Tags" to regionTags(it)) } ?: emptyMap())
        val existing = rows.indexOfFirst { it["Product"] == TARGET_PRODUCT }
        val updated = if (existing >= 0) {
            rows.mapIndexed { index, row -> if (index == existing) row + updates else row }
        } else {
            val branch = region ?: buildInfo.parentFile?.let { region(it) } ?: "us"
            rows + (mapOf("Branch" to branch, "Tags" to regionTags(branch)) + updates)
        }
        val text = (listOf(headerCells.joinToString("|")) + updated.map { row -> names.joinToString("|") { row[it].orEmpty() } })
            .joinToString("\n") + "\n"
        buildInfo.parentFile?.mkdirs()
        writeAtomically(buildInfo, text.toByteArray())
    }

    fun download(gameRoot: File, onStatus: (String) -> Unit) {
        val row = activeBuild(File(gameRoot, ".build.info"))
        check(row["Product"] == TARGET_PRODUCT) { "Incompatible game product: ${row["Product"]}. Expected $TARGET_PRODUCT." }
        val versionCheck = checkVersion(gameRoot)
        if (versionCheck != null && versionCheck.remoteBuildConfig.isNotBlank()) {
            updateGame(gameRoot, versionCheck, onStatus)
        } else {
            val row = activeBuild(File(gameRoot, ".build.info"))
            val cdn = Cdn(
                hosts = row.getValue("CDN Hosts").split(" ").filter { it.isNotBlank() },
                path = row.getValue("CDN Path"),
                region = row["Branch"],
            )
            onStatus(Text.reading(row["Version"].orEmpty()).first)
            val buildConfig = parseConfig(String(cdn.requireConfig(row.getValue("Build Key"))))
            val install = parseInstall(blteDecode(cdn.require("data", buildConfig.getValue("install")[1])))
            val files = install.filter { it.isClientBinary() }
            check(files.any { it.relativePath == EXECUTABLE }) { "No Classic Era Windows ARM64 executable in this build" }

            val clientDir = File(gameRoot, FLAVOR_DIR)
            files.forEach { destination(clientDir, it.relativePath) }
            val pending = files.filter { md5Hex(File(clientDir, it.relativePath)) != it.ckey }
            if (pending.isEmpty()) return

            val cdnConfig = parseConfig(String(cdn.requireConfig(row.getValue("CDN Key"))))
            syncIndices(gameRoot, cdn, cdnConfig, onStatus)
            onStatus(Text.lookingUp().first)
            val ekeys = EncodingLookup(cdn, buildConfig.getValue("encoding")[1]).find(pending.map { it.ckey }.toSet())
            downloadClientFiles(cdn, clientDir, pending, ekeys, ArchiveLocator(cdn, gameRoot, cdnConfig.getValue("archives")), onStatus)
        }
    }

    /** Downloads [pending] client files (loose CDN files or archive members) in parallel ranges, with size and speed in the status. */
    private fun downloadClientFiles(cdn: Cdn, clientDir: File, pending: List<InstallEntry>, ekeys: Map<String, String>,
                                    archives: ArchiveLocator?, onStatus: (String) -> Unit) {
        pending.forEachIndexed { index, entry ->
            val meter = TransferMeter()
            var size = 0L
            val report = { onStatus(Text.downloading(entry.name, index + 1, pending.size, mb(meter.total), mb(size), meter.speed()).first) }
            report()
            val onBytes: (Long) -> Unit = { meter.add(it); if (meter.due()) report() }
            val ekey = ekeys[entry.ckey] ?: throw IOException("${entry.name} is missing from the encoding table")
            val encoded = cdn.fetchFast("data", ekey, null, { size = it }, onBytes)
                ?: archives?.read(ekey, onSize = { size = it }, onBytes = onBytes)
                ?: throw IOException("${entry.name} not found on the CDN")
            report()
            val dest = destination(clientDir, entry.relativePath)
            dest.parentFile?.mkdirs()
            val temp = File(dest.parentFile, "${dest.name}.download")
            val digest = MessageDigest.getInstance("MD5")
            DigestOutputStream(temp.outputStream().buffered(), digest).use { blteDecode(encoded, it) }
            if (hex(digest.digest(), 0, 16) != entry.ckey) {
                temp.delete()
                throw IOException("Checksum mismatch for ${entry.name}")
            }
            replaceAtomically(temp, dest)
        }
    }

    private val VOICE_PATHS = setOf("utils/wowvoiceproxyt.exe", "utils/wowvoiceproxy.exe",
        "utils/vivoxsdk.dll", "utils/discord_partner_sdk.dll")

    internal fun voiceEntries(entries: List<InstallEntry>): List<InstallEntry> {
        val voice = entries.filter { it.isVoiceBinary() }
        check(voice.any { it.relativePath.endsWith(".exe", true) } &&
            voice.any { it.relativePath.equals("Utils/vivoxsdk.dll", true) }) {
            "The installed build has no complete Windows ARM64 voice payload."
        }
        check(voice.none { it.relativePath.equals("Utils/WowVoiceProxyT.exe", true) } ||
            voice.any { it.relativePath.equals("Utils/discord_partner_sdk.dll", true) }) {
            "The installed build's voice proxy is missing its Discord SDK library."
        }
        check(voice.map { it.relativePath.lowercase(java.util.Locale.ROOT) }.distinct().size == voice.size) {
            "Ambiguous voice payload in the installed build manifest."
        }
        return voice
    }

    /** Explicit repair of the installed build only. Never checks for a newer game build. Call on IO. [translate] picks the English or Hungarian progress text. */
    fun repairVoiceFiles(gameRoot: File, onStatus: (String) -> Unit, active: () -> Boolean,
        translate: (String, String) -> String = { en, _ -> en }): Int {
        check(!active()) { "Stop the game before repairing voice files." }
        val row = activeBuild(File(gameRoot, ".build.info"))
        check(row["Product"] == TARGET_PRODUCT) { "Incompatible game product." }
        val cdn = Cdn(row.getValue("CDN Hosts").split(" ").filter { it.isNotBlank() }, row.getValue("CDN Path"), row["Branch"])
        val version = row["Version"].orEmpty()
        onStatus(translate("Reading installed build $version manifest...", "A telepített $version build jegyzékének olvasása..."))
        val config = parseConfig(String(cdn.requireConfig(row.getValue("Build Key"))))
        val entries = voiceEntries(parseInstall(blteDecode(cdn.require("data", config.getValue("install")[1]))))
        val pending = entries.filter { md5Hex(File(gameRoot, "$FLAVOR_DIR/${it.relativePath}")) != it.ckey }
        if (pending.isEmpty()) return 0
        val ekeys = EncodingLookup(cdn, config.getValue("encoding")[1]).find(pending.map { it.ckey }.toSet())
        val archives by lazy {
            ArchiveLocator(cdn, gameRoot, parseConfig(String(cdn.requireConfig(row.getValue("CDN Key")))).getValue("archives"))
        }
        return installVoiceEntries(File(gameRoot, FLAVOR_DIR), pending, active, onStatus, translate) { entry ->
            val ekey = ekeys[entry.ckey] ?: throw IOException("${entry.name} is missing from the encoding table")
            cdn.fetch("data", ekey) ?: archives.read(ekey, beforeRead = { check(!active()) { "A game session started; voice repair stopped." } }) ?: throw IOException("${entry.name} not found on the CDN")
        }
    }

    /** Stage and validate all requested files before publishing any. Existing files survive checksum/ABI errors. */
    internal fun installVoiceEntries(clientDir: File, entries: List<InstallEntry>, active: () -> Boolean,
        onStatus: (String) -> Unit, translate: (String, String) -> String = { en, _ -> en }, encoded: (InstallEntry) -> ByteArray): Int {
        require(entries.all { it.isVoiceBinary() }) { "Only Windows ARM64 voice files can be repaired." }
        require(entries.map { it.relativePath.lowercase(java.util.Locale.ROOT) }.distinct().size == entries.size)
        check(!active()) { "Stop the game before repairing voice files." }
        require(clientDir.isDirectory) { "The installed WoW directory is missing." }
        val root = clientDir.canonicalFile.toPath()
        val destinations = entries.map { File(clientDir, it.relativePath).canonicalFile }.also { files ->
            require(files.all { it.toPath().startsWith(root) && it.parentFile.name.equals("Utils", true) }) {
                "Voice payload escapes the game directory."
            }
        }
        val stage = java.nio.file.Files.createTempDirectory(root, ".wow-voice-").toFile()
        try {
            entries.forEachIndexed { index, entry ->
                check(!active()) { "A game session started; voice repair stopped." }
                onStatus(translate("Downloading ${entry.name} (${index + 1}/${entries.size})...", "${entry.name} letöltése (${index + 1}/${entries.size})..."))
                val temp = File(stage, index.toString())
                val digest = MessageDigest.getInstance("MD5")
                DigestOutputStream(temp.outputStream().buffered(), digest).use { blteDecode(encoded(entry), it) }
                check(hex(digest.digest(), 0, 16) == entry.ckey) { "Checksum mismatch for ${entry.name}" }
                WowArm64Pe.validate(temp, dll = entry.relativePath.endsWith(".dll", true))
            }
            entries.indices.forEach { index ->
                check(!active()) { "A game session started; voice repair stopped." }
                val dest = destinations[index]
                check(dest.parentFile.isDirectory || dest.parentFile.mkdirs()) { "Cannot create Utils directory." }
                java.nio.file.Files.move(File(stage, index.toString()).toPath(), dest.toPath(),
                    java.nio.file.StandardCopyOption.ATOMIC_MOVE, java.nio.file.StandardCopyOption.REPLACE_EXISTING)
            }
            return entries.size
        } finally { stage.deleteRecursively() }
    }

    internal data class InstallEntry(val name: String, val ckey: String, val tags: Set<String>) {
        val relativePath get() = name.replace('\\', '/')

        fun isClientBinary(): Boolean {
            if ("Windows" !in tags || PLATFORM_ARCH !in tags || EXCLUDED_ARCH in tags) return false
            val lower = relativePath.lowercase(java.util.Locale.ROOT)
            return !lower.startsWith("utils/") || "exceptionhandler" in lower || isVoiceBinary()
        }

        fun isVoiceBinary(): Boolean = "Windows" in tags && PLATFORM_ARCH in tags && EXCLUDED_ARCH !in tags &&
            relativePath.lowercase(java.util.Locale.ROOT) in VOICE_PATHS
    }

    /**
     * [region]: the install's branch; Blizzard's own CDN of that region is tried before the listed hosts. Measured on the phone
     * (LTE, 2026-11-02, 20 MB range): eu.cdn.blizzard.com 15.6 MB/s over HTTPS (18 MB/s plain), level3.blizzard.com 8.5 MB/s plain,
     * level3.ssl.blizzard.com 2.5 MB/s; Europe's cdns row lists only level3.
     */
    internal class Cdn(val hosts: List<String>, val path: String, region: String? = null) {
        private val tryHosts: List<String> =
            (if (hosts.any { it.substringBefore(':') in LOOPBACK_HOSTS }) hosts else listOfNotNull(regionalCdn(region)) + hosts).distinct()

        // Never trust a CDN Content-Length enough to allocate an entire archive on Android.
        private val maxBufferedBytes = 128L * 1024 * 1024

        private fun checkBufferedSize(size: Long) {
            if (size > maxBufferedBytes) throw IOException("CDN response is too large to buffer ($size bytes); use a bounded archive range")
        }

        fun require(kind: String, key: String) = fetch(kind, key) ?: throw IOException("CDN is missing $kind/$key")

        /** Config files are named after their own MD5: a mismatch means a corrupted or substituted file. */
        fun requireConfig(key: String): ByteArray {
            val bytes = require("config", key)
            val actual = MessageDigest.getInstance("MD5").digest(bytes).joinToString("") { "%02x".format(it.toInt() and 255) }
            if (!actual.equals(key, ignoreCase = true)) throw IOException("Config checksum mismatch for $key")
            return bytes
        }

        /** Content length of a CDN file, null when the CDN does not have it, -1 when the server does not say. */
        fun head(kind: String, key: String): Long? {
            val base = requireKey(key.substringBefore('.')).lowercase(java.util.Locale.ROOT)
            var failure: IOException? = null
            var missing = false
            for (host in tryHosts) {
                if (!HOST_PATTERN.matches(host)) continue
                val scheme = if (host.substringBefore(':') in LOOPBACK_HOSTS) "http" else "https"
                val request = Request.Builder().url("$scheme://${if (scheme == "https") httpsHost(host) else host}/$path/$kind/${base.substring(0, 2)}/${base.substring(2, 4)}/$key").head().build()
                try {
                    Net.http.newCall(request).execute().use { response ->
                        when {
                            response.isSuccessful -> return response.header("Content-Length")?.toLongOrNull() ?: -1L
                            response.code == 403 || response.code == 404 -> missing = true
                            else -> failure = IOException("HTTP ${response.code} from $host")
                        }
                    }
                } catch (e: IOException) {
                    failure = e
                }
            }
            if (missing) return null
            throw failure ?: IOException("No CDN hosts in .build.info")
        }

        /**
         * [range] (or the whole file) in parallel [SEGMENT_BYTES] pieces when it is large; small ones and servers that do not honour
         * ranges take the single request. Null when the CDN does not have the file.
         */
        fun fetchFast(kind: String, key: String, range: LongRange?, onSize: (Long) -> Unit = {}, onBytes: ((Long) -> Unit)? = null): ByteArray? {
            val total = if (range != null) range.last - range.first + 1 else head(kind, key) ?: return null
            checkBufferedSize(total)
            if (total >= 0) onSize(total)
            if (total < SEGMENT_BYTES * 2 || total > Int.MAX_VALUE) return fetch(kind, key, range, onBytes)
            val start = range?.first ?: 0L
            val out = ByteArray(total.toInt())
            val pool = Executors.newFixedThreadPool(SEGMENT_THREADS)
            val honoured = try {
                (0 until total step SEGMENT_BYTES).map { offset ->
                    pool.submit<Boolean> {
                        val length = minOf(SEGMENT_BYTES, total - offset)
                        val piece = fetch(kind, key, (start + offset) until (start + offset + length), onBytes)
                            ?: throw IOException("CDN is missing $kind/$key")
                        if (piece.size.toLong() != length) return@submit false
                        piece.copyInto(out, offset.toInt())
                        true
                    }
                }.map { it.get() }.all { it }
            } catch (e: ExecutionException) {
                throw e.cause ?: e
            } finally {
                pool.shutdownNow()
            }
            return if (honoured) out else fetch(kind, key, range, onBytes)
        }

        fun fetch(kind: String, key: String, range: LongRange? = null, onBytes: ((Long) -> Unit)? = null): ByteArray? {
            val base = requireKey(key.substringBefore('.')).lowercase(java.util.Locale.ROOT)
            var failure: IOException? = null
            var missing = false
            for (host in tryHosts) {
                if (!HOST_PATTERN.matches(host)) continue
                // Blizzard's CDN hosts all serve HTTPS; plain HTTP is kept only for loopback test servers.
                val scheme = if (host.substringBefore(':') in LOOPBACK_HOSTS) "http" else "https"
                val request = Request.Builder()
                    .url("$scheme://${if (scheme == "https") httpsHost(host) else host}/$path/$kind/${base.substring(0, 2)}/${base.substring(2, 4)}/$key")
                    .apply { if (range != null) header("Range", "bytes=${range.first}-${range.last}") }
                    .build()
                try {
                    Net.http.newCall(request).execute().use { response ->
                        when {
                            response.isSuccessful -> {
                                val body = response.body ?: throw IOException("Empty CDN response")
                                val length = range?.let { it.last - it.first + 1 } ?: body.contentLength()
                                if (range != null && (range.first < 0 || length <= 0)) throw IOException("Invalid CDN range")
                                checkBufferedSize(length)
                                // Some CDN edges ignore Range and return HTTP 200 plus the complete GB-sized archive.
                                // Skip through that stream with a fixed buffer, then read only the requested slice.
                                if (range != null && response.code == 206) {
                                    response.header("Content-Range")?.let { value ->
                                        val expected = "bytes ${range.first}-${range.last}/"
                                        if (!value.startsWith(expected)) throw IOException("Wrong CDN Content-Range: $value")
                                    }
                                    if (body.contentLength() >= 0 && body.contentLength() != length)
                                        throw IOException("Truncated or oversized CDN range")
                                }
                                val out = ByteArrayOutputStream(minOf(length.coerceAtLeast(0), 65536).toInt())
                                val buffer = ByteArray(64 * 1024)
                                body.byteStream().use { input ->
                                    var skip = if (range != null && response.code == 200) range.first else 0L
                                    while (skip > 0) {
                                        val n = input.read(buffer, 0, minOf(skip, buffer.size.toLong()).toInt())
                                        if (n < 0) throw IOException("CDN response ends before requested range")
                                        skip -= n
                                        onBytes?.invoke(n.toLong())
                                    }
                                    var remaining = if (length >= 0) length else maxBufferedBytes + 1
                                    while (remaining > 0) {
                                        val n = input.read(buffer, 0, minOf(remaining, buffer.size.toLong()).toInt())
                                        if (n < 0) break
                                        checkBufferedSize(out.size().toLong() + n)
                                        out.write(buffer, 0, n)
                                        remaining -= n
                                        onBytes?.invoke(n.toLong())
                                    }
                                    if (length >= 0 && remaining != 0L) throw IOException("Truncated CDN response")
                                }
                                return out.toByteArray()
                            }
                            response.code == 403 || response.code == 404 -> missing = true
                            else -> failure = IOException("HTTP ${response.code} from $host")
                        }
                    }
                } catch (e: IOException) {
                    failure = e
                }
            }
            if (missing) return null
            throw failure ?: IOException("No CDN hosts in .build.info")
        }
    }

    private class EncodingLookup(private val cdn: Cdn, private val ekey: String) {
        private data class Chunk(val encodedOffset: Long, val encodedSize: Int, val decodedOffset: Long, val decodedSize: Int)

        private val chunks: List<Chunk> = parseChunkTable(ByteBuffer.wrap(fetchEncoded(0, 8)).getInt(4))

        private fun parseChunkTable(headerSize: Int): List<Chunk> {
            val header = ByteBuffer.wrap(fetchEncoded(0, headerSize))
            val count = header.getInt(8) and 0xFFFFFF
            var encoded = headerSize.toLong()
            var decoded = 0L
            return (0 until count).map { i ->
                val compressedSize = header.getInt(12 + i * 24)
                val decompressedSize = header.getInt(16 + i * 24)
                Chunk(encoded, compressedSize, decoded, decompressedSize).also {
                    encoded += compressedSize
                    decoded += decompressedSize
                }
            }
        }

        private var cachedChunk: Chunk? = null
        private var cachedData: ByteArray = ByteArray(0)

        private fun read(start: Long, length: Int): ByteArray {
            val out = ByteArray(length)
            var written = 0
            for (chunk in chunks) {
                val chunkEnd = chunk.decodedOffset + chunk.decodedSize
                if (chunkEnd <= start || chunk.decodedOffset >= start + length) continue
                val from = maxOf(start, chunk.decodedOffset) - chunk.decodedOffset
                val count = (minOf(start + length, chunkEnd) - chunk.decodedOffset - from).toInt()
                val mode = fetchEncoded(chunk.encodedOffset, 1)[0].toInt().toChar()
                val bytes = if (mode == 'N') {
                    fetchEncoded(chunk.encodedOffset + 1 + from, count)
                } else {
                    decodedChunk(chunk).copyOfRange(from.toInt(), from.toInt() + count)
                }
                bytes.copyInto(out, written)
                written += count
            }
            return out
        }

        private fun decodedChunk(chunk: Chunk): ByteArray {
            if (cachedChunk != chunk) {
                cachedData = decodeChunk(fetchEncoded(chunk.encodedOffset, chunk.encodedSize))
                cachedChunk = chunk
            }
            return cachedData
        }

        private fun fetchEncoded(offset: Long, size: Int) =
            cdn.fetch("data", ekey, offset until offset + size) ?: throw IOException("Encoding file missing")

        fun find(wanted: Set<String>): Map<String, String> {
            val header = ByteBuffer.wrap(read(0, 22))
            val ckeySize = header.get(3).toInt()
            val ekeySize = header.get(4).toInt()
            val pageSize = (header.getShort(5).toInt() and 0xFFFF) * 1024
            val pageCount = header.getInt(9)
            val especSize = header.getInt(18)
            val indexStart = 22L + especSize
            val indexEntrySize = ckeySize + 16
            val index = read(indexStart, pageCount * indexEntrySize)
            val firstKeys = (0 until pageCount).map { hex(index, it * indexEntrySize, ckeySize) }
            val pagesStart = indexStart + pageCount.toLong() * indexEntrySize

            val found = mutableMapOf<String, String>()
            wanted.groupBy { ckey -> firstKeys.binarySearch(ckey).let { if (it >= 0) it else -it - 2 } }
                .filterKeys { it >= 0 }
                .forEach { (page, ckeys) ->
                    val data = read(pagesStart + page.toLong() * pageSize, pageSize)
                    var pos = 0
                    while (pos + 6 + ckeySize <= data.size && data[pos].toInt() != 0) {
                        val keyCount = data[pos].toInt() and 0xFF
                        val ckey = hex(data, pos + 6, ckeySize)
                        if (ckey in ckeys) found[ckey] = hex(data, pos + 6 + ckeySize, ekeySize)
                        pos += 6 + ckeySize + keyCount * ekeySize
                    }
                }
            return found
        }
    }

    private class ArchiveLocator(private val cdn: Cdn, private val gameRoot: File, private val archives: List<String>) {
        fun read(ekey: String, beforeRead: () -> Unit = {}, onSize: (Long) -> Unit = {}, onBytes: ((Long) -> Unit)? = null): ByteArray? {
            for (archive in archives) {
                requireKey(archive)
                beforeRead()
                val local = File(gameRoot, "Data/indices/$archive.index")
                val index = if (local.isFile) {
                    local.readBytes()
                } else {
                    val fetched = cdn.fetch("data", "$archive.index") ?: continue
                    beforeRead()
                    local.parentFile?.mkdirs()
                    writeAtomically(local, fetched)
                    fetched
                }
                val buffer = ByteBuffer.wrap(index)
                for (block in 0 until index.size / 4096 * 4096 step 4096) {
                    var pos = block
                    while (pos <= block + 4096 - 24) {
                        if (hex(index, pos, 16) == ekey) {
                            val size = buffer.getInt(pos + 16).toLong() and 0xFFFFFFFFL
                            val offset = buffer.getInt(pos + 20).toLong() and 0xFFFFFFFFL
                            return cdn.fetchFast("data", archive, offset until offset + size, onSize, onBytes)
                        }
                        pos += 24
                    }
                }
            }
            return null
        }
    }

    internal fun activeBuild(buildInfo: File, product: String = TARGET_PRODUCT): Map<String, String> {
        check(buildInfo.isFile) { "Missing .build.info in ${buildInfo.parent}" }
        val lines = buildInfo.readLines().filter { it.isNotBlank() }
        val header = lines.first().split("|").map { it.substringBefore("!") }
        val rows = lines.drop(1).map { header.zip(it.split("|")).toMap() }
        return rows.firstOrNull { it["Product"] == product && it["Active"] == "1" }
            ?: rows.firstOrNull { it["Product"] == product }
            ?: rows.firstOrNull { it["Active"] == "1" }
            ?: rows.firstOrNull()
            ?: throw IllegalStateException("No build in .build.info")
    }

    internal fun parseConfig(text: String): Map<String, List<String>> =
        text.lines().filter { "=" in it && !it.startsWith("#") }.associate { line ->
            line.substringBefore("=").trim() to line.substringAfter("=").trim().split(" ").filter { it.isNotEmpty() }
        }

    private fun parseInstall(data: ByteArray): List<InstallEntry> {
        check(data[0] == 'I'.code.toByte() && data[1] == 'N'.code.toByte()) { "Bad install manifest" }
        val buffer = ByteBuffer.wrap(data)
        val hashSize = data[3].toInt()
        val tagCount = buffer.getShort(4).toInt() and 0xFFFF
        val entryCount = buffer.getInt(6)
        val maskSize = (entryCount + 7) / 8
        var pos = 10

        fun readString(): String {
            val end = (pos until data.size).first { data[it].toInt() == 0 }
            return String(data, pos, end - pos).also { pos = end + 1 }
        }

        val tags = (0 until tagCount).map {
            val name = readString()
            pos += 2
            (name to data.copyOfRange(pos, pos + maskSize)).also { pos += maskSize }
        }
        return (0 until entryCount).map { i ->
            val name = readString()
            val ckey = hex(data, pos, hashSize)
            pos += hashSize + 4
            val entryTags = tags.filter { (_, mask) -> mask[i / 8].toInt() and (0x80 ushr (i % 8)) != 0 }.map { it.first }.toSet()
            InstallEntry(name, ckey, entryTags)
        }
    }

    private fun blteDecode(data: ByteArray) = ByteArrayOutputStream(data.size).also { blteDecode(data, it) }.toByteArray()

    internal fun blteDecode(data: ByteArray, out: OutputStream) {
        check(String(data, 0, 4) == "BLTE") { "Not BLTE data" }
        val buffer = ByteBuffer.wrap(data)
        val headerSize = buffer.getInt(4)
        if (headerSize == 0) {
            out.write(decodeChunk(data.copyOfRange(8, data.size)))
            return
        }
        val count = buffer.getInt(8) and 0xFFFFFF
        var offset = headerSize
        for (i in 0 until count) {
            val compressedSize = buffer.getInt(12 + i * 24)
            out.write(decodeChunk(data.copyOfRange(offset, offset + compressedSize)))
            offset += compressedSize
        }
    }

    private fun decodeChunk(chunk: ByteArray): ByteArray {
        val body = chunk.copyOfRange(1, chunk.size)
        return when (chunk[0].toInt().toChar()) {
            'N' -> body
            'Z' -> inflate(body)
            'F' -> blteDecode(body)
            else -> throw IOException("Unsupported BLTE chunk mode ${chunk[0].toInt().toChar()}")
        }
    }

    private fun inflate(body: ByteArray): ByteArray {
        val inflater = Inflater()
        inflater.setInput(body)
        val out = ByteArrayOutputStream(body.size * 2)
        val buffer = ByteArray(64 * 1024)
        try {
            while (!inflater.finished()) {
                val n = inflater.inflate(buffer)
                if (n == 0 && (inflater.needsInput() || inflater.needsDictionary())) throw IOException("Truncated zlib chunk")
                out.write(buffer, 0, n)
            }
        } finally {
            inflater.end()
        }
        return out.toByteArray()
    }

    private val KEY_PATTERN = Regex("[0-9a-fA-F]{32}")
    private val HOST_PATTERN = Regex("[A-Za-z0-9.-]+(:[0-9]{1,5})?")
    private val LOOPBACK_HOSTS = setOf("127.0.0.1", "localhost")

    /**
     * Blizzard serves HTTPS for level3.blizzard.com on its own name, level3.ssl.blizzard.com (the cdns "Servers" list names it).
     * HTTPS straight to level3.blizzard.com was 4x slower and broke off mid-file (measured 2026-11-02: 2.5 MB/s against 10.5 MB/s
     * from level3.ssl.blizzard.com and 11 MB/s from us./eu.cdn.blizzard.com); Europe's only listed host is level3.
     */
    /** Blizzard's own CDN host for a region (Taiwan's cdns row uses the US one). */
    internal fun regionalCdn(region: String?): String? = when (region?.lowercase(Locale.ROOT)) {
        "eu" -> "eu.cdn.blizzard.com"
        "us", "tw" -> "us.cdn.blizzard.com"
        "kr" -> "kr.cdn.blizzard.com"
        else -> null
    }

    internal fun httpsHost(host: String): String = when (host.lowercase(Locale.ROOT)) {
        "level3.blizzard.com" -> "level3.ssl.blizzard.com"
        else -> host
    }
    private const val VERSION_SERVER = "https://us.version.battle.net"

    internal fun isKey(value: String) = KEY_PATTERN.matches(value)

    internal fun requireKey(value: String): String {
        require(isKey(value)) { "Invalid CDN key." }
        return value
    }

    /** Resolves a manifest path inside the client directory; refuses anything that would escape it. */
    private fun destination(clientDir: File, relativePath: String): File {
        val root = clientDir.canonicalFile.toPath()
        val file = File(clientDir, relativePath).canonicalFile
        require(file.toPath().startsWith(root) && file.toPath() != root) { "Client file escapes the game directory: $relativePath" }
        return file
    }

    private fun replaceAtomically(temp: File, dest: File) {
        java.nio.file.Files.move(temp.toPath(), dest.toPath(),
            java.nio.file.StandardCopyOption.ATOMIC_MOVE, java.nio.file.StandardCopyOption.REPLACE_EXISTING)
    }

    private fun writeAtomically(dest: File, bytes: ByteArray) {
        val temp = File(dest.parentFile, "${dest.name}.tmp")
        temp.writeBytes(bytes)
        replaceAtomically(temp, dest)
    }

    private fun md5Hex(file: File): String? {
        if (!file.isFile) return null
        val digest = MessageDigest.getInstance("MD5")
        file.inputStream().use { input ->
            val buffer = ByteArray(1024 * 1024)
            while (true) {
                val n = input.read(buffer)
                if (n < 0) break
                digest.update(buffer, 0, n)
            }
        }
        return hex(digest.digest(), 0, 16)
    }

    private fun hex(data: ByteArray, offset: Int, size: Int): String {
        val chars = CharArray(size * 2)
        for (i in 0 until size) {
            val v = data[offset + i].toInt() and 0xFF
            chars[i * 2] = HEX[v ushr 4]
            chars[i * 2 + 1] = HEX[v and 0xF]
        }
        return String(chars)
    }

    private val HEX = "0123456789abcdef".toCharArray()
}
