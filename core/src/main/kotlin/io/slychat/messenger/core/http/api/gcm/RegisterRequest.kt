package io.slychat.messenger.core.http.api.gcm

data class RegisterRequest(
    val token: String,
    val installationId: String
)