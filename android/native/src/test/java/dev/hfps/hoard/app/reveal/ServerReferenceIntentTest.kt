package dev.hfps.hoard.app.reveal

import android.app.Activity
import android.content.ComponentName
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageInfo
import android.net.Uri
import android.os.Bundle
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import dev.hfps.hoard.smb.CredentialStore
import dev.hfps.hoard.smb.share.RevealHandoff
import dev.hfps.hoard.smb.viewer.Opener
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * The server-reference sender (Reveal ADR 0011 amendment W3/H, Hoard ADR 0010 "Server items") against a test receiver:
 * a package `dev.hfps.reveal` (versionCode 90000) that answers the DEVELOP action; the intents Hoard starts are read
 * back as Reveal would. Stands in for an end-to-end run until a Reveal 0.9.0 build exists.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class ServerReferenceIntentTest {
    private val context = ApplicationProvider.getApplicationContext<android.app.Application>()

    private fun installReveal(versionCode: Long) {
        val pm = shadowOf(context.packageManager)
        val component = ComponentName(dev.hfps.hoard.smb.share.RevealPackages.RELEASE, "dev.hfps.reveal.MainActivity")
        pm.installPackage(PackageInfo().apply { packageName = component.packageName; longVersionCode = versionCode; applicationInfo = android.content.pm.ApplicationInfo().apply { packageName = component.packageName } })
        pm.addActivityIfNotPresent(component)
        pm.addIntentFilterForActivity(component, IntentFilter(RevealHandoff.ACTION).apply { addDataType("image/*"); addCategory(Intent.CATEGORY_DEFAULT) })
    }

    private fun handoff() = RevealHandoff(context, Opener(context), CredentialStore(context))

    private fun item(n: Int, pair: Boolean = n % 2 == 0) = RevealHandoff.ServerItem(
        "item-$n", "v1:" + "%064x".format(n), if (pair) listOf("v1:" + "%064x".format(n), "v1:" + "%064x".format(n + 9000)) else emptyList(), "DSC%05d.ARW".format(n),
    )

    @Test
    fun serverItemsGoByReferenceWithoutUrisGrantsOrCulling() {
        installReveal(90000)
        val reveal = handoff()
        assertThat(reveal.acceptsServerReferences()).isTrue()
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        reveal.launchMixed(activity, emptyList(), "  Trip  ", null, emptyMap(), RevealHandoff.ServerItems("srv-1", listOf(item(1), item(2)), initialItemId = "item-2"))
        val intent = shadowOf(activity).nextStartedActivity
        assertThat(intent.action).isEqualTo(RevealHandoff.ACTION)
        assertThat(intent.`package`).isEqualTo(dev.hfps.hoard.smb.share.RevealPackages.RELEASE)
        assertThat(intent.clipData).isNull()
        assertThat(intent.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION).isEqualTo(0)
        assertThat(intent.getIntExtra(RevealHandoff.EXTRA_SERVER_REF_VERSION, 0)).isEqualTo(1)
        assertThat(intent.getStringExtra(RevealHandoff.EXTRA_SERVER_ID)).isEqualTo("srv-1")
        assertThat(intent.getStringExtra(RevealHandoff.EXTRA_INITIAL_ITEM)).isEqualTo("item-2")
        assertThat(intent.getStringExtra(RevealHandoff.EXTRA_TITLE)).isEqualTo("Trip")
        assertThat(intent.hasExtra(RevealHandoff.EXTRA_CULLING)).isFalse()
        assertThat(intent.hasExtra(RevealHandoff.EXTRA_BATCH_INDEX)).isFalse()
        assertThat(intent.hasExtra(RevealHandoff.EXTRA_BATCH_COUNT)).isFalse()
        @Suppress("DEPRECATION")
        val items = intent.getParcelableArrayListExtra<Bundle>(RevealHandoff.EXTRA_SERVER_ITEMS)!!
        assertThat(items.map { it.getString("itemId") }).containsExactly("item-1", "item-2").inOrder()
        assertThat(items[0].keySet()).containsExactly("itemId", "fingerprint", "name")
        assertThat(items[1].getStringArray("fingerprints")!!.toList()).hasSize(2)
    }

    @Test
    fun aBigBatchIsSeveralIntentsWithTheSameTitle() {
        installReveal(90100)
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        handoff().launchMixed(activity, emptyList(), "Year", null, emptyMap(), RevealHandoff.ServerItems("srv-1", (1..1001).map { item(it) }))
        // Robolectric hands started intents back last first: put them in item order.
        @Suppress("DEPRECATION")
        val started = generateSequence { shadowOf(activity).nextStartedActivity }.toList()
            .sortedBy { it.getParcelableArrayListExtra<Bundle>(RevealHandoff.EXTRA_SERVER_ITEMS)!!.first().getString("itemId")!!.removePrefix("item-").toInt() }
        @Suppress("DEPRECATION")
        val sizes = started.map { it.getParcelableArrayListExtra<Bundle>(RevealHandoff.EXTRA_SERVER_ITEMS)!!.size }
        assertThat(sizes).containsExactly(500, 500, 1).inOrder()
        assertThat(started.map { it.getStringExtra(RevealHandoff.EXTRA_TITLE) }.toSet()).containsExactly("Year")
        // The whole first intent, parcelled, stays far under the Binder limit.
        val parcel = android.os.Parcel.obtain()
        started[0].extras!!.writeToParcel(parcel, 0)
        assertThat(parcel.dataSize()).isLessThan(400_000)
        parcel.recycle()
    }

    @Test
    fun everyIntentOfABatchCarriesItsIndexAndTheCount() {
        installReveal(90100)
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        handoff().launchMixed(activity, emptyList(), "Year", null, emptyMap(), RevealHandoff.ServerItems("srv-1", (1..1001).map { item(it) }, initialItemId = "item-3"))
        val started = generateSequence { shadowOf(activity).nextStartedActivity }.toList().sortedBy { it.getIntExtra(RevealHandoff.EXTRA_BATCH_INDEX, -1) }
        assertThat(started.map { it.getIntExtra(RevealHandoff.EXTRA_BATCH_INDEX, -1) }).containsExactly(0, 1, 2).inOrder()
        assertThat(started.map { it.getIntExtra(RevealHandoff.EXTRA_BATCH_COUNT, -1) }).containsExactly(3, 3, 3)
        assertThat(started[0].getStringExtra(RevealHandoff.EXTRA_INITIAL_ITEM)).isEqualTo("item-3")
        assertThat(started[1].hasExtra(RevealHandoff.EXTRA_INITIAL_ITEM)).isFalse()
    }

    @Test
    fun oneIntentMayMixMediaStoreUrisAndServerItems() {
        installReveal(90000)
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        // 2.2.0: a phone photo by its own MediaStore URI, an SMB photo by the URI of its copy in Pictures/Hoard.
        val uri = Uri.parse("content://media/external_primary/images/media/41")
        val copy = Uri.parse("content://media/external_primary/images/media/97")
        handoff().launchMixed(
            activity, listOf(RevealHandoff.Outgoing("DCIM/PXL_1.jpg", uri), RevealHandoff.Outgoing("shoot/DSC1.ARW", copy)), "Mixed", "DCIM/PXL_1.jpg",
            mapOf("DCIM/PXL_1.jpg" to RevealHandoff.Culling(3, "pick", ratingModifiedAt = 10, flagModifiedAt = 11, ratingDevice = "dev", flagDevice = "dev")),
            RevealHandoff.ServerItems("srv-1", listOf(item(1))),
        )
        val intent = shadowOf(activity).nextStartedActivity
        assertThat(intent.clipData!!.itemCount).isEqualTo(2)
        assertThat((0 until 2).map { intent.clipData!!.getItemAt(it).uri }).containsExactly(uri, copy).inOrder()
        // A plain read grant on exactly those URIs: never persistable, never write.
        assertThat(intent.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION).isNotEqualTo(0)
        assertThat(intent.flags and Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION).isEqualTo(0)
        assertThat(intent.flags and Intent.FLAG_GRANT_WRITE_URI_PERMISSION).isEqualTo(0)
        @Suppress("DEPRECATION")
        assertThat(intent.getParcelableExtra<Uri>(RevealHandoff.EXTRA_INITIAL_URI)).isEqualTo(uri)
        assertThat(intent.getIntExtra(RevealHandoff.EXTRA_CULLING_VERSION, 0)).isEqualTo(2)
        val culling = intent.getBundleExtra(RevealHandoff.EXTRA_CULLING)!!.getBundle(uri.toString())!!
        assertThat(culling.getLong("flagModifiedAt")).isEqualTo(11)
        assertThat(culling.getString("ratingDevice")).isEqualTo("dev")
        @Suppress("DEPRECATION")
        assertThat(intent.getParcelableArrayListExtra<Bundle>(RevealHandoff.EXTRA_SERVER_ITEMS)!!.single().getString("itemId")).isEqualTo("item-1")
    }

    @Test
    fun anOlderRevealGetsCullingVersion1AndNoServerItemsFromTheCaller() {
        installReveal(80000)
        val reveal = handoff()
        assertThat(reveal.acceptsServerReferences()).isFalse()
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        val uri = Uri.parse("content://media/external_primary/images/media/7")
        reveal.launchMixed(activity, listOf(RevealHandoff.Outgoing("a/DSC1.ARW", uri)), null, null, mapOf("a/DSC1.ARW" to RevealHandoff.Culling(2, "reject", ratingDevice = "dev")), null)
        val intent = shadowOf(activity).nextStartedActivity
        assertThat(intent.getIntExtra(RevealHandoff.EXTRA_CULLING_VERSION, 0)).isEqualTo(1)
        assertThat(intent.getBundleExtra(RevealHandoff.EXTRA_CULLING)!!.getBundle(uri.toString())!!.containsKey("ratingDevice")).isFalse()
        assertThat(intent.flags and Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION).isEqualTo(0)
    }

    @Test
    fun aBatchOfUrisAndManyServerItemsCarriesTheUrisOnTheFirstIntent() {
        installReveal(90000)
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        val uri = Uri.parse("content://media/external_primary/downloads/3")
        handoff().launchMixed(activity, listOf(RevealHandoff.Outgoing("a/IMG_1.CR3", uri)), "Mix", null, emptyMap(), RevealHandoff.ServerItems("srv-1", (1..600).map { item(it) }))
        val started = generateSequence { shadowOf(activity).nextStartedActivity }.toList().sortedBy { it.getIntExtra(RevealHandoff.EXTRA_BATCH_INDEX, -1) }
        assertThat(started).hasSize(2)
        assertThat(started[0].clipData!!.getItemAt(0).uri).isEqualTo(uri)
        assertThat(started[1].clipData).isNull()
        assertThat(started.map { it.getIntExtra(RevealHandoff.EXTRA_BATCH_COUNT, -1) }).containsExactly(2, 2)
    }

    @Test
    fun anOlderRevealDoesNotTakeReferences() {
        installReveal(70100)
        assertThat(handoff().acceptsServerReferences()).isFalse()
    }
}
