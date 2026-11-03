package app.gamenative.ui.screen.wow

import android.content.Context
import android.os.Environment
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.io.File
import java.io.IOException

/**
 * Where the game files live: the shared storage (visible to file managers, slower because Android
 * serves it through its FUSE layer) or the app's private storage (direct file system, faster loading).
 * Switching syncs the files across, never moves or deletes the other copy by itself.
 */
internal object WowGameStorage {
    private const val FOLDER = "WoW Classic Era"
    private const val SPACE_MARGIN_BYTES = 512L * 1024 * 1024
    private const val COMPARE_BUFFER_BYTES = 1024 * 1024

    fun sharedRoot(): File = File(Environment.getExternalStorageDirectory(), FOLDER)

    fun privateRoot(context: Context): File = File(context.filesDir, FOLDER)

    fun isPrivate(context: Context, path: String): Boolean =
        runCatching { File(path).canonicalFile == privateRoot(context).canonicalFile }.getOrDefault(false)

    class Progress(val copiedBytes: Long, val totalBytes: Long)

    private class Entry(val source: File, val target: File, val copy: Boolean)
    private class Plan(val entries: List<Entry>, val bytes: Long, val largest: Long)

    /**
     * A target of the same length is only accepted as "already moved" when its bytes equal the source: length and
     * modification time alone cannot prove it, and the source is deleted afterwards. Different bytes: the source wins.
     */
    private fun needsCopy(source: File, target: File): Boolean =
        !target.isFile || target.length() != source.length() || !sameContent(source, target)

    internal fun sameContent(first: File, second: File): Boolean {
        if (first.length() != second.length()) return false
        val a = ByteArray(COMPARE_BUFFER_BYTES)
        val b = ByteArray(COMPARE_BUFFER_BYTES)
        first.inputStream().use { x ->
            second.inputStream().use { y ->
                while (true) {
                    val readA = readFully(x, a)
                    val readB = readFully(y, b)
                    if (readA != readB) return false
                    if (readA <= 0) return true
                    // Compared in place: the valid part of the buffers is not copied into new arrays.
                    if (!java.nio.ByteBuffer.wrap(a, 0, readA).equals(java.nio.ByteBuffer.wrap(b, 0, readB))) return false
                }
            }
        }
    }

    private fun readFully(input: java.io.InputStream, buffer: ByteArray): Int {
        var total = 0
        while (total < buffer.size) {
            val count = input.read(buffer, total, buffer.size - total)
            if (count < 0) break
            total += count
        }
        return total
    }

    private fun plan(source: File, target: File): Plan {
        val entries = ArrayList<Entry>()
        var bytes = 0L
        var largest = 0L
        source.walkTopDown().onEnter { it.isDirectory && !java.nio.file.Files.isSymbolicLink(it.toPath()) }.forEach { file ->
            if (!file.isFile || file.name.endsWith(".part")) return@forEach
            val destination = File(target, file.relativeTo(source).path)
            val copy = needsCopy(file, destination)
            entries += Entry(file, destination, copy)
            if (copy) {
                bytes += file.length()
                largest = maxOf(largest, file.length())
            }
        }
        // .build.info moves last: while it is still in the source folder the launcher keeps offering the switch to finish an interrupted move.
        entries.sortBy { it.source.name == ".build.info" }
        return Plan(entries, bytes, largest)
    }

