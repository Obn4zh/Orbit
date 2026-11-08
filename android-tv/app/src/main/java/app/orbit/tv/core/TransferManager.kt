package app.orbit.tv.core

import android.net.Uri
import io.ktor.utils.io.ByteReadChannel
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

object Protocol {
    const val VERSION = 1
    const val DEFAULT_PORT = 47717
    const val KEY_HEADER = "X-Orbit-Key"
    const val SERVICE_TYPE = "_orbit._tcp"
    const val MAX_FILES = 2000
    const val APPROVAL_TIMEOUT_MS = 90_000L
    /** Shortcuts uploads from one address join an approved transfer while they keep coming (60 s since last byte). */
    const val AD_HOC_WINDOW_MS = 60_000L
}

object ErrorCodes {
    const val UNAUTHORIZED = "unauthorized"
    const val NOT_LOCAL = "not_local"
    const val RECEIVING_DISABLED = "receiving_disabled"
    const val DECLINED = "declined"
    const val EXPIRED = "expired"
    const val NOT_FOUND = "not_found"
    const val OFFSET_MISMATCH = "offset_mismatch"
    const val INSUFFICIENT_STORAGE = "insufficient_storage"
    const val CANCELED = "canceled"
    const val INVALID = "invalid_request"
    const val RATE_LIMITED = "rate_limited"
    const val WRITE_FAILED = "write_failed"
    const val UNTRUSTED_NETWORK = "untrusted_network"
}

class TransferError(val status: Int, val code: String, message: String) : Exception(message)

enum class SessionStatus { PENDING, ACCEPTED, ACTIVE, COMPLETED, DECLINED, EXPIRED, CANCELED, FAILED;
    val finished get() = this == COMPLETED || this == DECLINED || this == EXPIRED || this == CANCELED || this == FAILED
}

enum class FileStatus { QUEUED, RECEIVING, DONE, FAILED, CANCELED }

class TransferFile(val index: Int, val originalName: String, val safeName: String, @Volatile var size: Long, val type: String?) {
    val received = AtomicLong(0)
    @Volatile var status = FileStatus.QUEUED
    @Volatile var target: StorageTarget? = null
    @Volatile var savedName: String? = null
    val lock = Mutex()
}

class TransferSession(val id: String, val sender: String, val remote: String, val adHoc: Boolean) {
    val createdAt = System.currentTimeMillis()
    @Volatile var lastActivity = createdAt
    @Volatile var finishedAt = 0L
    @Volatile var error: String? = null
    val status = MutableStateFlow(SessionStatus.PENDING)
    val paused = MutableStateFlow(false)
    val decision = CompletableDeferred<Boolean>()
    private val _files = mutableListOf<TransferFile>()
    val files: List<TransferFile> get() = synchronized(_files) { _files.toList() }
    fun add(f: TransferFile) = synchronized(_files) { _files.add(f) }
    fun file(index: Int) = synchronized(_files) { _files.getOrNull(index) }
    val fileCount get() = synchronized(_files) { _files.size }
    val totalBytes get() = files.sumOf { if (it.size >= 0) it.size else it.received.get() }
    val receivedBytes get() = files.sumOf { it.received.get() }
}

/** Immutable view for the UI. */
data class SessionUi(
    val id: String, val sender: String, val status: SessionStatus, val paused: Boolean,
    val fileCount: Int, val doneCount: Int, val totalBytes: Long, val receivedBytes: Long,
    val currentName: String?, val names: List<String>, val createdAt: Long, val finishedAt: Long, val error: String?,
    /** Shortcuts upload: files are not known until after approval. */
    val adHoc: Boolean = false,
)

data class ReceivedItem(val uri: Uri, val name: String, val size: Long, val mime: String, val sender: String, val time: Long)

class TransferManager(private val settings: Settings, private val storage: Storage) {
    private val sessions = ConcurrentHashMap<String, TransferSession>()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val _events = MutableSharedFlow<TransferSession>(extraBufferCapacity = 64)
    private val _completed = MutableSharedFlow<ReceivedItem>(extraBufferCapacity = 256)

