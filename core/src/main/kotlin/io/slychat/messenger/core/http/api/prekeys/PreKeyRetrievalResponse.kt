package io.slychat.messenger.core.http.api.prekeys

import com.fasterxml.jackson.annotation.JsonIgnore
import com.fasterxml.jackson.annotation.JsonProperty

/** Lack of keyData indicates a non-registered user. */
data class PreKeyRetrievalResponse(
    @JsonProperty("errorMessage")
    val errorMessage: String?,

    //empty bundle: no active devices
    //else, a hash of deviceId -> bundle; if bundle is null, requested device has no prekeys
    //any asked devices that don't exist are ignored and not returned in the bundle
    @JsonProperty("bundles")
    val bundles: Map<Int, SerializedPreKeySet?>
) {
    @get:JsonIgnore
    val isSuccess: Boolean = errorMessage == null
}