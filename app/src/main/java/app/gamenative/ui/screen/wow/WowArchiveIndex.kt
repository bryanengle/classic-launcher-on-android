package app.gamenative.ui.screen.wow

import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest

/** CDN archive pages are followed by last-key/checksum tables, then a 28-byte footer.
 * The table can exceed one page and must never be interpreted as file records.
 */
internal object WowArchiveIndex {
    fun entryOffsets(data: ByteArray): IntArray {
        fun invalid(): Nothing = throw IOException("Invalid or unsupported CDN archive index")
        if (data.size < 28) invalid()
        val footer = data.copyOfRange(data.size - 20, data.size)
        // Version 1, reserved bytes, 4 KiB pages, 4-byte offsets/sizes, 16-byte keys, 8-byte hashes.
        if (!footer.copyOfRange(0, 8).contentEquals(byteArrayOf(1, 0, 0, 4, 4, 4, 16, 8))) invalid()
        val checksumInput = footer.copyOf().apply { fill(0, 12, 20) }
        val checksum = MessageDigest.getInstance("MD5").digest(checksumInput)
        if (!(0 until 8).all { checksum[it] == footer[12 + it] }) invalid()
        val count = ByteBuffer.wrap(footer).order(ByteOrder.LITTLE_ENDIAN).getInt(8)
        if (count < 0) invalid()
        val perPage = 4096 / 24
        val pages = (count.toLong() + perPage - 1) / perPage
        if (pages * (4096 + 16 + 8) + 28 != data.size.toLong()) invalid()
        return IntArray(count) { (it / perPage) * 4096 + (it % perPage) * 24 }
    }
}
