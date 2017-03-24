package io.slychat.messenger.core.persistence

import nl.komponents.kovenant.Promise

interface DownloadPersistenceManager {
    fun add(download: Download): Promise<Unit, Exception>

    //remove a download from the list
    fun remove(downloadIds: List<String>): Promise<Unit, Exception>

    //clears error
    fun setState(downloadId: String, state: DownloadState): Promise<Unit, Exception>

    fun setError(downloadId: String, error: DownloadError?): Promise<Unit, Exception>

    fun getAll(): Promise<List<DownloadInfo>, Exception>

    fun get(downloadId : String): Promise<Download?, Exception>
}