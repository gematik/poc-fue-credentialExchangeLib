/*
 * Copyright 2021-2024, gematik GmbH
 *
 * Licensed under the EUPL, Version 1.2 or - as soon they will be approved by the
 * European Commission – subsequent versions of the EUPL (the "Licence").
 * You may not use this work except in compliance with the Licence.
 *
 * You find a copy of the Licence in the "Licence" file or at
 * https://joinup.ec.europa.eu/collection/eupl/eupl-text-eupl-12
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the Licence is distributed on an "AS IS" basis,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either expressed or implied.
 * In case of changes by gematik find details in the "Readme" file.
 *
 * See the Licence for the specific language governing permissions and limitations under the Licence.
 */

package de.gematik.security.credentialExchangeLib.protocols

import de.gematik.security.credentialExchangeLib.extensions.deepCopy
import de.gematik.security.credentialExchangeLib.extensions.toIsoInstantString
import de.gematik.security.credentialExchangeLib.extensions.toZonedDateTime
import de.gematik.security.credentialExchangeLib.serializer.UnwrappingSingleValueJsonArrays
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import okhttp3.internal.toImmutableList
import java.net.URI
import java.time.ZonedDateTime

@Serializable
class Credential : LdObject, Verifiable {

    constructor(
        id: String? = null,
        atContext: List<URI> = DEFAULT_JSONLD_CONTEXTS,
        type: List<String> = DEFAULT_JSONLD_TYPES,
        credentialSubject: JsonLdObject? = null,
        issuer: URI? = null,
        issuanceDate: ZonedDateTime? = null,
        expirationDate: ZonedDateTime? = null,
        credentialStatus: JsonLdObject? = null,
        evidence: List<JsonLdObject>? = null,
        termsOfUse: List<JsonLdObject>? = null,
        proof: List<LdProof>? = null
    ) : super(id, atContext, type) {
        this.credentialSubject = credentialSubject
        _issuer = issuer?.toString()
        _issuanceDate = issuanceDate?.toIsoInstantString()
        _expirationDate = expirationDate?.toIsoInstantString()
        this.credentialStatus = credentialStatus
        this.evidence = evidence
        this.termsOfUse = termsOfUse
        this.proof = proof
    }

    var credentialSubject: JsonLdObject?
    @SerialName("issuer") private var _issuer: String? = null
    val issuer
        get() = _issuer?.let{URI.create(it)}
    @SerialName("issuanceDate") private var _issuanceDate: String? = null
    val issuanceDate
        get() = _issuanceDate?.toZonedDateTime()
    @SerialName("expirationDate") private var _expirationDate: String? = null
    val expirationDate
        get() = _expirationDate?.toZonedDateTime()
    var credentialStatus: JsonLdObject? = null
        private set
    var evidence: @Serializable(with = UnwrappingSingleValueJsonArrays::class) List<JsonLdObject>? = null
        private set
    var termsOfUse: @Serializable(with = UnwrappingSingleValueJsonArrays::class) List<JsonLdObject>? = null
        private set
    override var proof: @Serializable(with = UnwrappingSingleValueJsonArrays::class) List<LdProof>? = null

    companion object : Defaults() {
        override val DEFAULT_JSONLD_CONTEXTS = listOf(
            URI("https://www.w3.org/2018/credentials/v1")
        )
        override val DEFAULT_JSONLD_TYPES = listOf(
            "VerifiableCredential"
        )
    }

    override fun sign(ldProof: LdProof, privateKey: ByteArray) {
        val signedProof = ldProof.deepCopy().apply { sign(this@Credential, privateKey) }
        proof = (proof ?: emptyList()).toMutableList().apply {
            add(signedProof)
            toImmutableList()
        }
    }

    suspend fun asyncSign(ldProof: LdProof, privateKey: ByteArray, context: Any) {
        val signedProof = ldProof.deepCopy().apply { asyncSign(this@Credential, privateKey, context) }
        proof = (proof ?: emptyList()).toMutableList().apply {
            add(signedProof)
            toImmutableList()
        }
    }

    override fun verify(): Boolean {
        val singleProof = proof?.firstOrNull()?.deepCopy()
        check(singleProof != null) { "credential doesn't contain a proof for verification" }
        check(proof?.size == 1) { "verification of multi signature not supported yet" }
        singleProof.atContext = if (singleProof.atContext == null) {
            atContext
        } else {
            (singleProof.atContext as MutableList<URI>).apply {
                atContext?.forEach {
                    if (!contains(it)) add(it)
                }
            }
        }
        return singleProof.verify(this)
    }

    fun derive(frame: Credential): Credential {
        val singleProof = proof?.firstOrNull()
        check(singleProof != null) { "credential doesn't contain a proof for derivation" }
        check(proof?.size == 1) { "derive credential with multiple proofs is not supported yet" }
        return singleProof.deriveProof(this, frame)
    }

}

