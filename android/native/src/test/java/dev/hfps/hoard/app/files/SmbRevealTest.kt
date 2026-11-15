package dev.hfps.hoard.app.files

import com.google.common.truth.Truth.assertThat
import dev.hfps.hoard.server.paths.FileMatch
import dev.hfps.hoard.smb.share.RevealHandoff
import org.junit.Test

/** ADR 0022 "Edit in Reveal": per shot, a server item or URIs; the version gate; lookups; batches of 500. */
class SmbRevealTest {
    private val dir = "Imagens/2024/Trip"
    private fun p(name: String) = "$dir/$name"

    private val index = mapOf(
        p("DSC1.ARW") to FileMatch.Mapped("pair-1", "v1:r1", "raw"),
        p("DSC1.JPG") to FileMatch.Mapped("pair-1", "v1:j1", "rendered"),
        p("DSC2.JPG") to FileMatch.Changed("photo-2", "v1:j2"),
        p("DSC3.JPG") to FileMatch.Unknown,
    )
    private val match: (String) -> FileMatch = { index[it] ?: FileMatch.Unknown }
    private val paths = listOf(p("DSC1.ARW"), p("DSC1.JPG"), p("DSC2.JPG"), p("DSC3.JPG"))

    @Test
    fun mappedShotsGoAsServerItemsTheRestAsUris() {
        val plan = SmbReveal.plan(paths, match, acceptsReferences = true, paired = true, lookupsAllowed = false)
        assertThat(plan.server.map { it.itemId }).containsExactly("pair-1")
        // The RAW's item, matched by the RAW: never a URI for a mapped shot.
        assertThat(plan.server.single().matchedPath).isEqualTo(p("DSC1.ARW"))
        assertThat(plan.uris).containsExactly(p("DSC2.JPG"), p("DSC3.JPG")).inOrder()
        assertThat(plan.lookups).isEmpty()
    }

    @Test
    fun anOlderRevealOrNoPairingGetsUrisAsBefore() {
        val old = SmbReveal.plan(paths, match, acceptsReferences = false, paired = true, lookupsAllowed = true)
        assertThat(old.server).isEmpty()
        assertThat(old.uris).isEqualTo(paths)
        val unpaired = SmbReveal.plan(paths, match, acceptsReferences = true, paired = false, lookupsAllowed = true)
        assertThat(unpaired.uris).isEqualTo(paths)
    }

    @Test
    fun unmatchedShotsAreLookedUpThenSentByWhatTheLookupSaid() {
        val first = SmbReveal.plan(paths, match, acceptsReferences = true, paired = true, lookupsAllowed = true)
        assertThat(first.lookups.map { it.main }).containsExactly(p("DSC2.JPG"), p("DSC3.JPG")).inOrder()
        assertThat(first.uris).isEmpty()
        // DSC2 changed since the walk but has the same bytes' fingerprint as an item; DSC3 is not on the server.
        val looked = mapOf(first.lookups[0].key to ("photo-2" to "v1:j2"), first.lookups[1].key to null)
        val second = SmbReveal.plan(paths, match, acceptsReferences = true, paired = true, lookupsAllowed = false, looked = looked)
        assertThat(second.server.map { it.itemId }).containsExactly("pair-1", "photo-2").inOrder()
        assertThat(second.uris).containsExactly(p("DSC3.JPG"))
        assertThat(second.lookups).isEmpty()
    }

