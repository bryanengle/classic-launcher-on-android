package app.gamenative.ui.screen.wow

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class WowGameStorageTest {
    @get:Rule val temp = TemporaryFolder()

    private fun game(root: File) {
        File(root, "Data/a").mkdirs()
        File(root, "Data/a/one.bin").writeBytes(ByteArray(10_000) { it.toByte() })
        File(root, "Data/two.bin").writeBytes(ByteArray(5) { 7 })
        File(root, "_classic_era_").mkdirs()
        File(root, "_classic_era_/WowClassic-arm64.exe").writeBytes(ByteArray(300) { 1 })
        File(root, ".build.info").writeText("Product!STRING:0\nwow_classic_era\n")
    }

    @Test fun moveLeavesOnlyTheTarget() = runBlocking {
        val source = File(temp.root, "src").also { it.mkdirs(); game(it) }
        val target = File(temp.root, "dst")
        var last = -1L
        WowGameStorage.move(source, target) { last = it.copiedBytes }
        assertFalse("the source must be gone", source.exists())
        assertEquals(10_000L, File(target, "Data/a/one.bin").length())
        assertEquals(5L, File(target, "Data/two.bin").length())
        assertTrue(File(target, ".build.info").isFile)
        assertTrue(File(target, "_classic_era_/WowClassic-arm64.exe").isFile)
        assertEquals(10_305L + File(target, ".build.info").length(), last)
    }

    @Test fun interruptedMoveResumes() = runBlocking {
        val source = File(temp.root, "src").also { it.mkdirs(); game(it) }
        val target = File(temp.root, "dst")
        // A previous run already moved one file.
        File(target, "Data").mkdirs()
        File(source, "Data/two.bin").copyTo(File(target, "Data/two.bin"))
        File(source, "Data/two.bin").delete()
        WowGameStorage.move(source, target) { }
        assertFalse(source.exists())
        assertEquals(5L, File(target, "Data/two.bin").length())
        assertEquals(10_000L, File(target, "Data/a/one.bin").length())
    }

    @Test fun refusesNestedFolders() {
        val source = File(temp.root, "src").also { it.mkdirs(); game(it) }
        val result = runCatching { runBlocking { WowGameStorage.move(source, File(source, "inner")) { } } }
        assertTrue(result.isFailure)
        assertTrue(File(source, "Data/two.bin").isFile)
    }
}

class WowGameStorageResumeTest {
    @get:Rule val temp = TemporaryFolder()

    @Test fun finishedTargetWithoutSourceIsAcceptedAsDone() = runBlocking {
        val target = File(temp.root, "dst").also { it.mkdirs(); File(it, ".build.info").writeText("x") }
        WowGameStorage.move(File(temp.root, "gone"), target) { }
        assertTrue(File(target, ".build.info").isFile)
    }

    @Test fun halfMovedFolderIsResumedAndCompleted() = runBlocking {
        val source = File(temp.root, "src").also { File(it, "Data").mkdirs(); File(it, ".build.info").writeText("b") }
        File(source, "Data/left.bin").writeBytes(ByteArray(100) { 3 })
        val target = File(temp.root, "dst").also { File(it, "Data").mkdirs(); File(it, "Data/done.bin").writeBytes(ByteArray(50) { 2 }) }
        WowGameStorage.move(source, target) { }
        assertFalse(source.exists())
        assertEquals(100L, File(target, "Data/left.bin").length())
        assertEquals(50L, File(target, "Data/done.bin").length())
        assertTrue(File(target, ".build.info").isFile)
    }
}

class WowGameStorageContentTest {
    @get:Rule val temp = TemporaryFolder()

    @Test fun sameLengthButDifferentBytesAreCopiedNotDropped() = runBlocking {
        val source = File(temp.root, "src").also { File(it, "Data").mkdirs(); File(it, ".build.info").writeText("b") }
        File(source, "Data/WTF.bin").writeBytes(ByteArray(64) { 1 })
        val target = File(temp.root, "dst").also { File(it, "Data").mkdirs() }
        val clash = File(target, "Data/WTF.bin").also { it.writeBytes(ByteArray(64) { 2 }); it.setLastModified(System.currentTimeMillis() + 60_000) }
        WowGameStorage.move(source, target) { }
        assertFalse(source.exists())
        assertTrue("the source bytes win", clash.readBytes().all { it == 1.toByte() })
    }

    @Test fun identicalExistingTargetIsKept() = runBlocking {
        val source = File(temp.root, "src").also { File(it, "Data").mkdirs(); File(it, ".build.info").writeText("b") }
        File(source, "Data/same.bin").writeBytes(ByteArray(10_000) { (it % 7).toByte() })
        val target = File(temp.root, "dst").also { File(it, "Data").mkdirs() }
        File(target, "Data/same.bin").writeBytes(ByteArray(10_000) { (it % 7).toByte() })
        var changed = 0
        WowGameStorage.move(source, target, onChanged = { changed++ }) { }
        assertEquals(1, changed)
        assertFalse(source.exists())
        assertEquals(10_000L, File(target, "Data/same.bin").length())
    }

    @Test fun contentComparisonDetectsASingleDifferingByte() {
        val a = File(temp.root, "a").also { it.writeBytes(ByteArray(3_000_000) { 5 }) }
        val b = File(temp.root, "b").also { it.writeBytes(ByteArray(3_000_000) { 5 }) }
        assertTrue(WowGameStorage.sameContent(a, b))
        b.writeBytes(ByteArray(3_000_000) { if (it == 2_999_999) 6 else 5 })
        assertFalse(WowGameStorage.sameContent(a, b))
    }
}

@org.junit.runner.RunWith(org.robolectric.RobolectricTestRunner::class)
@org.robolectric.annotation.Config(application = android.app.Application::class, sdk = [29])
class WowGameStorageJournalTest {
    private val context get() = androidx.test.core.app.ApplicationProvider.getApplicationContext<android.content.Context>()

    @Test fun journalKeepsTheExactFoldersAndDirection() {
        val from = File("/data/local/a/Game"); val to = File(WowGameStorage.privateRoot(context).path)
        assertTrue(WowGameStorage.beginMove(context, true, from, to))
        // The active path later falls back to another install: the journal still names the original source.
        val pending = WowGameStorage.pendingMove(context, "/storage/emulated/0/WoW Forever")!!
        assertTrue(pending.toPrivate); assertEquals(from.path, pending.from.path); assertEquals(to.path, pending.to.path)
        assertFalse("nothing touched yet", pending.started)
        WowGameStorage.markMoveStarted(context)
        assertTrue(WowGameStorage.pendingMove(context, "/x")!!.started)
        WowGameStorage.endMove(context)
        assertNull(WowGameStorage.pendingMove(context, "/x"))
    }

    @Test fun journalIsClosedWhenTheActivePathAlreadyIsTheTargetAndTheSourceIsGone() {
        val to = File(WowGameStorage.privateRoot(context).path).also { it.mkdirs() }
        WowGameStorage.beginMove(context, true, File("/nonexistent/source"), to)
        assertNull(WowGameStorage.pendingMove(context, to.absolutePath))
        assertNull("the journal is gone", WowGameStorage.pendingMove(context, "/x"))
    }
}
