package com.adbhost.app.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class InstallArgsParserTest {

    @Test
    fun parsesAdb37PushInstallCommand() {
        val args = InstallArgsParser.parse("pm install -r /data/local/tmp/base.apk")
        assertNull(args.parseError)
        assertEquals(listOf("/data/local/tmp/base.apk"), args.apkPaths)
        assertTrue(args.replace)
        assertTrue(!args.allowTest && !args.allowDowngrade && !args.grantRuntimePermissions)
    }

    @Test
    fun parsesFlagsInAnyOrder() {
        val args = InstallArgsParser.parse("pm install -t -d -g -r /data/local/tmp/a.apk")
        assertNull(args.parseError)
        assertTrue(args.allowTest && args.allowDowngrade && args.grantRuntimePermissions)
        assertEquals(listOf("/data/local/tmp/a.apk"), args.apkPaths)
    }

    @Test
    fun parsesCombinedShortFlags() {
        val args = InstallArgsParser.parse("pm install -tdg /data/local/tmp/a.apk")
        assertNull(args.parseError)
        assertTrue(args.allowTest && args.allowDowngrade && args.grantRuntimePermissions)
    }

    @Test
    fun cmdPackagePrefixIsAccepted() {
        val args = InstallArgsParser.parse("cmd package install -r /data/local/tmp/a.apk")
        assertNotNull(args)
        assertEquals(listOf("/data/local/tmp/a.apk"), args.apkPaths)
    }

    @Test
    fun streamingSizeMarkerIgnored() {
        val args = InstallArgsParser.parse("pm install -S 523178 -r")
        assertNull(args.parseError)
        assertTrue(args.apkPaths.isEmpty())
    }

    @Test
    fun unknownFlagFails() {
        val args = InstallArgsParser.parse("pm install --user 0 /data/local/tmp/a.apk")
        assertEquals("unsupported flag --user", args.parseError)
    }

    @Test
    fun missingApkFails() {
        val args = InstallArgsParser.parse("pm install -r")
        assertEquals("no apk path", args.parseError)
    }
}