    @Test
    fun aShotWhoseRawIsNotMatchedIsLookedUpByTheRawNotSentAsItsJpeg() {
        // The JPEG was walked (a JPEG-only item); the RAW was copied in since, or changed: the JPEG's item is not the shot.
        val m: (String) -> FileMatch = { path ->
            when (path) {
                p("DSC5.ARW") -> FileMatch.Unknown
                p("DSC5.JPG") -> FileMatch.Mapped("jpeg-only-5", "v1:j5", "rendered")
                p("DSC6.ARW") -> FileMatch.Changed("pair-6", "v1:r6")
                p("DSC6.JPG") -> FileMatch.Mapped("pair-6", "v1:j6", "rendered")
                else -> FileMatch.Unknown
            }
        }
        val shot = listOf(p("DSC5.ARW"), p("DSC5.JPG"), p("DSC6.ARW"), p("DSC6.JPG"))
        val first = SmbReveal.plan(shot, m, acceptsReferences = true, paired = true, lookupsAllowed = true)
        assertThat(first.server).isEmpty()
        // Looked up by the RAW's own fingerprint.
        assertThat(first.lookups.map { it.main }).containsExactly(p("DSC5.ARW"), p("DSC6.ARW")).inOrder()
        // DSC5's RAW is not on the server: the whole shot goes as URIs (RAW and JPEG), never the JPEG's item alone.
        val looked = mapOf(first.lookups[0].key to null, first.lookups[1].key to ("pair-6" to "v1:r6"))
        val second = SmbReveal.plan(shot, m, acceptsReferences = true, paired = true, lookupsAllowed = false, looked = looked)
        assertThat(second.server.map { it.itemId }).containsExactly("pair-6")
        assertThat(second.uris).containsExactly(p("DSC5.ARW"), p("DSC5.JPG")).inOrder()
        // No lookups allowed (or over the budget): URIs for both files, as before.
        val none = SmbReveal.plan(shot, m, acceptsReferences = true, paired = true, lookupsAllowed = false)
        assertThat(none.server).isEmpty()
        assertThat(none.uris).isEqualTo(shot)
    }

    @Test
    fun aRenameGuessIsLookedUpNotSent() {
        val m: (String) -> FileMatch = { FileMatch.Changed("deleted-frame", "v1:old", renamed = true) }
        val plan = SmbReveal.plan(listOf(p("DSC7.ARW")), m, acceptsReferences = true, paired = true, lookupsAllowed = true)
        assertThat(plan.server).isEmpty()
        assertThat(plan.lookups.map { it.main }).containsExactly(p("DSC7.ARW"))
    }

    @Test
    fun lookupsAreCapped() {
        val many = (1..50).map { p("NEW$it.JPG") }
        val plan = SmbReveal.plan(many, { FileMatch.Unknown }, acceptsReferences = true, paired = true, lookupsAllowed = true)
        assertThat(plan.lookups).hasSize(SmbReveal.MAX_LOOKUPS)
        assertThat(plan.uris).hasSize(50 - SmbReveal.MAX_LOOKUPS)
    }

    @Test
    fun aMixedHandOffOfMoreThan500ItemsIsSplit() {
        val big = (1..1200).flatMap { listOf(p("S$it.ARW"), p("S$it.JPG")) } + p("LOCAL.JPG")
        val m: (String) -> FileMatch = { path ->
            if (path.endsWith("LOCAL.JPG")) FileMatch.Unknown else FileMatch.Mapped("item-" + path.substringAfterLast('/').substringBefore('.'), null, if (path.endsWith("ARW")) "raw" else "rendered")
        }
        val plan = SmbReveal.plan(big, m, acceptsReferences = true, paired = true, lookupsAllowed = false)
        assertThat(plan.server).hasSize(1200)
        assertThat(plan.uris).containsExactly(p("LOCAL.JPG"))
        val batches = RevealHandoff.serverBatches(plan.server.map { RevealHandoff.ServerItem(it.itemId, it.fingerprint, emptyList(), it.shot.main) })
        assertThat(batches.map { it.size }).containsExactly(500, 500, 200).inOrder()
    }

    @Test
    fun shotsGroupByFolderAndStemKeepingOrder() {
        val shots = SmbReveal.shots(listOf("a/X.ARW", "a/x.jpg", "b/X.JPG", "a/Y.JPG"))
        assertThat(shots.map { it.paths }).containsExactly(listOf("a/X.ARW", "a/x.jpg"), listOf("b/X.JPG"), listOf("a/Y.JPG")).inOrder()
    }
}
