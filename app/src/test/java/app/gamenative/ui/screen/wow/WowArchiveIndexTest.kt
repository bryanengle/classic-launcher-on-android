package app.gamenative.ui.screen.wow

import org.junit.Assert.*
import org.junit.Test
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest

internal fun archiveIndexFixture(count: Int): ByteArray {
    val pages = (count + 169) / 170
    val data = ByteArray(pages * (4096 + 24) + 28)
    val footer = ByteArray(20)
    byteArrayOf(1, 0, 0, 4, 4, 4, 16, 8).copyInto(footer)
    ByteBuffer.wrap(footer).order(ByteOrder.LITTLE_ENDIAN).putInt(8, count)
    MessageDigest.getInstance("MD5").digest(footer).copyInto(footer, 12, 0, 8)
    footer.copyInto(data, data.size - 20)
    return data
}

class WowArchiveIndexTest {
    @Test fun largeLookupTableCannotOverwriteARealEntryWithAGigabyteSize() {
        // Classic's 29,609-entry archive has 175 data pages, followed by a 4,200-byte table.
        // The old parser treated that table's first page as more file entries.
        val data = archiveIndexFixture(29609)
        val real = ByteArray(16) { (it + 1).toByte() }
        real.copyInto(data)
        ByteBuffer.wrap(data).putInt(16, 1091)
        val table = 175 * 4096
        real.copyInto(data, table)
        ByteBuffer.wrap(data).putInt(table + 16, 1135560682)
        val offsets = WowArchiveIndex.entryOffsets(data)
        val matches = offsets.filter { p -> (0 until 16).all { data[p + it] == real[it] } }
        assertEquals(listOf(0), matches)
        assertEquals(1091, ByteBuffer.wrap(data).getInt(matches.single() + 16))
        assertEquals(29609, offsets.size)
        assertTrue(offsets.all { it < table })
    }

    @Test fun skipsPagePaddingAndStopsAtTheDeclaredEntryCount() {
        val offsets = WowArchiveIndex.entryOffsets(archiveIndexFixture(171))
        assertEquals(169 * 24, offsets[169])
        assertEquals(4096, offsets[170])
        assertEquals(171, offsets.size)
        assertEquals(0, WowArchiveIndex.entryOffsets(archiveIndexFixture(0)).size)
    }

    @Test fun corruptFooterIsRejectedBeforeItsCountCanDriveAnAllocation() {
        val data = archiveIndexFixture(1)
        data[data.size - 12] = 127
        assertThrows(IOException::class.java) { WowArchiveIndex.entryOffsets(data) }
    }

    @Test fun truncatedAndPaddedIndexesAreRejected() {
        val data = archiveIndexFixture(171)
        assertThrows(IOException::class.java) { WowArchiveIndex.entryOffsets(data.copyOf(data.size - 1)) }
        assertThrows(IOException::class.java) { WowArchiveIndex.entryOffsets(byteArrayOf(0) + data) }
        assertThrows(IOException::class.java) { WowArchiveIndex.entryOffsets(ByteArray(12)) }
    }

    @Test fun groupAndLooseFileIndexFormatsAreNotReadAsArchiveRecords() {
        for (offsetBytes in listOf(0, 5, 6)) {
            val data = archiveIndexFixture(1)
            data[data.size - 16] = offsetBytes.toByte()
            assertThrows(IOException::class.java) { WowArchiveIndex.entryOffsets(data) }
        }
    }
}
