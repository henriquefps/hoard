package dev.hfps.hoard.server.sync

import dev.hfps.hoard.server.ItemIds
import dev.hfps.hoard.server.api.CullingChange
import dev.hfps.hoard.server.api.CullingField
import dev.hfps.hoard.server.api.CullingRecord
import dev.hfps.hoard.server.api.CullingResult
import dev.hfps.hoard.server.db.CullingEntity
import dev.hfps.hoard.server.db.CullingFingerprintEntity
import dev.hfps.hoard.server.db.PendingCullingEntity
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.contentOrNull

/*
 * ADR 0016 "Client rules" for Hoard (rules 1-4 and 6), pure and JVM-tested (CullingRulesTest): the server is the
 * reference and the local database a cache of its per-field values; local decisions queue per field (the last one
 * wins, with its own time) and are pushed with the cached rev as `baseRev`; a pulled record never overrides a field
 * that has a pending change; records map to local shots by any member fingerprint, following `replacedBy`.
 */

/** The three culling fields (ADR 0016 "The culling record"). */
enum class CullField(val wire: String) {
    Rating("rating"),
    Flag("flag"),
    Label("label"),
    ;

    companion object {
        fun of(wire: String): CullField? = entries.firstOrNull { it.wire == wire }
    }
}

/** A shot's culling as Hoard shows it: stars 0..5, flag `pick` / `reject` / null, label `""` = none. */
data class CullValues(val rating: Int = 0, val flag: String? = null, val label: String = "") {
    val unmarked: Boolean get() = rating == 0 && flag == null && label.isEmpty()
}

object CullingRules {
    const val FLAG_NONE = "none"

    fun encode(field: CullField, values: CullValues): String = when (field) {
        CullField.Rating -> values.rating.coerceIn(0, 5).toString()
        CullField.Flag -> JsonPrimitive(values.flag ?: FLAG_NONE).toString()
        CullField.Label -> JsonPrimitive(values.label).toString()
    }

    fun json(value: String): JsonElement = dev.hfps.hoard.server.api.ServerJson.parseToJsonElement(value)

    /** The cached server values of [record] (absent fields at their defaults). */
    fun serverValues(record: CullingEntity?): CullValues = CullValues(
        rating = record?.rating ?: 0,
        flag = record?.flag?.takeIf { it != FLAG_NONE },
        label = record?.label.orEmpty(),
    )

    /** What the shot shows: the server's values with every pending change over them (rule 3). */
    fun effective(record: CullingEntity?, pending: List<PendingCullingEntity>): CullValues {
        var v = serverValues(record)
        for (p in pending) {
            val value = json(p.value)
            v = when (CullField.of(p.field)) {
                CullField.Rating -> v.copy(rating = (value as? JsonPrimitive)?.intOrNull?.coerceIn(0, 5) ?: v.rating)
                CullField.Flag -> v.copy(flag = (value as? JsonPrimitive)?.contentOrNull?.takeIf { it != FLAG_NONE })
                CullField.Label -> v.copy(label = (value as? JsonPrimitive)?.contentOrNull.orEmpty())
                null -> v
            }
        }
        return v
    }

    /** The cached rev of a field (0 when the server never wrote it: rule 2's `baseRev`). */
    fun revOf(record: CullingEntity?, field: CullField): Long = when (field) {
        CullField.Rating -> record?.ratingRev
        CullField.Flag -> record?.flagRev
        CullField.Label -> record?.labelRev
    } ?: 0L

    /**
     * Rule 2: a local decision on [itemId] that turns [before] into [after] queues one change per field that changed,
     * dated [at]. A field already queued is replaced (several decisions before a push collapse to the last one, with
     * its time). A field set back to the server's value while queued still goes out: the server answers `unchanged`.
     */
    fun queue(itemId: String, record: CullingEntity?, before: CullValues, after: CullValues, at: Long): List<PendingCullingEntity> {
        val out = ArrayList<PendingCullingEntity>(3)
        for (field in CullField.entries) {
            if (encode(field, before) == encode(field, after)) continue
            out += PendingCullingEntity(itemId, field.wire, encode(field, after), revOf(record, field), at)
        }
        return out
    }

    /** The changes of a push, in the order of [pending] (by item id, ADR 0016 "Wire format"). */
    fun changes(pending: List<PendingCullingEntity>): List<CullingChange> = pending.map {
        CullingChange(itemId = it.itemId, field = it.field, value = json(it.value), baseRev = it.baseRev, modifiedAt = it.modifiedAt)
    }

