package com.adbhost.app.core

import java.security.Signature
import java.security.cert.Certificate

enum class CertDecision { TRUSTED, NEW, REJECTED }

class AuthEngine(val identity: RsaIdentity, val trust: TrustStore) {

    fun verifySignature(token: ByteArray, signature: ByteArray): Boolean =
        trust.trustedAdbPublicKeys().any { key ->
            runCatching {
                val verifier = Signature.getInstance("SHA1withRSA")
                verifier.initVerify(key)
                verifier.update(token)
                verifier.verify(signature)
            }.getOrDefault(false)
        }

    fun verifyHostCert(cert: Certificate): CertDecision {
        val fingerprint = sha1FingerprintHex(cert.publicKey.encoded)
        return if (fingerprint.uppercase() in trust.trustedCertFingerprints()) CertDecision.TRUSTED else CertDecision.NEW
    }
}
