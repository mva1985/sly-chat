package io.slychat.messenger.services.files

import io.slychat.messenger.core.files.RemoteFile
import io.slychat.messenger.core.persistence.Download
import rx.Observable
import java.util.concurrent.atomic.AtomicBoolean

interface DownloadOperations {
    fun download(download: Download, file: RemoteFile, isCancelled: AtomicBoolean): Observable<Long>
}