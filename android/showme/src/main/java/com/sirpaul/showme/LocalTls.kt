package com.sirpaul.showme

import org.bouncycastle.asn1.x500.X500Name
import org.bouncycastle.asn1.x509.*
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder
import org.bouncycastle.jce.provider.BouncyCastleProvider
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder
import java.math.BigInteger
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Date
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLServerSocketFactory

/** Per-session TLS identity. No shared TLS private key is shipped inside the APK. */
data class LocalTls(val sockets: SSLServerSocketFactory, val certificate: ByteArray, val fingerprint: String) {
    companion object {
        fun create(address: String): LocalTls {
            val random = SecureRandom()
            val provider = BouncyCastleProvider()
            val keys = KeyPairGenerator.getInstance("RSA").apply { initialize(2048, random) }.generateKeyPair()
            val name = X500Name("CN=ShowMe local session")
            val now = System.currentTimeMillis()
            val builder = JcaX509v3CertificateBuilder(name, BigInteger(128, random).abs().add(BigInteger.ONE),
                Date(now - 300_000L), Date(now + 7L * 24 * 3600 * 1000), name, keys.public)
            builder.addExtension(Extension.subjectAlternativeName, false, GeneralNames(GeneralName(GeneralName.iPAddress, address)))
            builder.addExtension(Extension.basicConstraints, true, BasicConstraints(false))
            builder.addExtension(Extension.keyUsage, true, KeyUsage(KeyUsage.digitalSignature or KeyUsage.keyEncipherment))
            builder.addExtension(Extension.extendedKeyUsage, false, ExtendedKeyUsage(KeyPurposeId.id_kp_serverAuth))
            val signer = JcaContentSignerBuilder("SHA256withRSA").setProvider(provider).build(keys.private)
            val cert = JcaX509CertificateConverter().setProvider(provider).getCertificate(builder.build(signer))
            val password = java.util.UUID.randomUUID().toString().toCharArray()
            val store = KeyStore.getInstance("PKCS12")
            store.load(null, password)
            store.setKeyEntry("showme", keys.private, password, arrayOf(cert))
            return fromStore(store, password)
        }
        fun fromStore(store: KeyStore, password: CharArray): LocalTls {
            val alias = store.aliases().toList().firstOrNull { store.isKeyEntry(it) } ?: error("No private key in certificate file")
            val certificate = store.getCertificate(alias).encoded
            val manager = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm())
            manager.init(store, password)
            val context = SSLContext.getInstance("TLS")
            context.init(manager.keyManagers, null, SecureRandom())
            val fingerprint = MessageDigest.getInstance("SHA-256").digest(certificate).joinToString(":") { "%02X".format(it) }
            return LocalTls(context.serverSocketFactory, certificate, fingerprint)
        }
    }
}
