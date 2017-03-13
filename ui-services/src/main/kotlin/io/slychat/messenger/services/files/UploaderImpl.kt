package io.slychat.messenger.services.files

import io.slychat.messenger.core.condError
import io.slychat.messenger.core.isNotNetworkError
import io.slychat.messenger.core.persistence.*
import io.slychat.messenger.services.mapUi
import nl.komponents.kovenant.Promise
import nl.komponents.kovenant.functional.bind
import nl.komponents.kovenant.ui.failUi
import nl.komponents.kovenant.ui.successUi
import org.slf4j.LoggerFactory
import rx.Observable
import rx.Scheduler
import rx.Subscriber
import rx.subjects.PublishSubject
import java.io.FileNotFoundException
import java.util.concurrent.TimeUnit

class UploaderImpl(
    initialSimulUploads: Int,
    private val uploadPersistenceManager: UploadPersistenceManager,
    private val uploadOperations: UploadOperations,
    private val timerScheduler: Scheduler,
    private val mainScheduler: Scheduler,
    initialNetworkStatus: Boolean
) : Uploader {
    companion object {
        internal const val PROGRESS_TIME_MS = 1000L
    }

    private val log = LoggerFactory.getLogger(javaClass)

    private val list = TransferList<UploadStatus>(initialSimulUploads)

    override var simulUploads: Int
        get() = list.maxSize
        set(value) {
            list.maxSize = value
        }

    override var isNetworkAvailable = initialNetworkStatus
        set(value) {
            field = value

            if (value)
                startNextUpload()
        }

    override val uploads: List<UploadStatus>
        get() = list.all.values.toList()

    private val subject = PublishSubject.create<TransferEvent>()

    override val events: Observable<TransferEvent>
        get() = subject

    override fun init() {
        uploadPersistenceManager.getAll() successUi {
            addUploads(it)
        } failUi {
            log.error("Failed to fetch initial uploads: {}", it.message, it)
        }
    }

    override fun shutdown() {
    }

    private fun addUploads(info: Iterable<UploadInfo>) {
        info.forEach {
            val upload = it.upload
            val file = it.file
            if (upload.id in list.all)
                error("Upload ${upload.id} already in transfer list")

            val initialState = if (upload.error == null) {
                if (upload.state == UploadState.COMPLETE)
                    TransferState.COMPLETE
                else
                    TransferState.QUEUED
            }
            else
                TransferState.ERROR

            list.all[upload.id] = UploadStatus(
                upload,
                file,
                initialState,
                upload.parts.map { UploadPartTransferProgress(if (it.isComplete) it.remoteSize else 0, it.remoteSize) }
            )

            if (initialState == TransferState.QUEUED)
                list.queued.add(upload.id)

            subject.onNext(TransferEvent.UploadAdded(upload, initialState))
        }

        startNextUpload()
    }

    override fun upload(info: UploadInfo): Promise<Unit, Exception> {
        return uploadPersistenceManager.add(info) mapUi {
            addUploads(listOf(info))
        }
    }

    private fun startNextUpload() {
        if (!isNetworkAvailable)
            return

        if (!list.canActivateMore) {
            log.info("{}/{} uploads running, not starting more", list.active.size, list.maxSize)
            return
        }

        while (list.canActivateMore) {
            val next = list.nextQueued()

            val nextId = next.upload.id
            list.active.add(nextId)

            val newState = TransferState.ACTIVE
            list.setStatus(nextId, next.copy(state = newState))

            subject.onNext(TransferEvent.UploadStateChanged(next.upload, newState))

            nextStep(nextId)
        }
    }

    private fun nextStep(uploadId: String) {
        val status = list.getStatus(uploadId)

        when (status.upload.state) {
            UploadState.PENDING -> createUpload(status)
            //for now we just upload the next available part sequentially
            UploadState.CREATED -> uploadNextPart(status)
            //this shouldn't get here, but it's here for completion
            UploadState.COMPLETE -> log.warn("nextStep called with state=COMPLETE state")
        }
    }

    private fun markUploadComplete(status: UploadStatus) {
        log.info("Marking upload {} as complete", status.upload.id)

        val uploadId = status.upload.id
        list.active.remove(uploadId)
        list.inactive.add(uploadId)

        updateTransferState(uploadId, TransferState.COMPLETE)
    }

    private fun updateUploadState(uploadId: String, newState: UploadState): Promise<Unit, Exception> {
        return uploadPersistenceManager.setState(uploadId, newState) mapUi {
            updateCachedUploadState(uploadId, newState)
        } failUi {
            log.error("Failed to update upload {} state to {}: {}", uploadId, newState, it.message, it)
            moveUploadToErrorState(uploadId, UploadError.UNKNOWN)
        }
    }

    private fun updateCachedUploadState(uploadId: String, newState: UploadState) {
        list.updateStatus(uploadId) {
            it.copy(
                it.upload.copy(state = newState)
            )
        }
    }

    private fun updateTransferState(uploadId: String, newState: TransferState) {
        val status = list.updateStatus(uploadId) {
            it.copy(state = newState)
        }

        subject.onNext(TransferEvent.UploadStateChanged(status.upload, newState))
    }

    private fun moveUploadToErrorState(uploadId: String, uploadError: UploadError) {
        log.info("Moving upload {} to error state", uploadId)

        list.updateStatus(uploadId) {
            it.copy(
                upload = it.upload.copy(error = uploadError)
            )
        }

        list.active.remove(uploadId)
        list.queued.remove(uploadId)
        list.inactive.add(uploadId)

        updateTransferState(uploadId, TransferState.ERROR)
    }

    private fun receivePartProgress(uploadId: String, partN: Int, transferedBytes: Long) {
        val status = list.updateStatus(uploadId) {
            val progress = it.progress.mapIndexed { i, uploadPartTransferProgress ->
                if (i == partN - 1)
                    uploadPartTransferProgress.add(transferedBytes)
                else
                    uploadPartTransferProgress
            }

            it.copy(progress = progress)
        }

        val progress = UploadTransferProgress(status.progress, status.transferedBytes, status.totalBytes)
        subject.onNext(TransferEvent.UploadProgress(status.upload, progress))
    }

    //TODO cancellation error
    private fun handleUploadException(uploadId: String, e: Throwable, origin: String) {
        val uploadError = when (e) {
            is FileNotFoundException -> UploadError.FILE_DISAPPEARED

            is InsufficientQuotaException -> UploadError.INSUFFICIENT_QUOTA

            is UploadCorruptedException -> UploadError.CORRUPTED

            else -> {
                if (isNotNetworkError(e))
                    UploadError.UNKNOWN
                else
                    UploadError.NETWORK_ISSUE
            }
        }

        log.condError(uploadError == UploadError.UNKNOWN, "{} failed: {}", origin, e.message, e)

        uploadPersistenceManager.setError(uploadId, uploadError) successUi {
            moveUploadToErrorState(uploadId, uploadError)
        } fail {
            log.error("Failed to set upload error for {}: {}", uploadId, it.message, it)
        }
    }

    private fun uploadNextPart(status: UploadStatus) {
        val nextPart = status.upload.parts.find { !it.isComplete }
        val uploadId = status.upload.id
        if (nextPart == null) {
            updateUploadState(uploadId, UploadState.COMPLETE) mapUi {
                markUploadComplete(status)
            }
            return
        }

        uploadOperations.uploadPart(status.upload, nextPart, status.file)
            .buffer(PROGRESS_TIME_MS, TimeUnit.MILLISECONDS, timerScheduler)
            .map { it.sum() }
            .observeOn(mainScheduler)
            .subscribe(object : Subscriber<Long>() {
                override fun onError(e: Throwable) {
                    handleUploadException(uploadId, e, "uploadPart")
                }

                override fun onNext(t: Long) {
                    receivePartProgress(uploadId, nextPart.n, t)
                }

                override fun onCompleted() {
                    log.info("Upload $uploadId/${nextPart.n} completed")

                    uploadPersistenceManager.completePart(uploadId, nextPart.n) successUi {
                        completePart(uploadId, nextPart.n)
                        nextStep(uploadId)
                    } fail {
                        log.error("Failed to mark part as complete: {}", it.message, it)
                    }
                }
            })
    }

    //XXX for progress, check if part's marked as complete, and drop it
    //since we schedule stuff to be run, it'll occur that we complete a part before the final progress update comes in

    private fun completePart(uploadId: String, n: Int) {
        val status = list.getStatus(uploadId)

        val newProgress = status.progress.mapIndexed { i, progress ->
            if (i == (n - 1))
                progress.copy(transferedBytes = progress.totalBytes)
            else
                progress
        }

        list.setStatus(uploadId, status.copy(
            upload = status.upload.markPartCompleted(n),
            progress = newProgress
        ))

        val transferProgress = UploadTransferProgress(newProgress, status.transferedBytes, status.totalBytes)
        subject.onNext(TransferEvent.UploadProgress(status.upload, transferProgress))
    }

    private fun createUpload(status: UploadStatus) {
        val uploadId = status.upload.id

        uploadOperations.create(status.upload, status.file) bind {
            updateUploadState(uploadId, UploadState.CREATED)
        } successUi {
            nextStep(uploadId)
        } failUi {
            handleUploadException(uploadId, it, "create")
        }
    }

    override fun clearError(uploadId: String): Promise<Unit, Exception> {
        return uploadPersistenceManager.setError(uploadId, null) successUi {
            list.inactive.remove(uploadId)
            list.queued.add(uploadId)

            val status = list.updateStatus(uploadId) {
                it.copy(
                    upload = it.upload.copy(error = null),
                    state = TransferState.QUEUED
                )
            }

            subject.onNext(TransferEvent.UploadStateChanged(status.upload, status.state))

            startNextUpload()
        }
    }

    override fun remove(uploadIds: List<String>): Promise<Unit, Exception> {
        val statuses = uploadIds.map { uploadId ->
            list.all[uploadId] ?: throw InvalidUploadException(uploadId)
        }

        val ids = statuses.map { status ->
            val id = status.upload.id
            if (id !in list.inactive)
                throw IllegalStateException("Upload $id is currently active, can't remove")

            list.queued.remove(id)
            list.inactive.remove(id)

            id
        }

        return uploadPersistenceManager.remove(ids) successUi {
            ids.forEach { list.all.remove(it) }

            subject.onNext(TransferEvent.UploadRemoved(statuses.map { it.upload }))
        }
    }
}