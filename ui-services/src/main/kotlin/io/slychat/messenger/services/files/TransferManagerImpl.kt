package io.slychat.messenger.services.files

import io.slychat.messenger.core.persistence.UploadInfo
import io.slychat.messenger.core.persistence.UploadPersistenceManager
import io.slychat.messenger.core.persistence.UploadState
import io.slychat.messenger.services.mapUi
import nl.komponents.kovenant.Promise
import nl.komponents.kovenant.functional.bind
import nl.komponents.kovenant.ui.failUi
import nl.komponents.kovenant.ui.successUi
import org.slf4j.LoggerFactory
import rx.Observable
import rx.Subscription
import rx.subjects.PublishSubject
import java.util.*

class TransferManagerImpl(
    override var options: TransferOptions,
    private val uploadPersistenceManager: UploadPersistenceManager,
    private val transferOperations: TransferOperations,
    networkStatus: Observable<Boolean>
) : TransferManager {
    private val log = LoggerFactory.getLogger(javaClass)

    //upload ids
    private val queued = ArrayDeque<String>()
    private val active = ArrayList<String>()
    //completed, errored uploads
    private val inactive = ArrayList<String>()

    //uploadId->upload
    private val all = HashMap<String, UploadStatus>()

    override val uploads: List<UploadStatus>
        get() = all.values.toList()

    private val subject = PublishSubject.create<TransferEvent>()

    override val events: Observable<TransferEvent>
        get() = subject

    private var subscription: Subscription? = null

    private var isNetworkAvailable = false

    init {
        subscription = networkStatus.subscribe { onNetworkStatusChange(it) }
    }

    private fun onNetworkStatusChange(isAvailable: Boolean) {
        isNetworkAvailable = isAvailable

        if (isAvailable)
            startNextUpload()
    }

    override fun init() {
        uploadPersistenceManager.getAll() successUi {
            addUploads(it)
        } failUi {
            log.error("Failed to fetch initial uploads: {}", it.message, it)
        }
    }

    override fun shutdown() {
        subscription?.unsubscribe()
        subscription = null
    }

    private fun addUploads(info: Iterable<UploadInfo>) {
        info.forEach {
            val upload = it.upload
            val file = it.file
            if (upload.id in all)
                error("Upload ${upload.id} already in transfer list")

            val initialState = if (upload.error == null) {
                if (upload.state == UploadState.COMPLETE)
                    UploadTransferState.COMPLETE
                else
                    UploadTransferState.QUEUED
            }
            else
                UploadTransferState.ERROR

            all[upload.id] = UploadStatus(
                upload,
                file,
                initialState,
                upload.parts.map { UploadPartTransferProgress(0, it.size) }
            )

            if (initialState == UploadTransferState.QUEUED)
                queued.add(upload.id)

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

        if (active.size >= options.simulUploads) {
            log.info("{}/{} uploads running, not starting more", active.size, options.simulUploads)
            return
        }

        while (queued.isNotEmpty()) {
            val nextId = queued.pop()

            val status = all[nextId] ?: error("Queued upload $nextId not in upload list")

            active.add(nextId)

            val newState = UploadTransferState.ACTIVE
            all[nextId] = status.copy(state = newState)

            subject.onNext(TransferEvent.UploadStateChanged(status.upload, newState))

            nextStep(status.upload.id)
        }
    }

    private fun nextStep(uploadId: String) {
        val status = all[uploadId] ?: error("nextStep called for invalid upload id: $uploadId")

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
        active.remove(uploadId)
        inactive.add(uploadId)

        updateTransferState(uploadId, UploadTransferState.COMPLETE)
    }

    private fun updateUploadState(uploadId: String, newState: UploadState): Promise<Unit, Exception> {
        return uploadPersistenceManager.setState(uploadId, newState) mapUi {
            updateCachedUploadState(uploadId, newState)
        } failUi {
            log.error("Failed to update upload {} state to {}: {}", uploadId, newState, it.message, it)
            moveUploadToErrorState(uploadId)
        }
    }

    private fun updateCachedUploadState(uploadId: String, newState: UploadState) {
        val status = all[uploadId] ?: error("Requested to update cached upload state for invalid id $uploadId")

        all[uploadId] = status.copy(
            status.upload.copy(state = newState)
        )
    }

    private fun updateTransferState(uploadId : String, newState: UploadTransferState) {
        val status = all[uploadId] ?: error("Requested up to update transfer state for upload $uploadId but no such upload")

        all[uploadId] = status.copy(state = newState)

        subject.onNext(TransferEvent.UploadStateChanged(status.upload, newState))
    }

    private fun moveUploadToErrorState(uploadId: String) {
        log.info("Moving upload {} to error state", uploadId)

        active.remove(uploadId)
        queued.remove(uploadId)
        inactive.add(uploadId)

        updateTransferState(uploadId, UploadTransferState.ERROR)
    }

    //XXX this is called on a diff thread
    //just keep track of last time we emitted data, and emit every second
    //the last time it occurs doesn't matter (since we end up completing)
    //XXX for testing this this is kinda difficult though... although I guess just override the current time works?
    //not sure using an observable really makes things any easier anyways
    private fun receivePartProgress(uploadId: String, partN: Int, transferedBytes: Long) {

    }

    private fun uploadNextPart(status: UploadStatus) {
        //TODO error
        val nextPart = status.upload.parts.find { !it.isComplete }
        val uploadId = status.upload.id
        if (nextPart == null) {
            updateUploadState(uploadId, UploadState.COMPLETE) mapUi {
                markUploadComplete(status)
            }
            return
        }

        //(for later)
        //it could occur that this is called after status.upload is modified (eg: another part completes) if transfering
        //multiple parts; however we don't actually use .parts since we pass in the part explicitly so this isn't an issue
        //we should probably mapUi, because if something caused the upload to fail we don't wanna do anything
        val p = transferOperations.uploadPart(status.upload, nextPart, status.file) {
            receivePartProgress(status.upload.id, nextPart.n, it)
        }

        p.bind {
            uploadPersistenceManager.completePart(uploadId, nextPart.n)
        }.successUi {
            completePart(uploadId, nextPart.n)
            nextStep(uploadId)
        }
    }

    //XXX for progress, check if part's marked as complete, and drop it
    //since we schedule stuff to be run, it'll occur that we complete a part before the final progress update comes in

    private fun completePart(uploadId: String, n: Int) {
        val status = all[uploadId] ?: error("completePart called with invalid upload id: $uploadId")

        val newProgress = status.progress.mapIndexed { i, progress ->
            if (i == (n - 1))
                progress.copy(transferedBytes = progress.totalBytes)
            else
                progress
        }

        all[uploadId] = status.copy(
            upload = status.upload.markPartCompleted(n),
            progress = newProgress
        )

        val transferProgress = UploadTransferProgress(newProgress, status.transferedBytes, status.totalBytes)
        subject.onNext(TransferEvent.UploadProgress(status.upload, transferProgress))
    }

    private fun createUpload(status: UploadStatus) {
        //TODO error
        val p = transferOperations.create(status.upload, status.file)

        val uploadId = status.upload.id

        p bind {
            updateUploadState(uploadId, UploadState.CREATED)
        } successUi {
            nextStep(uploadId)
        }
    }
}