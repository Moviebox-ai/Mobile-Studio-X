package com.aaryo.mobilestudio.toolchain

import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
import java.security.PrivateKey
import java.security.Signature
import java.security.cert.X509Certificate
import java.util.Base64
import java.util.jar.JarFile
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

/** Outcome of verifying the V1 (JAR) signature of an APK. */
data class JarSignatureVerification(
    val isSigned: Boolean,
    val signers: List<String>,
    val signedEntries: Int,
    val unsignedEntries: List<String>,
    val details: String
)

/**
 * Spec-compliant JAR (APK signature scheme v1) signer.
 *
 * The previous implementation digested only `Name: <entry>` headers, so every APK it produced
 * carried a `CERT.SF` whose digests could never match `MANIFEST.MF`. Android's package parser
 * rejects such packages (`INSTALL_PARSE_FAILED_NO_CERTIFICATES` / "no certificates"), which is
 * why on-device builds could not be installed.
 *
 * This implementation follows the JAR file specification:
 *  - `MANIFEST.MF` holds a per-entry section with `SHA-256-Digest` of the uncompressed bytes.
 *  - `CERT.SF` holds `SHA-256-Digest-Manifest` plus one section per manifest section, where each
 *    digest covers the *exact bytes* of that manifest section.
 *  - `CERT.RSA` is a PKCS#7 SignedData block whose signature covers the `CERT.SF` bytes.
 * Long attributes are folded at 70 UTF-8 bytes per line (72 including CRLF), as required by the
 * specification.
 */
object JarV1Signer {
    private const val CRLF = "\r\n"
    private const val DIGEST_ALGORITHM = "SHA-256"
    private const val DIGEST_HEADER = "SHA-256-Digest"
    private const val DIGEST_MANIFEST_HEADER = "SHA-256-Digest-Manifest"

    /**
     * Signs [unsignedApk] and writes the result to [signedApk].
     *
     * Entries are copied byte-for-byte; stored (uncompressed) entries such as `resources.arsc`
     * stay stored so the caller can still run `zipalign` afterwards.
     */
    fun sign(
        unsignedApk: File,
        signedApk: File,
        privateKey: PrivateKey,
        certificate: X509Certificate,
        createdBy: String = "Mobile Studio X"
    ): File {
        require(unsignedApk.isFile) { "Input archive does not exist: ${unsignedApk.name}" }
        signedApk.parentFile?.mkdirs()

        val manifestSections = LinkedHashMap<String, ByteArray>()

        // 1. MANIFEST.MF — main section plus one section per entry.
        val manifest = StringBuilder()
        appendFolded(manifest, "Manifest-Version", "1.0")
        appendFolded(manifest, "Created-By", createdBy)
        manifest.append(CRLF)

        ZipFile(unsignedApk).use { zip ->
            for (entry in zip.entries()) {
                if (entry.isDirectory || entry.name.startsWith("META-INF/")) continue
                val digest = MessageDigest.getInstance(DIGEST_ALGORITHM)
                zip.getInputStream(entry).use { input ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        if (read > 0) digest.update(buffer, 0, read)
                    }
                }
                val section = StringBuilder()
                appendFolded(section, "Name", entry.name)
                appendFolded(section, DIGEST_HEADER, base64(digest.digest()))
                section.append(CRLF)
                val sectionBytes = section.toString().toByteArray(Charsets.UTF_8)
                manifest.append(String(sectionBytes, Charsets.UTF_8))
                manifestSections[entry.name] = sectionBytes
            }
        }

        val manifestBytes = manifest.toString().toByteArray(Charsets.UTF_8)

        // 2. CERT.SF — digests of the manifest as a whole and of every individual section.
        val signatureFile = StringBuilder()
        appendFolded(signatureFile, "Signature-Version", "1.0")
        appendFolded(signatureFile, "Created-By", createdBy)
        appendFolded(signatureFile, DIGEST_MANIFEST_HEADER, base64(digest(manifestBytes)))
        signatureFile.append(CRLF)
        for ((name, sectionBytes) in manifestSections) {
            appendFolded(signatureFile, "Name", name)
            appendFolded(signatureFile, DIGEST_HEADER, base64(digest(sectionBytes)))
            signatureFile.append(CRLF)
        }
        val signatureFileBytes = signatureFile.toString().toByteArray(Charsets.UTF_8)

