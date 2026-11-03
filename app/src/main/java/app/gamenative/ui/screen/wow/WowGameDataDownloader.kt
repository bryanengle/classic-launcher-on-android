package app.gamenative.ui.screen.wow

import android.content.Context
import java.io.File
import java.io.IOException
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.util.concurrent.Executors
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * Downloads the whole game data (the build's download manifest for Windows ARM64 + the game language + region) straight into
 * WoW's local storage, before the game runs: CDN archive pieces of up to 8 MB, 4 at a time, from Blizzard's own regional CDN.
 * The game streams the same files itself while it runs, but only what it is about to show, at about 2 MB/s on the phone; this
 * runs at the line's speed (15-18 MB/s measured on LTE from eu.cdn.blizzard.com). Priority 0 first (what the game needs to
 * start), then 1, then 2. Files the storage already has are skipped, so a stopped download continues where it was.
 */
internal object WowGameDataDownloader {
    data class Progress(val done: Long, val total: Long, val bytesPerSecond: Double, val preparing: Boolean)

    private const val THREADS = 4
    private const val RANGE_MAX = 8L * 1024 * 1024
    private const val GAP_MAX = 256L * 1024
    private const val CHECKPOINT_BYTES = 512L * 1024 * 1024

    private class Job(val archive: Int, val start: Long, val end: Long, val items: IntArray)
    private class Result(val job: Job, val data: ByteArray)

