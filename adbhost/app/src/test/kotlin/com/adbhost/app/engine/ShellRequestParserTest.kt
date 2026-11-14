package com.adbhost.app.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ShellRequestParserTest {

    @Test
    fun bareShellIsInteractiveV1() {
        val request = ShellRequestParser.parse("shell")
        assertEquals(1, request?.version)
        assertNull(request?.command)
        assertFalse(request?.pty == true)
        assertFalse(request?.raw == true)
    }

    @Test
    fun shellColonIsEmptyInteractive() {
        val request = ShellRequestParser.parse("shell:")
        assertEquals(1, request?.version)
        assertNull(request?.command)
    }

    @Test
    fun commaV2AloneIsInteractiveV2() {
        val request = ShellRequestParser.parse("shell,v2")
        assertEquals(2, request?.version)
        assertNull(request?.command)
    }

    @Test
    fun colonStyleMeansCommandPerAosp() {
        val request = ShellRequestParser.parse("shell:v2")
        assertEquals("v2", request?.command)
    }

    @Test
    fun plainCommand() {
        val request = ShellRequestParser.parse("shell:ls -la")
        assertEquals(1, request?.version)
        assertEquals("ls -la", request?.command)
    }

    @Test
    fun commandWithColonsIsPreserved() {
        val request = ShellRequestParser.parse("shell:echo a:b")
        assertEquals("echo a:b", request?.command)
    }

    @Test
    fun v2RawWithCommand() {
        val request = ShellRequestParser.parse("shell,v2,raw:echo hi")
        assertEquals(2, request?.version)
        assertTrue(request?.raw == true)
        assertFalse(request?.pty == true)
        assertEquals("echo hi", request?.command)
    }

    @Test
    fun shellCommaPtyPrefix() {
        val request = ShellRequestParser.parse("shell,pty:top")
        assertTrue(request?.pty == true)
        assertEquals("top", request?.command)
    }

    @Test
    fun modifierOrderIndependent() {
        val request = ShellRequestParser.parse("shell,raw,v2,pty:top")
        assertEquals(2, request?.version)
        assertTrue(request?.pty == true)
        assertTrue(request?.raw == true)
        assertEquals("top", request?.command)
    }

    @Test
    fun trailingColonMeansInteractive() {
        val request = ShellRequestParser.parse("shell,v2,pty:")
        assertEquals(2, request?.version)
        assertTrue(request?.pty == true)
        assertNull(request?.command)
    }

    @Test
    fun nonShellReturnsNull() {
        assertNull(ShellRequestParser.parse("sync:"))
        assertNull(ShellRequestParser.parse("features"))
    }
}