        // 3. Sign CERT.SF and wrap the signature into a PKCS#7 SignedData block.
        val signer = Signature.getInstance("SHA256withRSA")
        signer.initSign(privateKey)
        signer.update(signatureFileBytes)
        val signatureBytes = signer.sign()
        val pkiBlock = Pkcs7SignedData.build(certificate, signatureBytes)

        // 4. Emit the signed archive.
        ZipOutputStream(FileOutputStream(signedApk).buffered()).use { output ->
            copyEntries(ZipFile(unsignedApk), output)
            writeEntry(output, "META-INF/MANIFEST.MF", manifestBytes)
            writeEntry(output, "META-INF/CERT.SF", signatureFileBytes)
            writeEntry(output, "META-INF/CERT.RSA", pkiBlock)
        }
        return signedApk
    }

    private fun copyEntries(zip: ZipFile, output: ZipOutputStream) {
        for (entry in zip.entries()) {
            if (entry.name.startsWith("META-INF/")) continue
            val copy = ZipEntry(entry.name)
            copy.time = 0L
            if (entry.method == ZipEntry.STORED) {
                copy.method = ZipEntry.STORED
                copy.size = entry.size
                copy.compressedSize = entry.size
                copy.crc = entry.crc
            }
            output.putNextEntry(copy)
            zip.getInputStream(entry).use { input -> input.copyTo(output, 64 * 1024) }
            output.closeEntry()
        }
    }

    private fun writeEntry(output: ZipOutputStream, name: String, bytes: ByteArray) {
        val entry = ZipEntry(name)
        entry.time = 0L
        output.putNextEntry(entry)
        output.write(bytes)
        output.closeEntry()
    }

    /**
     * Appends `header: value` to [builder], folding it so no line exceeds 72 bytes including CRLF.
     * Continuation lines start with a single space, as the JAR specification requires.
     */
    internal fun appendFolded(builder: StringBuilder, header: String, value: String) {
        val full = "$header: $value"
        var index = 0
        var continuation = false
        while (index < full.length) {
            val budget = if (continuation) 69 else 70
            val end = minOf(index + budget, full.length)
            if (continuation) builder.append(' ')
            builder.append(full, index, end)
            builder.append(CRLF)
            index = end
            continuation = true
        }
        if (full.isEmpty()) builder.append(CRLF)
    }

    private fun digest(bytes: ByteArray): ByteArray = MessageDigest.getInstance(DIGEST_ALGORITHM).digest(bytes)

    private fun base64(bytes: ByteArray): String = Base64.getEncoder().encodeToString(bytes)
}

/**
 * Verifies the V1 (JAR) signature of an archive using the platform `JarFile` verifier, which
 * checks the PKCS#7 signature, the `CERT.SF` digests and every entry digest.
 */
object JarV1Verifier {

    fun verify(archive: File): JarSignatureVerification {
        if (!archive.isFile) {
            return JarSignatureVerification(false, emptyList(), 0, emptyList(), "Archive not found: ${archive.name}")
        }
        return runCatching {
            var signedEntries = 0
            val unsignedEntries = mutableListOf<String>()
            val signers = linkedSetOf<String>()

            JarFile(archive, true).use { jar ->
                val entries = jar.entries()
                while (entries.hasMoreElements()) {
                    val entry = entries.nextElement()
                    if (entry.isDirectory || entry.name.startsWith("META-INF/")) continue
                    // The verifier only attaches certificates once the whole entry has been
                    // streamed, so drain the stream completely.
                    jar.getInputStream(entry).use { input ->
                        val buffer = ByteArray(16 * 1024)
                        while (input.read(buffer) >= 0) {
                            // drain
                        }
                    }
                    val certificates = entry.certificates
                    if (certificates.isNullOrEmpty()) {
                        unsignedEntries += entry.name
                    } else {
                        signedEntries++
                        certificates.forEach { certificate ->
                            if (certificate is X509Certificate) signers += certificate.subjectX500Principal.name
                        }
                    }
                }
            }

            val signed = signedEntries > 0 && unsignedEntries.isEmpty()
            JarSignatureVerification(
                isSigned = signed,
                signers = signers.toList(),
                signedEntries = signedEntries,
                unsignedEntries = unsignedEntries,
                details = when {
                    signed -> "V1 (JAR) signature verified for all $signedEntries entries."
                    signedEntries == 0 -> "No V1 signature found (META-INF/MANIFEST.MF + CERT.SF missing)."
                    else -> "V1 signature is incomplete: ${unsignedEntries.size} entries are unsigned."
                }
            )
        }.getOrElse { error ->
            JarSignatureVerification(
                isSigned = false,
                signers = emptyList(),
                signedEntries = 0,
                unsignedEntries = emptyList(),
                details = "V1 signature verification failed: ${error.message ?: error.javaClass.simpleName}"
            )
        }
    }
}

