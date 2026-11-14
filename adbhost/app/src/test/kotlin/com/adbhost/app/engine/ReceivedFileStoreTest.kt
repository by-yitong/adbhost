package com.adbhost.app.engine

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ReceivedFileStoreTest {

    @get:Rule
    val temp = TemporaryFolder()

    private fun newStore(): Pair<ReceivedFileStore, File> {
        val root = temp.newFolder()
        return ReceivedFileStore(File(temp.root, "received_files.txt"), root) to root
    }

    private fun push(store: ReceivedFileStore, root: File, relativePath: String, content: String = "data") {
        val file = File(root, relativePath)
        file.parentFile?.mkdirs()
        file.writeText(content)
        store.record(file, relativePath)
    }

    @Test
    fun recordThenListSortsNewestFirst() {
        val (s, root) = newStore()
        push(s, root, "a.txt", "aaa")
        Thread.sleep(5)
        push(s, root, "b/c.jpg", "bb")
        val files = s.list()
        assertEquals(listOf("b/c.jpg", "a.txt"), files.map { it.relativePath })
        assertEquals(3L, files.first { it.relativePath == "a.txt" }.size)
    }

    @Test
    fun recordSamePathReplacesOldEntry() {
        val (s, root) = newStore()
        push(s, root, "a.txt", "one")
        push(s, root, "a.txt", "two-longer")
        val files = s.list()
        assertEquals(1, files.size)
        assertEquals("two-longer".length.toLong(), files.single().size)
    }

    @Test
    fun listPersistsAcrossInstances() {
        val (s1, root) = newStore()
        val file = File(root, "x/y.bin").apply { parentFile.mkdirs(); writeText("hello") }
        s1.record(file, "x/y.bin")
        val s2 = ReceivedFileStore(File(temp.root, "received_files.txt"), root)
        assertEquals(listOf("x/y.bin"), s2.list().map { it.relativePath })
    }

    @Test
    fun specialCharactersRoundTrip() {
        val (s, root) = newStore()
        push(s, root, "照片 |1.txt", "x")
        assertEquals(listOf("照片 |1.txt"), s.list().map { it.relativePath })
    }

    @Test
    fun clearDeletesFilesRecordsAndEmptyDirs() {
        val (s, root) = newStore()
        push(s, root, "a.txt")
        push(s, root, "nested/deep/b.txt")
        assertEquals(2, s.clear())
        assertTrue(s.list().isEmpty())
        assertFalse(File(root, "a.txt").exists())
        assertFalse(File(root, "nested/deep/b.txt").exists())
        assertFalse(File(root, "nested/deep").exists())
        assertTrue(root.exists())
    }

    @Test
    fun clearOnEmptyStoreReturnsZero() {
        assertEquals(0, newStore().first.clear())
    }

    @Test
    fun shouldRecordExcludesTmpStaging() {
        assertTrue(ReceivedFileStore.shouldRecord("DCIM/photo.jpg"))
        assertFalse(ReceivedFileStore.shouldRecord("tmp/base.apk"))
        assertFalse(ReceivedFileStore.shouldRecord(""))
    }

    @Test
    fun malformedLinesIgnored() {
        val storeFile = File(temp.root, "received_files.txt")
        val (s, _) = newStore()
        storeFile.writeText("garbage\n999|5|" + java.net.URLEncoder.encode("ok.txt", "UTF-8") + "\n|abc|x\n")
        assertEquals(listOf("ok.txt"), s.list().map { it.relativePath })
    }
}
