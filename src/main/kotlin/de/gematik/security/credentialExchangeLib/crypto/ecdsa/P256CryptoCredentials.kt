package de.gematik.security.credentialExchangeLib.crypto.ecdsa

import de.gematik.security.credentialExchangeLib.crypto.KeyPair
import io.github.novacrypto.base58.Base58
import org.bouncycastle.asn1.sec.SECNamedCurves
import org.bouncycastle.crypto.params.ECDomainParameters
import java.net.URI

class P256CryptoCredentials(keyPair: KeyPair) : EcdsaCryptoCredentials(keyPair) {
    companion object {
        val ecDomainParameters = SECNamedCurves.getByName("secp256r1").let { ECDomainParameters(it.curve, it.g, it.n, it.h, it.seed) }
        val multiCodecId = byteArrayOf(0x80.toByte(), 0x24) // varint of 0x1200
        fun createPublicKey(privateKey: ByteArray) : ByteArray {
            return createPublicKey(privateKey, ecDomainParameters)
        }
    }

    override val didKey: URI
    override val verificationMethod: URI

    init {
        didKey = URI.create("did:key:z${Base58.base58Encode(multiCodecId + keyPair.publicKey!!)}")
        verificationMethod = URI.create("${didKey}#${didKey.toString().drop(8)}")
    }
}
