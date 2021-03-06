package io.slychat.messenger.core.http.api.contacts

import io.slychat.messenger.core.UserCredentials
import io.slychat.messenger.core.http.HttpClient
import io.slychat.messenger.core.http.api.apiGetRequest
import io.slychat.messenger.core.http.api.apiPostRequest
import io.slychat.messenger.core.typeRef

class AddressBookClient(private val serverBaseUrl: String, private val httpClient: HttpClient) {
    fun get(userCredentials: UserCredentials, request: GetAddressBookRequest): GetAddressBookResponse {
        val url = "$serverBaseUrl/v1/address-book?hash=${request.hash}"

        return apiGetRequest(httpClient, url, userCredentials, listOf(), typeRef())
    }

    /**
     * @throws ResourceConflictException If the update could not be performed due to another client performing an update.
     */
    fun update(userCredentials: UserCredentials, request: UpdateAddressBookRequest): UpdateAddressBookResponse {
        val url = "$serverBaseUrl/v1/address-book"

        return apiPostRequest(httpClient, url, userCredentials, request, typeRef())
    }
}