    fun run(context: Context, gameRoot: File, cancelled: () -> Boolean, onProgress: (Progress) -> Unit) {
        onProgress(Progress(0, 0, 0.0, true))
        val row = WowClientDownloader.activeBuild(File(gameRoot, ".build.info"))
        check(row["Product"] == WowClientDownloader.TARGET_PRODUCT) { "Install the game first." }
        val cdn = WowClientDownloader.Cdn(row.getValue("CDN Hosts").split(" ").filter { it.isNotBlank() }, row.getValue("CDN Path"), row["Branch"])
        val buildConfig = WowClientDownloader.parseConfig(String(cdn.requireConfig(row.getValue("Build Key"))))
        val cdnConfig = WowClientDownloader.parseConfig(String(cdn.requireConfig(row.getValue("CDN Key"))))
        val locale = runCatching { WowGameConfig.current(gameRoot).locale }.getOrDefault("enUS")
        val counter = WowClientDownloader.DownloadManifestCounter(
            setOf("Windows", "arm64", locale, row["Branch"].orEmpty().uppercase(java.util.Locale.ROOT), "speech", "text"), keep = true)
        WowClientDownloader.blteDecode(cdn.require("data", buildConfig.getValue("download")[1]), counter)
        val entries = counter.selectedEntries()
        val total = entries.sizes.sum()

        val template = context.assets.open("casc/shmem.template").use { it.readBytes() }
        val storage = WowCasc.Storage.open(File(gameRoot, "Data/data"), template)
        val present = storage.presentPrefixes()
        fun prefix(i: Int) = ByteBuffer.wrap(entries.keys, i * 16, 8).long
        val todo = (0 until entries.count).filter { present.binarySearch(prefix(it)) < 0 }.toIntArray()
        var done = total - todo.sumOf { entries.sizes[it] }
        onProgress(Progress(done, total, 0.0, true))
        if (todo.isEmpty()) { storage.close(); onProgress(Progress(total, total, 0.0, false)); return }

        // Where each missing file sits: the CDN archive indexes the install keeps in Data/indices (fetched when missing).
        val archives = cdnConfig.getValue("archives")
        val byPrefix = todo.sortedBy { prefix(it) }.toIntArray()
        val prefixes = LongArray(byPrefix.size) { prefix(byPrefix[it]) }
        val archiveOf = IntArray(entries.count) { -1 }
        val offsetOf = LongArray(entries.count)
        val sizeOf = IntArray(entries.count)
        val indices = File(gameRoot, "Data/indices").apply { mkdirs() }
        val locator = Executors.newFixedThreadPool(4)
        try {
            archives.mapIndexed { ai, archive ->
                locator.submit {
                    val local = File(indices, "$archive.index")
                    val data = if (local.isFile) local.readBytes() else (cdn.fetch("data", "$archive.index") ?: return@submit).also { local.writeBytes(it) }
                    val bb = ByteBuffer.wrap(data)
                    for (p in WowArchiveIndex.entryOffsets(data)) {
                        val pre = bb.getLong(p)
                        val at = prefixes.binarySearch(pre)
                        if (at >= 0) {
                            val i = byPrefix[at]
                            if ((0 until 16).all { data[p + it] == entries.keys[i * 16 + it] }) synchronized(archiveOf) {
                                val size = bb.getInt(p + 16)
                                check(size.toLong() == entries.sizes[i]) { "CDN archive size does not match the download manifest" }
                                archiveOf[i] = ai; sizeOf[i] = size; offsetOf[i] = bb.getInt(p + 20).toLong() and 0xFFFFFFFFL
                            }
                        }
                    }
                }
            }.forEach { it.get() }
        } finally { locator.shutdownNow() }

        // Jobs: priority first, then archive and offset; neighbouring files of an archive are fetched as one range.
        val ordered = todo.sortedWith(compareBy<Int>({ entries.priorities[it].toInt() }, { archiveOf[it] }, { offsetOf[it] })).toIntArray()
        val jobs = ArrayList<Job>()
        var i = 0
        while (i < ordered.size) {
            val first = ordered[i]
            if (archiveOf[first] < 0) { jobs += Job(-1, 0, entries.sizes[first] - 1, intArrayOf(first)); i++; continue }
            val group = arrayListOf(first)
            var end = offsetOf[first] + sizeOf[first]
            var j = i + 1
            while (j < ordered.size) {
                val n = ordered[j]
                if (archiveOf[n] != archiveOf[first] || entries.priorities[n] != entries.priorities[first]) break
                if (offsetOf[n] - end > GAP_MAX || offsetOf[n] + sizeOf[n] - offsetOf[first] > RANGE_MAX) break
                group += n; end = maxOf(end, offsetOf[n] + sizeOf[n]); j++
            }
            jobs += Job(archiveOf[first], offsetOf[first], end - 1, group.toIntArray())
            i = j
        }

        val pool = Executors.newFixedThreadPool(THREADS)
        val slots = Semaphore(THREADS * 2)
        val results = LinkedBlockingQueue<Result>()
        val failure = AtomicReference<Throwable?>(null)
        val meter = WowClientDownloader.TransferMeter()
        var lastReport = 0L
        var sinceCheckpoint = 0L
        var submitted = 0
        var received = 0
        fun submitNext(): Boolean {
            if (submitted >= jobs.size || cancelled() || failure.get() != null) return false
            if (!slots.tryAcquire()) return false
            val job = jobs[submitted++]
            pool.submit {
                try {
                    val data = if (job.archive < 0) {
                        cdn.fetchFast("data", hexKey(entries.keys, job.items[0]), null, onBytes = meter::add)
                    } else {
                        cdn.fetchFast("data", archives[job.archive], job.start..job.end, onBytes = meter::add)
                    } ?: throw IOException("Missing on the CDN: ${if (job.archive < 0) hexKey(entries.keys, job.items[0]) else archives[job.archive]}")
                    results.put(Result(job, data))
                } catch (e: Throwable) {
                    failure.compareAndSet(null, e); results.put(Result(job, ByteArray(0)))
                }
            }
            return true
        }
        try {
            while (true) {
                while (submitNext()) Unit
                if (received == submitted && (submitted >= jobs.size || cancelled() || failure.get() != null)) break
                val r = results.poll(500, TimeUnit.MILLISECONDS) ?: continue
                received++; slots.release()
                if (failure.get() != null) continue
                for (item in r.job.items) {
                    val (off, len) = if (r.job.archive < 0) 0 to r.data.size else (offsetOf[item] - r.job.start).toInt() to sizeOf[item]
                    verify(entries.keys, item, r.data, off, len)
                    storage.add(entries.keys.copyOfRange(item * 16, item * 16 + 16), r.data, off, len)
                    done += entries.sizes[item]; sinceCheckpoint += len
                }
                if (sinceCheckpoint >= CHECKPOINT_BYTES) { storage.checkpoint(); sinceCheckpoint = 0 }
                val now = System.currentTimeMillis()
                if (now - lastReport > 500) { lastReport = now; onProgress(Progress(done, total, meter.speed().toDouble() * 1048576, false)) }
            }
        } finally {
            pool.shutdownNow()
            storage.close()
        }
        failure.get()?.let { throw it }
        onProgress(Progress(done, total, 0.0, false))
    }

    private fun hexKey(keys: ByteArray, i: Int) = (0 until 16).joinToString("") { "%02x".format(keys[i * 16 + it]) }

    /** The encoding key is the MD5 of the BLTE header (or of the whole blob when it has no chunk table). */
    private fun verify(keys: ByteArray, item: Int, data: ByteArray, off: Int, len: Int) {
        check(len >= 8 && data[off] == 'B'.code.toByte() && data[off + 1] == 'L'.code.toByte()) { "Corrupt download: not BLTE" }
        val headerSize = ByteBuffer.wrap(data, off + 4, 4).int
        val md5 = MessageDigest.getInstance("MD5").apply { update(data, off, if (headerSize > 0) headerSize else len) }.digest()
        check((0 until 16).all { md5[it] == keys[item * 16 + it] }) { "Corrupt download: checksum mismatch" }
    }
}