/** Minimal PKCS#7 / ASN.1 DER encoder used for the V1 `CERT.RSA` block. */
internal object Pkcs7SignedData {

    fun build(certificate: X509Certificate, signature: ByteArray): ByteArray {
        val sha256Oid = oid(0x60, 0x86, 0x48, 0x01, 0x65, 0x03, 0x04, 0x02, 0x01)
        val rsaEncryptionOid = oid(0x2A, 0x86, 0x48, 0x86, 0xF7, 0x0D, 0x01, 0x01, 0x01)
        val dataOid = oid(0x2A, 0x86, 0x48, 0x86, 0xF7, 0x0D, 0x01, 0x07, 0x01)
        val signedDataOid = oid(0x2A, 0x86, 0x48, 0x86, 0xF7, 0x0D, 0x01, 0x07, 0x02)

        val signerVersion = Asn1.integer(1)
        val issuer = certificate.issuerX500Principal.encoded
        val serial = Asn1.integer(certificate.serialNumber)
        val issuerAndSerial = Asn1.sequence(issuer, serial)

        val digestAlgorithm = Asn1.sequence(sha256Oid, Asn1.nullValue())
        val digestAlgorithms = Asn1.set(digestAlgorithm)
        val encryptionAlgorithm = Asn1.sequence(rsaEncryptionOid, Asn1.nullValue())

        val signerInfo = Asn1.sequence(
            signerVersion,
            issuerAndSerial,
            digestAlgorithm,
            encryptionAlgorithm,
            Asn1.octetString(signature)
        )

        val certificates = Asn1.tagged(0xA0, certificate.encoded)
        val encapsulatedContent = Asn1.sequence(dataOid)

        val signedData = Asn1.sequence(
            Asn1.integer(1),
            digestAlgorithms,
            encapsulatedContent,
            certificates,
            Asn1.set(signerInfo)
        )

        return Asn1.sequence(signedDataOid, Asn1.tagged(0xA0, signedData))
    }

    private fun oid(vararg arcs: Int): ByteArray {
        val body = ByteArray(arcs.size)
        for (i in arcs.indices) body[i] = arcs[i].toByte()
        return Asn1.tagged(0x06, body)
    }
}

/** Tiny ASN.1 DER encoder (definite lengths only). */
internal object Asn1 {

    fun tagged(tag: Int, content: ByteArray): ByteArray {
        val header = byteArrayOf(tag.toByte()) + encodeLength(content.size)
        return header + content
    }

    fun sequence(vararg elements: ByteArray): ByteArray = tagged(0x30, elements.reduceOrNull { acc, next -> acc + next } ?: ByteArray(0))

    fun set(vararg elements: ByteArray): ByteArray = tagged(0x31, elements.reduceOrNull { acc, next -> acc + next } ?: ByteArray(0))

    fun integer(value: Int): ByteArray = integer(java.math.BigInteger.valueOf(value.toLong()))

    fun integer(value: java.math.BigInteger): ByteArray {
        var bytes = value.toByteArray()
        if (bytes.isEmpty()) bytes = byteArrayOf(0)
        // Reject negative encodings for positive values (BigInteger already handles sign).
        return tagged(0x02, bytes)
    }

    fun octetString(bytes: ByteArray): ByteArray = tagged(0x04, bytes)

    fun nullValue(): ByteArray = byteArrayOf(0x05, 0x00)

    fun utf8String(value: String): ByteArray = tagged(0x0C, value.toByteArray(Charsets.UTF_8))