    /** Sessions that need the user's approval. */
    val approvalRequests: SharedFlow<TransferSession> = _events
    /** Files that finished successfully. */
    val completedFiles: SharedFlow<ReceivedItem> = _completed

    fun find(id: String) = sessions[id]

    fun snapshot(): List<SessionUi> = sessions.values.sortedByDescending { it.createdAt }
        .filter { !(it.adHoc && it.fileCount == 0 && it.status.value != SessionStatus.PENDING) }
        .map { s ->
            val files = s.files
            SessionUi(
                s.id, s.sender, s.status.value, s.paused.value, files.size, files.count { it.status == FileStatus.DONE },
                s.totalBytes, s.receivedBytes,
                (files.firstOrNull { it.status == FileStatus.RECEIVING } ?: files.firstOrNull { it.status == FileStatus.QUEUED })?.safeName,
                files.take(5).map { it.safeName }, s.createdAt, s.finishedAt, s.error, s.adHoc,
            )
        }

    // ---------------------------------------------------------------- sessions

    fun createSession(sender: String, remote: String, files: List<Triple<String, Long, String?>>, adHoc: Boolean = false): TransferSession {
        val st = settings.current
        if (!st.receivingEnabled) throw TransferError(503, ErrorCodes.RECEIVING_DISABLED, "Receiving is turned off.")
        if (!adHoc && files.isEmpty()) throw TransferError(400, ErrorCodes.INVALID, "No files.")
        if (files.size > Protocol.MAX_FILES) throw TransferError(400, ErrorCodes.INVALID, "Too many files.")
        if (files.any { it.second < 0 || it.second > 1L shl 42 }) throw TransferError(400, ErrorCodes.INVALID, "Invalid size.")
        ensureSpace(files.sumOf { it.second })

        val s = TransferSession(UUID.randomUUID().toString().replace("-", "").take(16), sender.ifBlank { "Unknown device" }.take(40), remote, adHoc)
        files.forEachIndexed { i, (name, size, type) -> s.add(TransferFile(i, name.take(255), FileNameSanitizer.sanitize(name), size, type)) }
        sessions[s.id] = s
        trimHistory()

        if (st.requireApproval) {
            scope.launch { delay(Protocol.APPROVAL_TIMEOUT_MS); expire(s) }
            _events.tryEmit(s)
        } else accept(s.id)
        return s
    }

    fun getOrCreateAdHoc(sender: String, remote: String): TransferSession {
        val now = System.currentTimeMillis()
        sessions.values.filter { it.adHoc && it.remote == remote && now - it.lastActivity < Protocol.AD_HOC_WINDOW_MS }
            .filter { it.status.value in setOf(SessionStatus.PENDING, SessionStatus.ACCEPTED, SessionStatus.ACTIVE, SessionStatus.COMPLETED) }
            .maxByOrNull { it.lastActivity }
            ?.let {
                if (it.status.value == SessionStatus.COMPLETED) it.status.value = SessionStatus.ACTIVE
                it.lastActivity = now
                return it
            }
        return createSession(sender, remote, emptyList(), adHoc = true)
    }

    fun addAdHocFile(s: TransferSession, name: String, size: Long, type: String?): TransferFile {
        if (size > 0) ensureSpace(size)
        val f = synchronized(s) { TransferFile(s.fileCount, name.take(255), FileNameSanitizer.sanitize(name), size, type).also { s.add(it) } }
        s.lastActivity = System.currentTimeMillis()
        return f
    }

    suspend fun awaitDecision(s: TransferSession): Boolean {
        when (s.status.value) {
            SessionStatus.PENDING -> {}
            SessionStatus.ACCEPTED, SessionStatus.ACTIVE, SessionStatus.COMPLETED -> return true
            else -> return false
        }
        return withTimeoutOrNull(Protocol.APPROVAL_TIMEOUT_MS) { s.decision.await() } ?: run { expire(s); false }
    }

