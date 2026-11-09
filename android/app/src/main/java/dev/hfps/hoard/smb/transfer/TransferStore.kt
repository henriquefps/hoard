package dev.hfps.hoard.smb.transfer

import android.content.Context
import android.util.AtomicFile
import android.util.Log
import dev.hfps.hoard.smb.ErrorCode
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileNotFoundException

/** A host/port pair from `StartDownloadOptions.addresses`. */
data class TransferAddress(val host: String, val port: Int)

/** Contract transfer states. */
object TransferStates {
    const val QUEUED = "queued"
    const val RUNNING = "running"
    const val RETRYING = "retrying"
    const val COMPLETED = "completed"
    const val FAILED = "failed"
    const val CANCELLED = "cancelled"
    const val SKIPPED = "skipped"

    val TERMINAL = setOf(COMPLETED, FAILED, CANCELLED, SKIPPED)

    fun isTerminal(state: String) = state in TERMINAL
}

/** A native transfer job: the contract's `NativeTransfer` plus what the worker needs to run it. */
data class TransferJob(
    val transferId: String,
    val machineId: String,
    val addresses: List<TransferAddress>,
    val expectedServerGuid: String,
    val share: String,
    val remotePath: String,
    val destTreeUri: String,
    val destName: String,
    val destUri: String?,
    val totalBytes: Long = 0,
    val bytesDone: Long = 0,
    val state: String = TransferStates.QUEUED,
    val errorCode: ErrorCode? = null,
    val errorMessage: String? = null,
    val activeHost: String? = null,
    val startedAt: String,
    val finishedAt: String? = null,
    /** `resumeFromBytes` from JS, applied on the first run only. */
    val resumeFromBytes: Long = 0,
    /** Remote last-write time seen when bytes were written (a change restarts at 0). */
    val remoteModifiedMillis: Long? = null,
    /** True once the worker opened the destination at least once (later runs resume from its size). */
    val started: Boolean = false,
    /** The job created the destination document (so it may delete it if nothing was written). */
    val createdDocument: Boolean = false,
) {
    val isTerminal: Boolean get() = TransferStates.isTerminal(state)

    fun toJson(): JSONObject = JSONObject().apply {
        put("transferId", transferId)
        put("machineId", machineId)
        put("addresses", JSONArray().apply {
            addresses.forEach { put(JSONObject().put("host", it.host).put("port", it.port)) }
        })
        put("expectedServerGuid", expectedServerGuid)
        put("share", share)
        put("remotePath", remotePath)
        put("destTreeUri", destTreeUri)
        put("destName", destName)
        putOpt("destUri", destUri)
        put("totalBytes", totalBytes)
        put("bytesDone", bytesDone)
        put("state", state)
        putOpt("errorCode", errorCode?.name)
        putOpt("errorMessage", errorMessage)
        putOpt("activeHost", activeHost)
        put("startedAt", startedAt)
        putOpt("finishedAt", finishedAt)
        put("resumeFromBytes", resumeFromBytes)
        putOpt("remoteModifiedMillis", remoteModifiedMillis)
        put("started", started)
        put("createdDocument", createdDocument)
    }

    companion object {
        fun fromJson(json: JSONObject): TransferJob {
            val addresses = json.optJSONArray("addresses") ?: JSONArray()
            return TransferJob(
                transferId = json.getString("transferId"),
                machineId = json.getString("machineId"),
                addresses = (0 until addresses.length()).map {
                    val address = addresses.getJSONObject(it)
                    TransferAddress(address.getString("host"), address.optInt("port", 445))
                },
                expectedServerGuid = json.optString("expectedServerGuid"),
                share = json.getString("share"),
                remotePath = json.getString("remotePath"),
                destTreeUri = json.getString("destTreeUri"),
                destName = json.getString("destName"),
                destUri = json.optString("destUri").takeIf { json.has("destUri") && it.isNotEmpty() },
                totalBytes = json.optLong("totalBytes"),
                bytesDone = json.optLong("bytesDone"),
                state = json.optString("state", TransferStates.QUEUED),
                errorCode = json.optString("errorCode").takeIf { json.has("errorCode") }?.let(ErrorCode::fromName),
                errorMessage = json.optString("errorMessage").takeIf { json.has("errorMessage") },
                activeHost = json.optString("activeHost").takeIf { json.has("activeHost") },
                startedAt = json.getString("startedAt"),
                finishedAt = json.optString("finishedAt").takeIf { json.has("finishedAt") },
                resumeFromBytes = json.optLong("resumeFromBytes"),
                remoteModifiedMillis = if (json.has("remoteModifiedMillis")) json.optLong("remoteModifiedMillis") else null,
                started = json.optBoolean("started"),
                createdDocument = json.optBoolean("createdDocument"),
            )
        }
    }
}

/**
 * Jobs persisted to `files/hoard_transfers.json` (atomic replace), so `getTransfers` answers after the
 * app was killed and the worker can resume. Small by design: finished jobs leave once acknowledged.
 */
class TransferStore(context: Context) {

    private val file = AtomicFile(File(context.applicationContext.filesDir, "hoard_transfers.json"))
    private val jobs = LinkedHashMap<String, TransferJob>()

    init {
        load()
    }

    @Synchronized
    fun get(transferId: String): TransferJob? = jobs[transferId]

    @Synchronized
    fun all(): List<TransferJob> = jobs.values.toList()

    /** Insert or replace, then persist (unless [persist] is false: frequent progress updates). */
    @Synchronized
    fun put(job: TransferJob, persist: Boolean = true) {
        jobs[job.transferId] = job
        if (persist) save()
    }

    /** Atomically transform a job; returns the new value (null if the job is unknown). */
    @Synchronized
    fun update(transferId: String, persist: Boolean = true, transform: (TransferJob) -> TransferJob): TransferJob? {
        val current = jobs[transferId] ?: return null
        val next = transform(current)
        jobs[transferId] = next
        if (persist) save()
        return next
    }

    @Synchronized
    fun remove(transferIds: Collection<String>, onlyTerminal: Boolean = true) {
        var changed = false
        for (id in transferIds) {
            val job = jobs[id] ?: continue
            if (!onlyTerminal || job.isTerminal) {
                jobs.remove(id)
                changed = true
            }
        }
        if (changed) save()
    }

    @Synchronized
    fun flush() = save()

    private fun load() {
        try {
            val text = file.readFully().toString(Charsets.UTF_8)
            val array = JSONArray(text)
            for (i in 0 until array.length()) {
                runCatching { TransferJob.fromJson(array.getJSONObject(i)) }
                    .onSuccess { jobs[it.transferId] = it }
                    .onFailure { Log.w(TAG, "Skipping unreadable transfer record", it) }
            }
        } catch (_: FileNotFoundException) {
            // First run.
        } catch (e: Exception) {
            Log.w(TAG, "Transfer store unreadable, starting empty", e)
        }
    }

    private fun save() {
        val array = JSONArray()
        jobs.values.forEach { array.put(it.toJson()) }
        val stream = try {
            file.startWrite()
        } catch (e: Exception) {
            Log.w(TAG, "Could not persist transfers", e)
            return
        }
        try {
            stream.write(array.toString().toByteArray(Charsets.UTF_8))
            file.finishWrite(stream)
        } catch (e: Exception) {
            file.failWrite(stream)
            Log.w(TAG, "Could not persist transfers", e)
        }
    }

    private companion object {
        const val TAG = "HoardSmb"
    }
}
