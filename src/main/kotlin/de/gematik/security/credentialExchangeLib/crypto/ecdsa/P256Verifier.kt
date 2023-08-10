package de.gematik.security.credentialExchangeLib.crypto.ecdsa

import org.bouncycastle.crypto.digests.SHA256Digest
import org.bouncycastle.crypto.params.ECPublicKeyParameters
import org.bouncycastle.crypto.signers.ECDSASigner
import org.bouncycastle.crypto.signers.HMacDSAKCalculator

class P256Verifier(publicKey: ByteArray) : EcdsaVerifier(publicKey) {
    override val verifier = ECDSASigner(HMacDSAKCalculator(SHA256Digest())).apply {
        init(false, ECPublicKeyParameters(
            P256CryptoCredentials.ecDomainParameters.curve.decodePoint(publicKey),
            P256CryptoCredentials.ecDomainParameters))
    }
}