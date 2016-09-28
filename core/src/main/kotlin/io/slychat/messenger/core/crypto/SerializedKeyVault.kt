package io.slychat.messenger.core.crypto

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.annotation.JsonProperty

@JsonIgnoreProperties(ignoreUnknown = true)
data class SerializedKeyVault(
    @JsonProperty("encryptedKeyPair") val encryptedKeyPair: String,
    @JsonProperty("keyPasswordHashParams") val keyPasswordHashParams: SerializedCryptoParams,
    @JsonProperty("keyPairCipherParams") val keyPairCipherParams: SerializedCryptoParams,
    @JsonProperty("privateKeyHashParams") val privateKeyHashParams: SerializedCryptoParams,
    @JsonProperty("localDataEncryptionParams") val localDataEncryptionParams: SerializedCryptoParams,
    @JsonProperty("remotePasswordHashParams") val remotePasswordHashParams: SerializedCryptoParams
)