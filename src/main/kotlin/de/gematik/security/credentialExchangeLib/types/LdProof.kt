package de.gematik.security.credentialExchangeLib.types

import bbs.signatures.ProofMessage
import com.apicatalog.jsonld.JsonLd
import com.apicatalog.jsonld.document.JsonDocument
import com.apicatalog.jsonld.document.RdfDocument
import de.gematik.security.credentialExchangeLib.crypto.*
import de.gematik.security.credentialExchangeLib.defaultJsonLdOptions
import de.gematik.security.credentialExchangeLib.extensions.deepCopy
import de.gematik.security.credentialExchangeLib.extensions.normalize
import de.gematik.security.credentialExchangeLib.extensions.toBls12381G2PublicKey
import de.gematik.security.credentialExchangeLib.extensions.toJsonDocument
import de.gematik.security.credentialExchangeLib.json
import de.gematik.security.credentialExchangeLib.serializer.DateSerializer
import de.gematik.security.credentialExchangeLib.serializer.URISerializer
import de.gematik.security.credentialExchangeLib.serializer.UnwrappingSingleValueJsonArrays
import kotlinx.serialization.Required
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import java.net.URI
import java.util.*
import kotlin.random.Random

@Serializable
class LdProof(
    override val id: String? = null,
    @Required @SerialName("@context") override val atContext: @Serializable(with = UnwrappingSingleValueJsonArrays::class) List<@Serializable(
        with = URISerializer::class
    ) URI> = DEFAULT_JSONLD_CONTEXTS,
    @Required override var type: @Serializable(with = UnwrappingSingleValueJsonArrays::class) List<String>? = DEFAULT_JSONLD_TYPES,
    val creator: @Serializable(with = URISerializer::class) URI? = null,
    val created: @Serializable(with = DateSerializer::class) Date,
    val domain: String? = null,
    val challenge: String? = null,
    var nonce: String? = null,
    val proofPurpose: ProofPurpose,
    val verificationMethod: @Serializable(with = URISerializer::class) URI,
    var proofValue: String? = null
) : LdObject {

    companion object : LdObject.Defaults() {
        override val DEFAULT_JSONLD_CONTEXTS = listOf(
            URI("https://w3id.org/security/bbs/v1")
        )
        override val DEFAULT_JSONLD_TYPES = listOf<String>()
    }

    inline fun <reified T> sign(jsonLdObject: T, signer: Signer) where T : LdObject, T : Verifiable {
        check(proofValue == null) { "proof already contains proof value" }
        check(jsonLdObject.proof == null) { "jsonLdObject already signed" }
        val statements = listOf(
            normalize().trim().split('\n'),
            jsonLdObject.normalize<T>().trim().split('\n')
        ).flatMap { it.map { it.toByteArray() } }
        proofValue = String(Base64.getEncoder().encode(signer.sign(statements)))
    }

    inline fun <reified T> verify(
        jsonLdObject: T
    ): Boolean where T : LdObject, T : Verifiable {
        val ldProofWithoutProofValue = deepCopy().apply { proofValue = null }
        val jsonLdObjectWithoutProof = jsonLdObject.deepCopy<T>().apply { proof = null }
        val statements = listOf(
            ldProofWithoutProofValue.normalize().trim().split('\n'),
            jsonLdObjectWithoutProof.normalize<T>().trim().split('\n')
        ).flatMap { it.map { it.toByteArray() } }
        val verifier = type?.let{getVerifier(it, verificationMethod.toBls12381G2PublicKey())}
        return verifier?.verify(statements, Base64.getDecoder().decode(proofValue))?:false
    }

    fun deriveProof(credential: Credential, frame: Credential): Credential {

        // 0. check if signature is suitable for deriving proof
        check(
            type?.firstOrNull { it.endsWith(ProofType.BbsBlsSignature2020.name) } != null
        ) { "proof type doesn't support proof derivation" }

        // 1 frame input document
        val credentialWithoutProof = credential.deepCopy().apply { proof = null }
        // 1.1 normalize input document to rdf string
        val normalizedCredential = credentialWithoutProof.normalize().trim()
        // 1.2 convert internal blank node identifiers to uniform black node identifiers (urn:bnid)
        val normalizeTransformedCredential = normalizedCredential.replace(Regex("_:c14n[0-9]*"), "<urn:bnid:$0>")
        // 1.3 create expanded input document from rdf string
        val expandedInputDocument = normalizeTransformedCredential.byteInputStream().use {
            JsonDocument.of(
                JsonLd.fromRdf(
                    RdfDocument.of(it)
                ).get()
            )
        }
        // 1.4 frame
        val framedCredential = json.decodeFromString<Credential>(
            JsonLd.frame(expandedInputDocument, frame.toJsonDocument()).options(defaultJsonLdOptions).get().toString()
        )
        // 1.5 revert black node identifier to internal blank node identifiers
        val normalizedFramedCredential =
            framedCredential.normalize().trim().replace(Regex("<urn:bnid:(_:c14n[0-9]*)>"), "$1")
        // 2 prepare list of proof messages
        // 2.1 init list of ProofMessage with messages from LdProof without proof value
        val newLdProof = deepCopy().apply { proofValue = null }
        val proofMessages = newLdProof.normalize().trim().split('\n').map {
            ProofMessage(ProofMessage.PROOF_MESSAGE_TYPE_REVEALED, it.toByteArray(), null)
        }.toMutableList()
        // 2.2 add proof messages from credential
        val framedCredentialMessages = normalizedFramedCredential.split('\n')
        var j = 0
        normalizedCredential.split('\n').forEach {
            val type = if (it == framedCredentialMessages.get(j)) {
                j++
                ProofMessage.PROOF_MESSAGE_TYPE_REVEALED
            } else {
                ProofMessage.PROOF_MESSAGE_TYPE_HIDDEN_PROOF_SPECIFIC_BLINDING
            }
            proofMessages.add(ProofMessage(type, it.toByteArray(), null))
        }
        // 3 calculate signature
        val signer = type?.let{getSigner(it, KeyPair(publicKey = verificationMethod.toBls12381G2PublicKey()))}
        val nonce = Random.nextBytes(32)
        val signature = signer?.deriveProof(Base64.getDecoder().decode(proofValue), nonce, proofMessages)
        // 4 complete new proof, add to new credential and return
        return framedCredential.apply {
            proof = listOf(newLdProof.apply {
                type = listOf(ProofType.BbsBlsSignatureProof2020.name)
                proofValue = Base64.getEncoder().encodeToString(signature)
                this.nonce = Base64.getEncoder().encodeToString(nonce)
            })
        }
    }

    fun verifyProof(credential: Credential) : Boolean {
        check(type?.size == 1 && type?.get(0) == ProofType.BbsBlsSignatureProof2020.name){"invalid proof type"}
        // 1 prepare original proof
        val ldProofWithoutProofValue = deepCopy().apply {
            // 1.1 remove proof value
            proofValue = null
            //1.2 remove nonce
            nonce = null
            //1.3 set original proof type
            type = listOf(ProofType.BbsBlsSignature2020.name)
        }
        // 2 prepare credential
        val credentialWithoutProof = credential.deepCopy().apply { proof = null }
        // 3 normalize credential and ldproof and revert uri blank node identifier (urn:bnid) back into internal blank node identifiers
        val statements = listOf(
            ldProofWithoutProofValue.normalize().trim().replace(Regex("<urn:bnid:(_:c14n[0-9]*)>"), "$1").split('\n'),
            credentialWithoutProof.normalize().trim().replace(Regex("<urn:bnid:(_:c14n[0-9]*)>"), "$1").split('\n')
        ).flatMap { it.map { it.toByteArray() } }
        // 4 get verifier and verify revealed messages
        val verifier = type?.let{getVerifier(it, verificationMethod.toBls12381G2PublicKey())}
        return verifier?.verifyProof(
            statements,
            Base64.getDecoder().decode(proofValue),
            Base64.getDecoder().decode(nonce)
        )?:false
    }

}