    fun accept(id: String) {
        val s = sessions[id] ?: return
        if (s.status.value != SessionStatus.PENDING) return
        s.status.value = SessionStatus.ACCEPTED
        s.decision.complete(true)
    }

    fun decline(id: String) {
        val s = sessions[id] ?: return
        if (s.status.value != SessionStatus.PENDING) return
        s.status.value = SessionStatus.DECLINED
        s.finishedAt = System.currentTimeMillis()
        s.decision.complete(false)
    }

    fun pause(id: String) { sessions[id]?.takeIf { !it.status.value.finished }?.paused?.value = true }
    fun resume(id: String) { sessions[id]?.paused?.value = false }

    fun cancel(id: String) {
        val s = sessions[id] ?: return
        if (s.status.value.finished) return
        s.decision.complete(false)
        s.status.value = SessionStatus.CANCELED
        s.finishedAt = System.currentTimeMillis()
        s.paused.value = false
        for (f in s.files) {
            if (f.status == FileStatus.DONE) continue
            f.status = FileStatus.CANCELED
            if (f.lock.tryLock()) try { f.target?.delete(); f.target = null } finally { f.lock.unlock() }
        }
    }

    fun clearFinished() { sessions.values.filter { it.status.value.finished }.forEach { sessions.remove(it.id) } }

    private fun expire(s: TransferSession) {
        if (s.status.value != SessionStatus.PENDING) return
        s.status.value = SessionStatus.EXPIRED
        s.finishedAt = System.currentTimeMillis()
        s.decision.complete(false)
    }

    // ---------------------------------------------------------------- streaming

    enum class Result { DONE, INCOMPLETE }

    /** Reads from a Ktor channel (PUT body). */
    suspend fun receive(s: TransferSession, f: TransferFile, body: ByteReadChannel, offset: Long): Result =
        receiveImpl(s, f, offset) { buf ->
            // Coalesce the channel's small socket reads into one large write.
            var filled = 0
            while (filled < buf.size) {
                val n = body.readAvailable(buf, filled, buf.size - filled)
                if (n < 0) break
                filled += n
                if (body.availableForRead == 0 && filled >= 64 * 1024) break
            }
            if (filled == 0 && body.isClosedForRead) -1 else filled
        }

    /** Reads from a blocking stream (multipart part). */
    suspend fun receive(s: TransferSession, f: TransferFile, body: InputStream, offset: Long): Result =
        receiveImpl(s, f, offset) { buf -> body.read(buf) }

