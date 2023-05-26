package de.gematik.security.credentialExchangeLib.crypto

import bbs.signatures.Bbs
import bbs.signatures.ProofMessage
import java.security.GeneralSecurityException

class BbsPlusSigner(override val keyPair: KeyPair) : Signer {
    init {
        keyPair.privateKey?.let {
            require(it.size == Bbs.getSecretKeySize()) {
                "wrong private keysize - expected ${Bbs.getSecretKeySize()} was ${it.size}"
            }
        }
        keyPair.publicKey?.let {
            require(it.size == Bbs.getBls12381G2PublicKeySize()) {
                "wrong public keysize - expected ${Bbs.getBls12381G2PublicKeySize()} was ${it.size}"
            }
        }
    }

    override fun sign(content: List<ByteArray>): ByteArray {
        return runCatching {
            check(keyPair.privateKey != null && keyPair.publicKey!=null){"private and public key required to sign content"}
            Bbs.blsSign(keyPair.privateKey, keyPair.publicKey, content.toTypedArray());
        }.onFailure { throw GeneralSecurityException(it.message) }.getOrThrow()
    }

    fun deriveProof(signature: ByteArray, nonce: ByteArray, content: List<ProofMessage>): ByteArray {
        return runCatching {
            check(keyPair.publicKey!=null){"public key required to derive proof"}
            Bbs.blsCreateProof(keyPair.publicKey, nonce, signature, content.toTypedArray());
        }.onFailure { throw GeneralSecurityException(it.message) }.getOrThrow()
    }
}