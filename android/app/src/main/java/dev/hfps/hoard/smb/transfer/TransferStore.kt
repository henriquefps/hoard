package dev.hfps.hoard.smb.transfer

import android.content.Context
import android.util.AtomicFile
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Jobs persisted one file each under `files/transfers/<id>.json` (atomic replace), items included,
 * so `getTransfers` answers after the app was killed and a worker restarted by WorkManager resumes
 * where the items say. 1.3's single `hoard_transfers.json` is migrated once. Finished jobs stay
 * (hidden once acknowledged) so Retry can run their failed items again; only the newest
 * [KEEP_FINISHED] finished jobs are kept.
 */
class TransferStore(context: Context) {

    private val dir = File(context.applicationContext.filesDir, "transfers").apply { mkdirs() }
    private val legacy = File(context.applicationContext.filesDir, "hoard_transfers.json")
    private val jobs = LinkedHashMap<String, TransferJob>()

    init {
        load()
    }

    @Synchronized
    fun get(transferId: String): TransferJob? = jobs[transferId]

    @Synchronized
    fun all(): List<TransferJob> = jobs.values.toList()

    @Synchronized
    fun put(job: TransferJob) {
        jobs[job.transferId] = job
        save(job)
        trim()
    }

    /** Persist the job's current state. */
    fun save(job: TransferJob) {
        val text = runCatching { job.toJson().toString() }.getOrElse {
            Log.w(TAG, "Could not serialize ${job.transferId}", it)
            return
        }
        synchronized(this) {
            if (jobs[job.transferId] !== job) return
            val file = AtomicFile(fileOf(job.transferId))
            val out = try { file.startWrite() } catch (e: Exception) { Log.w(TAG, "Could not persist ${job.transferId}", e); return }
            try {
                out.write(text.toByteArray(Charsets.UTF_8))
                file.finishWrite(out)
            } catch (e: Exception) {
                file.failWrite(out)
                Log.w(TAG, "Could not persist ${job.transferId}", e)
            }
        }
    }

    @Synchronized
    fun remove(transferId: String) {
        jobs.remove(transferId)
        AtomicFile(fileOf(transferId)).delete()
    }

    private fun fileOf(id: String) = File(dir, "${id.replace(Regex("[^A-Za-z0-9_-]"), "_")}.json")

    /** Keep every unfinished job and the newest finished ones. */
    private fun trim() {
        val finished = jobs.values.filter { it.isTerminal }.sortedByDescending { it.finishedAt ?: it.startedAt }
        finished.drop(KEEP_FINISHED).forEach { remove(it.transferId) }
    }

    private fun load() {
        dir.listFiles { f -> f.name.endsWith(".json") }?.forEach { f ->
            runCatching { TransferJob.fromJson(JSONObject(AtomicFile(f).readFully().toString(Charsets.UTF_8))) }
                .onSuccess { jobs[it.transferId] = it }
                .onFailure { Log.w(TAG, "Skipping unreadable transfer ${f.name}", it) }
        }
        if (legacy.exists()) {
            runCatching {
                val array = JSONArray(AtomicFile(legacy).readFully().toString(Charsets.UTF_8))
                for (i in 0 until array.length()) {
                    runCatching { TransferJob.fromLegacy(array.getJSONObject(i)) }.onSuccess {
                        if (it.transferId !in jobs) {
                            jobs[it.transferId] = it
                            save(it)
                        }
                    }
                }
            }
            AtomicFile(legacy).delete()
        }
    }

    private companion object {
        const val TAG = "HoardSmb"
        const val KEEP_FINISHED = 100
    }
}
