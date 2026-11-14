package com.adbhost.app.engine

import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SyncPathMapperTest {

    private val root: File = Files.createTempDirectory("adbhost-sync").toFile()

    @Test
    fun sdcardMapsToRoot() {
        assertEquals(File(root, "a.txt").path, SyncPathMapper.toLocal(root, "/sdcard/a.txt")?.path)
    }

    @Test
    fun sdcardSubdirectoryMapsToRoot() {
        assertEquals(File(root, "sub/a.txt").path, SyncPathMapper.toLocal(root, "/sdcard/sub/a.txt")?.path)
    }

    @Test
    fun sdcardRootItselfMapsToRoot() {
        assertEquals(root.path, SyncPathMapper.toLocal(root, "/sdcard")?.path)
        assertEquals(root.path, SyncPathMapper.toLocal(root, "/sdcard/")?.path)
    }

    @Test
    fun dataLocalTmpMapsToTmp() {
        assertEquals(File(root, "tmp/x.bin").path, SyncPathMapper.toLocal(root, "/data/local/tmp/x.bin")?.path)
        assertEquals(File(root, "tmp").path, SyncPathMapper.toLocal(root, "/data/local/tmp")?.path)
    }

    @Test
    fun otherAbsoluteMapsUnderRoot() {
        assertEquals(File(root, "etc/hosts").path, SyncPathMapper.toLocal(root, "/etc/hosts")?.path)
    }

    @Test
    fun relativePathRejected() {
        assertNull(SyncPathMapper.toLocal(root, "foo/bar"))
        assertNull(SyncPathMapper.toLocal(root, ""))
    }

    @Test
    fun traversalRejected() {
        val escaped = SyncPathMapper.toLocal(root, "/sdcard/../../etc/passwd")
        assertNull(escaped)
        val parent = root.parentFile
        if (escaped != null && parent != null) {
            assertFalse(escaped.canonicalPath.startsWith(parent.canonicalPath.take(parent.canonicalPath.length)))
        }
        assertEquals(
            File(root, "inner").canonicalPath,
            SyncPathMapper.toLocal(root, "/sdcard/sub/../inner")?.canonicalPath,
        )
    }
}
