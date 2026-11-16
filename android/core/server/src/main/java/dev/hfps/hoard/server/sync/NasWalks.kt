package dev.hfps.hoard.server.sync

import dev.hfps.hoard.server.api.NasWalkRequest
import dev.hfps.hoard.server.api.ServerApi
import dev.hfps.hoard.server.api.ServerException
import kotlinx.coroutines.delay

/**
 * `POST /v1/nas/walk` after a trash run (ADR 0023, hoard-server 0.11.0): at most 100 folders per call. The server
 * queues each folder once and may defer its walk (`202` with `deferred`: when it may start; nothing to do but wait);
 * past the per-device budget it answers `429 rate_limited` with `Retry-After`, honoured a few times; a folder it does
 * not index (`404`) is dropped from the request. Best effort: the scheduled walk catches up anyway.
 */
object NasWalks {
    data class Outcome(
        val queued: List<String> = emptyList(),
        val deferred: Map<String, Long> = emptyMap(),
        /** Folders still rate-limited after the retries (the scheduled walk will see them). */
        val rateLimited: List<String> = emptyList(),
        val unknown: List<String> = emptyList(),
    )

    const val MAX_RETRIES = 3
    private const val DEFAULT_RETRY_S = 10L
    private const val MAX_RETRY_S = 120L

    suspend fun request(api: ServerApi, folders: List<String>, sleep: suspend (Long) -> Unit = { delay(it) }): Outcome {
        val queued = ArrayList<String>()
        val deferred = LinkedHashMap<String, Long>()
        val limited = ArrayList<String>()
        val unknown = ArrayList<String>()
        for (chunk in folders.distinct().chunked(NasWalkRequest.MAX_FOLDERS)) {
            var ask = chunk
            var retries = 0
            while (ask.isNotEmpty()) {
                try {
                    val r = api.nasWalk(ask)
                    queued += r.queued
                    deferred += r.deferred
                    break
                } catch (e: ServerException) {
                    when {
                        e.status == 429 && retries < MAX_RETRIES -> {
                            retries++
                            sleep((e.retryAfterSeconds ?: DEFAULT_RETRY_S).coerceIn(1, MAX_RETRY_S) * 1000)
                        }
                        e.status == 429 -> {
                            limited += ask
                            break
                        }
                        e.status == 404 && ask.size > 1 -> {
                            // Which folder is unknown is not parsed: ask one by one.
                            for (one in ask) {
                                val single = request(api, listOf(one), sleep)
                                queued += single.queued
                                deferred += single.deferred
                                limited += single.rateLimited
                                unknown += single.unknown
                            }
                            break
                        }
                        e.status == 404 -> {
                            unknown += ask
                            break
                        }
                        else -> throw e
                    }
                }
            }
        }
        return Outcome(queued, deferred, limited, unknown)
    }
}