    private suspend fun receiveImpl(s: TransferSession, f: TransferFile, offset: Long, read: suspend (ByteArray) -> Int): Result {
        checkWritable(s)
        f.lock.withLock {
            checkWritable(s)
            if (f.status == FileStatus.DONE) return Result.DONE
            if (f.status == FileStatus.CANCELED) throw TransferError(410, ErrorCodes.CANCELED, "Canceled.")
            if (offset != f.received.get()) throw TransferError(409, ErrorCodes.OFFSET_MISMATCH, "Expected offset ${f.received.get()}.")

            if (s.status.value == SessionStatus.ACCEPTED) s.status.value = SessionStatus.ACTIVE
            f.status = FileStatus.RECEIVING
            s.lastActivity = System.currentTimeMillis()

            val target = try { f.target ?: local { storage.create(f.safeName, f.type) }.also { f.target = it } }
                catch (e: LocalIo) { throw TransferError(500, ErrorCodes.WRITE_FAILED, "Could not create the file: ${e.cause?.message}") }
            val buffer = ByteArray(512 * 1024)
            try {
                val out: OutputStream = local { target.open(append = offset > 0) }
                // Whole loop on the IO dispatcher: no thread hop per chunk.
                withContext(Dispatchers.IO) { out.use { stream ->
                    while (true) {
                        if (s.paused.value) combine(s.paused, s.status) { p, st -> !p || st.finished }.first { it }
                        if (s.status.value == SessionStatus.CANCELED) throw CanceledByReceiver()
                        val n = read(buffer)
                        if (n < 0) break
                        if (n == 0) continue
                        if (f.size >= 0 && f.received.get() + n > f.size) throw TransferError(400, ErrorCodes.INVALID, "More data than declared.")
                        local { stream.write(buffer, 0, n) }
                        f.received.addAndGet(n.toLong())
                        s.lastActivity = System.currentTimeMillis()
                    }
                    local { stream.flush() }
                } }
            } catch (e: CanceledByReceiver) {
                f.status = FileStatus.CANCELED
                target.delete(); f.target = null
                throw TransferError(410, ErrorCodes.CANCELED, "Canceled by the receiver.")
            } catch (e: LocalIo) {
                f.status = FileStatus.FAILED
                fail(s, if (isNoSpace(e.cause)) "Not enough storage" else (e.cause?.message ?: "Write failed"))
                target.delete(); f.target = null
                throw if (isNoSpace(e.cause)) TransferError(507, ErrorCodes.INSUFFICIENT_STORAGE, "Not enough storage.")
                else TransferError(500, ErrorCodes.WRITE_FAILED, "Could not write the file.")
            } catch (e: TransferError) {
                throw e
            } catch (e: Exception) {
                // Network dropped: keep the partial item (still pending in MediaStore) so the sender can resume.
                if (s.status.value == SessionStatus.CANCELED) { f.status = FileStatus.CANCELED; target.delete(); f.target = null }
                else if (f.status == FileStatus.RECEIVING) f.status = FileStatus.QUEUED
                throw e
            }

            if (f.size >= 0 && f.received.get() < f.size) { f.status = FileStatus.QUEUED; return Result.INCOMPLETE }
            if (f.size < 0) f.size = f.received.get()
            val saved = local { target.commit() }
            f.savedName = saved
            f.status = FileStatus.DONE
            _completed.tryEmit(ReceivedItem(target.uri, saved, f.size, Storage.mimeFor(saved, f.type), s.sender, System.currentTimeMillis()))
            if (s.files.all { it.status == FileStatus.DONE } && (s.status.value == SessionStatus.ACTIVE || s.status.value == SessionStatus.ACCEPTED)) {
                s.finishedAt = System.currentTimeMillis()
                s.status.value = SessionStatus.COMPLETED
            }
            return Result.DONE
        }
    }

    // ---------------------------------------------------------------- helpers

    private class CanceledByReceiver : Exception()
    private class LocalIo(cause: Throwable) : Exception(cause)

    private inline fun <T> local(block: () -> T): T = try { block() } catch (e: TransferError) { throw e } catch (e: IOException) { throw LocalIo(e) } catch (e: SecurityException) { throw LocalIo(e) } catch (e: IllegalStateException) { throw LocalIo(e) }

    private fun isNoSpace(t: Throwable?) = t?.message?.contains("ENOSPC") == true || t?.message?.contains("No space", ignoreCase = true) == true

    private fun fail(s: TransferSession, error: String) {
        s.error = error
        s.finishedAt = System.currentTimeMillis()
        s.status.value = SessionStatus.FAILED
    }

    private fun checkWritable(s: TransferSession) {
        when (s.status.value) {
            SessionStatus.ACCEPTED, SessionStatus.ACTIVE, SessionStatus.COMPLETED -> return
            SessionStatus.PENDING -> throw TransferError(409, "pending", "Waiting for approval.")
            SessionStatus.DECLINED -> throw TransferError(403, ErrorCodes.DECLINED, "Declined.")
            SessionStatus.EXPIRED -> throw TransferError(403, ErrorCodes.EXPIRED, "Approval timed out.")
            else -> throw TransferError(410, ErrorCodes.CANCELED, s.error ?: "Canceled.")
        }
    }

    private fun ensureSpace(bytes: Long) {
        if (bytes > 0 && storage.freeBytes() < bytes + 64L * 1024 * 1024)
            throw TransferError(507, ErrorCodes.INSUFFICIENT_STORAGE, "Not enough storage on the TV.")
    }

    private fun trimHistory() {
        sessions.values.filter { it.status.value.finished }.sortedByDescending { it.createdAt }.drop(100).forEach { sessions.remove(it.id) }
    }
}
