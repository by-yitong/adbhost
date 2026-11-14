package com.adbhost.app.core

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class TrustStoreTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val certFp = "AA:BB:CC:DD:EE:FF:00:11:22:33:44:55:66:77:88:99:AA:BB:CC:DD"

    @Test
    fun adbKeyRoundTripAndAtomicFileExists() {
        val dir = tmp.newFolder()
        val store = TrustStore(dir)
        assertTrue(store.trustedAdbPublicKeys().isEmpty())
        store.addAdbPublicKey(TestKeys.publicKeyLine)
        assertTrue(File(dir, "adb_keys").exists())
        val keys = store.trustedAdbPublicKeys()
        assertEquals(1, keys.size)
        assertEquals(2048, keys[0].modulus.bitLength())
    }

    @Test
    fun addAdbPublicKeyIsIdempotentPerFingerprint() {
        val store = TrustStore(tmp.newFolder())
        store.addAdbPublicKey(TestKeys.publicKeyLine)
        store.addAdbPublicKey(TestKeys.publicKeyLine + " again")
        assertEquals(1, store.trustedAdbPublicKeys().size)
    }

    @Test
    fun adbKeyListEntryCarriesNameAndFingerprint() {
        val store = TrustStore(tmp.newFolder())
        store.addAdbPublicKey(TestKeys.publicKeyLine)
        val entries = store.listEntries()
        assertEquals(1, entries.size)
        assertEquals(TrustStore.KIND_ADB, entries[0].kind)
        assertEquals(TestKeys.publicKeyLine.substringAfter(' '), entries[0].name)
        assertTrue(entries[0].fingerprint.matches(Regex("^([0-9A-F]{2}:){19}[0-9A-F]{2}$")))
    }

    @Test
    fun removeAdbPublicKey() {
        val store = TrustStore(tmp.newFolder())
        store.addAdbPublicKey(TestKeys.publicKeyLine)
        val fingerprint = store.listEntries()[0].fingerprint
        assertTrue(store.removeAdbPublicKey(fingerprint))
        assertTrue(store.trustedAdbPublicKeys().isEmpty())
        assertFalse(store.removeAdbPublicKey(fingerprint))
    }

    @Test
    fun certFingerprintRoundTripAndRemoval() {
        val dir = tmp.newFolder()
        val store = TrustStore(dir)
        assertTrue(store.trustedCertFingerprints().isEmpty())
        store.addCertFingerprint(certFp.lowercase())
        assertTrue(File(dir, "cert_fps.txt").exists())
        assertEquals(setOf(certFp), store.trustedCertFingerprints())
        store.addCertFingerprint(certFp)
        assertEquals(1, store.trustedCertFingerprints().size)
        val entries = store.listEntries()
        assertEquals(1, entries.size)
        assertEquals(TrustStore.KIND_CERT, entries[0].kind)
        assertTrue(entries[0].addedAt > 0)
        assertTrue(store.removeCertFingerprint(certFp.lowercase()))
        assertTrue(store.trustedCertFingerprints().isEmpty())
        assertFalse(store.removeCertFingerprint(certFp))
    }

    @Test
    fun mixedEntriesAreListedTogether() {
        val store = TrustStore(tmp.newFolder())
        store.addAdbPublicKey(TestKeys.publicKeyLine)
        store.addCertFingerprint(certFp)
        val kinds = store.listEntries().map { it.kind }.sorted()
        assertEquals(listOf(TrustStore.KIND_ADB, TrustStore.KIND_CERT), kinds)
    }

    @Test
    fun certEntryCarriesNameOnAdd() {
        val store = TrustStore(tmp.newFolder())
        store.addCertFingerprint(certFp, "DESKTOP-ABC")
        val entry = store.listEntries().single()
        assertEquals("DESKTOP-ABC", entry.name)
    }

    @Test
    fun certNameUpsertBackfillsAndKeepsTimestamp() {
        val store = TrustStore(tmp.newFolder())
        store.addCertFingerprint(certFp)
        val original = store.listEntries().single().addedAt
        store.addCertFingerprint(certFp, "新主机名")
        val entry = store.listEntries().single()
        assertEquals("新主机名", entry.name)
        assertEquals(original, entry.addedAt)
        assertEquals(1, store.listEntries().size)
    }

    @Test
    fun certNameSanitizesSeparators() {
        val store = TrustStore(tmp.newFolder())
        store.addCertFingerprint(certFp, "bad|name\nsecond")
        val entry = store.listEntries().single()
        assertFalse(entry.name.contains('|'))
        assertFalse(entry.name.contains('\n'))
        assertEquals(setOf(certFp), store.trustedCertFingerprints())
    }
}
