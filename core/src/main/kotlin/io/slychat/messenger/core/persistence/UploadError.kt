package io.slychat.messenger.core.persistence

//isTransient is true if the upload can be retried without user intervention
enum class UploadError(override val isTransient: Boolean) : TransferError {
    INSUFFICIENT_QUOTA(false),
    //filePath is invalid
    FILE_DISAPPEARED(false),
    //XXX this is only for single part uploads; we need to delete the old file and create a new upload in this case
    CORRUPTED(false),
    //file path is already taken
    DUPLICATE_FILE(false),
    //things like disconnection, etc
    NETWORK_ISSUE(true),
    //503 from server
    SERVICE_UNAVAILABLE(true),
    //too many uploads for this user
    MAX_UPLOADS_EXCEEDED(false),
    //not really sure whether to mark this transient or not
    UNKNOWN(true)
}