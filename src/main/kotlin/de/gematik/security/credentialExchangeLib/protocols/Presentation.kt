package de.gematik.security.credentialExchangeLib.protocols

import de.gematik.security.credentialExchangeLib.serializer.URISerializer
import de.gematik.security.credentialExchangeLib.serializer.UnwrappingSingleValueJsonArrays
import kotlinx.serialization.Required
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import java.net.URI

@Serializable
class Presentation(
    override val id: String? = null,
    @Required @SerialName("@context") override var atContext: @Serializable(with = UnwrappingSingleValueJsonArrays::class) List<@Serializable(with = URISerializer::class) URI> = DEFAULT_JSONLD_CONTEXTS,
    @Required override var type: @Serializable(with = UnwrappingSingleValueJsonArrays::class) List<String> = DEFAULT_JSONLD_TYPES,
    val presentationSubmission: PresentationSubmission,
    val verifiableCredential: List<Credential>,
    override var proof: @Serializable(with = UnwrappingSingleValueJsonArrays::class) List<LdProof>? = null
) : LdObject, Verifiable {

    companion object : LdObject.Defaults() {
        override val DEFAULT_JSONLD_CONTEXTS = listOf(
            URI("https://www.w3.org/2018/credentials/v1")
        )
        override val DEFAULT_JSONLD_TYPES = listOf(
            "VerifiablePresentation"
        )
    }

    override fun sign(ldProof: LdProof, privateKey: ByteArray){
        ldProof.sign(this, privateKey)
        proof = listOf(ldProof)
    }

    suspend fun asyncSign(ldProof: LdProof, privateKey: ByteArray, context : Any){
        ldProof.asyncSign(this, privateKey, context)
        proof = listOf(ldProof)
    }

    override fun verify() : Boolean {
        val singleProof = proof?.firstOrNull()
        check(singleProof!=null){"presentation doesn't contain a proof for verification"}
        check(proof?.size == 1){"verfication of multi signature not supported yet"}
        return singleProof.verify(this)
    }

}

