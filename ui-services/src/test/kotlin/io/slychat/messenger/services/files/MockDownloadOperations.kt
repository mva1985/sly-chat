package io.slychat.messenger.services.files

import io.slychat.messenger.core.files.RemoteFile
import io.slychat.messenger.core.persistence.Download
import rx.Observable
import rx.schedulers.TestScheduler
import rx.subjects.PublishSubject
import java.util.*
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.test.assertTrue
import kotlin.test.fail

//test scheduler is require, since operators like buffer() delay running completion/error
class MockDownloadOperations(private val scheduler: TestScheduler) : DownloadOperations {
    private data class DownloadArgs(val download: Download, val file: RemoteFile)

    private val downloadObservables = HashMap<String, PublishSubject<Long>>()
    private val cancellationTokens = HashMap<String, AtomicBoolean>()

    private var downloadArgs: DownloadArgs? = null

    override fun download(download: Download, file: RemoteFile, isCancelled: AtomicBoolean): Observable<Long> {
        downloadArgs = DownloadArgs(download, file)

        val s = PublishSubject.create<Long>()
        downloadObservables[download.id] = s
        cancellationTokens[download.id] = isCancelled
        return s
    }

    fun assertDownloadNotCalled() {
        if (downloadArgs != null)
            fail("download() called with $downloadArgs")
    }

    fun assertDownloadCalled(download: Download, file: RemoteFile) {
        val args = downloadArgs ?: fail("download() not called")

        val expected = DownloadArgs(download, file)
        if (args != expected)
            fail("download() called with differing args\nExpected:\n$expected\n\nActual:\n$args")
    }

    fun getDownloadSubject(downloadId: String): PublishSubject<Long> {
        return downloadObservables[downloadId] ?: fail("download() not called for $downloadId")
    }

    fun completeDownload(downloadId: String) {
        val s = getDownloadSubject(downloadId)
        s.onCompleted()
        scheduler.triggerActions()
    }

    fun errorDownload(downloadId: String, e: Exception) {
        val s = getDownloadSubject(downloadId)
        s.onError(e)
        scheduler.triggerActions()
    }

    fun sendDownloadProgress(downloadId: String, transferedBytes: Long) {
        val s = getDownloadSubject(downloadId)
        s.onNext(transferedBytes)
        scheduler.triggerActions()
    }

    fun assertCancelled(downloadId: String) {
        val token = cancellationTokens[downloadId] ?: fail("download($downloadId) was not called")

        assertTrue(token.get(), "Cancellation not requested")
    }
}