    /**
     * MOVES the game from [source] to [target]: each file is copied, verified, and only then deleted from [source],
     * so the game never exists twice and the extra space needed is one file, not the whole game. Resumable: after an
     * interruption a repeat run moves what is left. The caller switches the active path only after this returns.
     */
    suspend fun move(source: File, target: File, onChanged: () -> Unit = {}, onProgress: (Progress) -> Unit) {
        // A move that was killed after the last file left the source: the target is already complete.
        if (!source.exists() && File(target, ".build.info").isFile) return
        require(source.isDirectory) { "Source folder is missing: $source" }
        require(source.canonicalFile != target.canonicalFile) { "Source and target are the same folder." }
        require(!target.canonicalPath.startsWith(source.canonicalPath + File.separator) &&
            !source.canonicalPath.startsWith(target.canonicalPath + File.separator)) { "Folders must not contain each other." }
        target.mkdirs()
        val plan = plan(source, target)
        val free = target.usableSpace
        if (plan.largest + SPACE_MARGIN_BYTES > free) {
            throw IOException("NOSPACE:${plan.largest + SPACE_MARGIN_BYTES}:$free")
        }
        var copied = 0L
        var lastReport = 0L
        var changed = false
        onProgress(Progress(0, plan.bytes))
        val buffer = ByteArray(4 * 1024 * 1024)
        for (entry in plan.entries) {
            currentCoroutineContext().ensureActive()
            val from = entry.source
            val to = entry.target
            // The first change to either folder: from here on an interrupted move has to be finished, not forgotten.
            if (!changed) { changed = true; onChanged() }
            if (entry.copy) {
                to.parentFile?.mkdirs()
                val partial = File(to.parentFile, to.name + ".part")
                from.inputStream().use { input ->
                    partial.outputStream().use { output ->
                        while (true) {
                            val count = input.read(buffer)
                            if (count < 0) break
                            output.write(buffer, 0, count)
                            copied += count
                            val now = System.currentTimeMillis()
                            if (now - lastReport >= 300) {
                                lastReport = now
                                onProgress(Progress(copied, plan.bytes))
                                currentCoroutineContext().ensureActive()
                            }
                        }
                    }
                }
                if (partial.length() != from.length()) {
                    partial.delete()
                    throw IOException("Copy of ${from.name} is incomplete.")
                }
                partial.setLastModified(from.lastModified())
                if (to.exists() && !to.delete()) throw IOException("Cannot replace ${to.name}")
                if (!partial.renameTo(to)) throw IOException("Cannot finish ${to.name}")
            }
            // Delete the source file only once the target is verified in place.
            if (!to.isFile || to.length() != from.length()) throw IOException("Verification of ${to.name} failed.")
            if (!from.delete()) throw IOException("Cannot remove the old ${from.name}")
        }
        onProgress(Progress(plan.bytes, plan.bytes))
        // Only empty folders (and stray .part files) can remain; anything else means the move is not complete.
        val leftover = source.walkTopDown().filter { it.isFile && !it.name.endsWith(".part") }.firstOrNull()
        if (leftover != null) throw IOException("Not everything was moved: ${leftover.name}")
        source.deleteRecursively()
    }

    private const val PREFS = "wow_forever"
    private const val KEY_SHARED = "shared_game_path"
    private const val KEY_PENDING = "pending_move_to_private"
    private const val KEY_PENDING_FROM = "pending_move_from"
    private const val KEY_PENDING_TO = "pending_move_to"
    private const val KEY_PENDING_STARTED = "pending_move_started"

    /** A move that was begun and not finished: the exact folders, the direction and whether any file was touched yet. */
    class PendingMove(val toPrivate: Boolean, val from: File, val to: File, val started: Boolean)

    /**
     * Recorded before the first file moves, so a killed or cancelled move is finished on the next launcher start from the
     * SAME folders (the active game path may differ by then). False when the journal could not be written: do not move.
     */
    fun beginMove(context: Context, toPrivate: Boolean, from: File, to: File): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putBoolean(KEY_PENDING, toPrivate)
            .putString(KEY_PENDING_FROM, from.absolutePath).putString(KEY_PENDING_TO, to.absolutePath)
            .putBoolean(KEY_PENDING_STARTED, false).commit()

    /** The first file of the move is about to change: an interrupted run must be resumed from now on. */
    fun markMoveStarted(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY_PENDING_STARTED, true).commit()

    fun endMove(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .remove(KEY_PENDING).remove(KEY_PENDING_FROM).remove(KEY_PENDING_TO).remove(KEY_PENDING_STARTED).commit()
    }

    /** The unfinished move to complete, else null. A journal written by an older build (direction only) is completed from the active path. */
    fun pendingMove(context: Context, currentPath: String): PendingMove? {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (!prefs.contains(KEY_PENDING)) return null
        val toPrivate = prefs.getBoolean(KEY_PENDING, false)
        val from = prefs.getString(KEY_PENDING_FROM, null)?.let(::File) ?: File(currentPath)
        val to = prefs.getString(KEY_PENDING_TO, null)?.let(::File)
            ?: if (toPrivate) privateRoot(context) else sharedPath(context)
        val started = prefs.getBoolean(KEY_PENDING_STARTED, true)
        // Nothing changed and the active path is already the target: only the bookkeeping is left.
        if (runCatching { File(currentPath).canonicalFile == to.canonicalFile }.getOrDefault(false) && !from.isDirectory) {
            endMove(context); return null
        }
        return PendingMove(toPrivate, from, to, started)
    }

    /** True when the finished target holds the game, so a journal whose source is gone can be closed and the path switched. */
    fun targetComplete(target: File): Boolean = File(target, ".build.info").isFile

    /** The shared-storage folder the game came from (the picked one, else the default). */
    fun sharedPath(context: Context): File =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_SHARED, null)?.let(::File) ?: sharedRoot()

    fun rememberShared(context: Context, folder: File) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY_SHARED, folder.absolutePath).apply()
    }
}