    /**
     * Encodes a timestamp as UTCTime (tag 0x17) below 2050 and as GeneralizedTime (tag 0x18)
     * from 2050 onwards, which is what RFC 5280 requires for long-lived signing certificates.
     */
    fun time(value: java.util.Date): ByteArray {
        val calendar = java.util.Calendar.getInstance(java.util.TimeZone.getTimeZone("UTC"), java.util.Locale.US)
        calendar.time = value
        val year = calendar.get(java.util.Calendar.YEAR)
        return if (year >= 2050) {
            val format = java.text.SimpleDateFormat("yyyyMMddHHmmss'Z'", java.util.Locale.US)
            format.timeZone = java.util.TimeZone.getTimeZone("UTC")
            tagged(0x18, format.format(value).toByteArray(Charsets.US_ASCII))
        } else {
            val format = java.text.SimpleDateFormat("yyMMddHHmmss'Z'", java.util.Locale.US)
            format.timeZone = java.util.TimeZone.getTimeZone("UTC")
            tagged(0x17, format.format(value).toByteArray(Charsets.US_ASCII))
        }
    }

    private fun encodeLength(length: Int): ByteArray {
        if (length < 0x80) return byteArrayOf(length.toByte())
        var value = length
        val bytes = java.io.ByteArrayOutputStream()
        while (value > 0) {
            bytes.write(value and 0xFF)
            value = value ushr 8
        }
        val body = bytes.toByteArray().reversed().toByteArray()
        return byteArrayOf((0x80 or body.size).toByte()) + body
    }
}

/** Self-signed certificate generator (no external BouncyCastle dependency). */
internal object Asn1CertGenerator {

    fun generateSelfSignedCert(keyPair: java.security.KeyPair, cn: String, org: String): X509Certificate {
        val publicKeyBytes = keyPair.public.encoded
        val serialNumber = (System.currentTimeMillis() and 0x7FFFFFFF).toBigInteger()
        val now = System.currentTimeMillis()
        val notBefore = java.util.Date(now - 86_400_000L)
        val notAfter = java.util.Date(now + 30L * 365 * 86_400_000L)

        val sha256WithRsaOid = Asn1.tagged(
            0x06,
            byteArrayOf(0x2A, 0x86.toByte(), 0x48, 0x86.toByte(), 0xF7.toByte(), 0x0D, 0x01, 0x01, 0x0B)
        )
        val signatureAlgorithm = Asn1.sequence(sha256WithRsaOid, Asn1.nullValue())

        // Name ::= SEQUENCE OF RelativeDistinguishedName, RDN ::= SET OF AttributeTypeAndValue
        val commonNameRdn = Asn1.set(
            Asn1.sequence(
                Asn1.tagged(0x06, byteArrayOf(0x55, 0x04, 0x03)), // 2.5.4.3 commonName
                Asn1.utf8String(cn)
            )
        )
        val organizationRdn = Asn1.set(
            Asn1.sequence(
                Asn1.tagged(0x06, byteArrayOf(0x55, 0x04, 0x0A)), // 2.5.4.10 organizationName
                Asn1.utf8String(org)
            )
        )
        val name = Asn1.sequence(commonNameRdn, organizationRdn)

        val validity = Asn1.sequence(Asn1.time(notBefore), Asn1.time(notAfter))
        val version = Asn1.tagged(0xA0, Asn1.integer(2)) // v3

        val subjectPublicKeyInfo = publicKeyBytes
        val tbsCertificate = Asn1.sequence(
            version,
            Asn1.integer(serialNumber),
            signatureAlgorithm,
            name,
            validity,
            name,
            subjectPublicKeyInfo
        )

        val signer = Signature.getInstance("SHA256withRSA")
        signer.initSign(keyPair.private)
        signer.update(tbsCertificate)
        val signature = signer.sign()

        val certificateDer = Asn1.sequence(
            tbsCertificate,
            signatureAlgorithm,
            Asn1.tagged(0x03, byteArrayOf(0x00) + signature)
        )

        val factory = java.security.cert.CertificateFactory.getInstance("X.509")
        return factory.generateCertificate(certificateDer.inputStream()) as X509Certificate
    }
}