    /** What to do with one answered change. */
    sealed interface Outcome {
        /** Drop it from the queue; when [wrote], the server now holds the pushed value at [rev] on [itemId]. */
        data class Done(val itemId: String, val wrote: Boolean, val rev: Long?) : Outcome
        /** An invalid change is dropped, never retried; the local value stays (e.g. `label_too_long`). */
        data class Dropped(val code: String?) : Outcome
    }

    fun outcome(sent: PendingCullingEntity, result: CullingResult): Outcome = when (result.status) {
        "applied" -> Outcome.Done(result.itemId ?: sent.itemId, wrote = true, rev = result.rev)
        "conflict" -> Outcome.Done(result.itemId ?: sent.itemId, wrote = result.winner == "incoming", rev = result.rev)
        "unchanged" -> Outcome.Done(result.itemId ?: sent.itemId, wrote = false, rev = result.rev)
        else -> Outcome.Dropped(result.code)
    }

    /**
     * The cache after a change the server accepted ([Outcome.Done] with `wrote`): the field takes the pushed value,
     * its rev and time, so the shot keeps showing it until the pull that follows the push confirms it.
     */
    fun written(record: CullingEntity?, itemId: String, sent: PendingCullingEntity, rev: Long, deviceId: String): CullingEntity {
        val base = record ?: CullingEntity(itemId, rev, null, null, null, null, null, null, null, null, null, null, null, null, null)
        val value = json(sent.value) as? JsonPrimitive
        val r = maxOf(base.rev, rev)
        return when (CullField.of(sent.field)) {
            CullField.Rating -> base.copy(rev = r, rating = value?.intOrNull, ratingRev = rev, ratingAt = sent.modifiedAt, ratingDevice = deviceId)
            CullField.Flag -> base.copy(rev = r, flag = value?.contentOrNull, flagRev = rev, flagAt = sent.modifiedAt, flagDevice = deviceId)
            CullField.Label -> base.copy(rev = r, label = value?.contentOrNull, labelRev = rev, labelAt = sent.modifiedAt, labelDevice = deviceId)
            null -> base
        }
    }

    /** A pulled record as cached (rule 3: always stored; pending changes override it in [effective]). */
    fun entity(record: CullingRecord): CullingEntity {
        fun int(f: CullingField?) = (f?.value as? JsonPrimitive)?.intOrNull
        fun str(f: CullingField?) = (f?.value as? JsonPrimitive)?.contentOrNull
        return CullingEntity(
            itemId = record.itemId,
            rev = record.rev,
            replacedBy = record.replacedBy,
            rating = int(record.rating), ratingRev = record.rating?.rev, ratingAt = record.rating?.modifiedAt, ratingDevice = record.rating?.deviceId,
            flag = str(record.flag), flagRev = record.flag?.rev, flagAt = record.flag?.modifiedAt, flagDevice = record.flag?.deviceId,
            label = str(record.label), labelRev = record.label?.rev, labelAt = record.label?.modifiedAt, labelDevice = record.label?.deviceId,
        )
    }

    fun fingerprints(record: CullingRecord): List<CullingFingerprintEntity> =
        record.fingerprints.distinct().map { CullingFingerprintEntity(it, record.itemId) }
}

/**
 * ADR 0016 "Mapping a record to a client's photos": which culling record (item id) a local shot belongs to.
 * [recordsByFingerprint] is every cached record's member fingerprints, [replacedBy] the replaced records.
 */
object CullingMapping {
    /**
     * 1. any member of a record equals any of the shot's files; 2. follow `replacedBy` to its end; 3. else the id
     * derived from the shot's main fingerprint (a phone-only shot, or a server-only item, which is its own record).
     */
    fun itemFor(
        shotFingerprints: Collection<String>,
        mainFingerprint: String?,
        recordsByFingerprint: Map<String, Collection<String>>,
        replacedBy: Map<String, String>,
    ): String? {
        val matched = shotFingerprints.asSequence().flatMap { recordsByFingerprint[it].orEmpty().asSequence() }
            .map { follow(it, replacedBy) }
            .firstOrNull()
        return matched ?: mainFingerprint?.let { follow(ItemIds.derive(it), replacedBy) }
    }

    fun follow(itemId: String, replacedBy: Map<String, String>): String {
        var id = itemId
        val seen = HashSet<String>()
        while (true) {
            val next = replacedBy[id] ?: return id
            if (!seen.add(id)) return id
            id = next
        }
    }
}
