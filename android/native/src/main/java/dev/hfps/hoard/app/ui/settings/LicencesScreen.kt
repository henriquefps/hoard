package dev.hfps.hoard.app.ui.settings

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dev.hfps.hoard.app.R
import dev.hfps.hoard.designsystem.components.HfpsListRow
import dev.hfps.hoard.designsystem.components.HfpsRowDivider
import dev.hfps.hoard.designsystem.components.HfpsScaffold
import dev.hfps.hoard.designsystem.components.HfpsTopBar
import dev.hfps.hoard.designsystem.components.rememberCollapsingHeaderState

/** A component Hoard ships and its licence (names are not translated: res/values/licences.xml). */
data class Licence(val name: Int, val licence: Int)

/** What the APK of :native contains, Hoard itself first. */
val LICENCES = listOf(
    Licence(R.string.licence_hoard, R.string.licence_agpl3),
    Licence(R.string.licence_libraw, R.string.licence_libraw_terms),
    Licence(R.string.licence_smbj, R.string.licence_apache2),
    Licence(R.string.licence_bouncycastle, R.string.licence_mit),
    Licence(R.string.licence_slf4j, R.string.licence_mit),
    Licence(R.string.licence_androidx, R.string.licence_apache2),
    Licence(R.string.licence_kotlin, R.string.licence_apache2),
    Licence(R.string.licence_mlkit, R.string.licence_mlkit_terms),
    Licence(R.string.licence_inter, R.string.licence_ofl),
    Licence(R.string.licence_phosphor, R.string.licence_mit),
)

@Composable
fun LicencesScreen(onBack: () -> Unit, modifier: Modifier = Modifier) {
    HfpsScaffold(
        modifier = modifier,
        header = rememberCollapsingHeaderState(),
        topBar = { HfpsTopBar(title = stringResource(R.string.licences_title), onBack = onBack) },
    ) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
            LazyColumn(Modifier.widthIn(max = 840.dp).fillMaxSize(), contentPadding = PaddingValues(vertical = 8.dp)) {
                items(LICENCES.size) { index ->
                    if (index > 0) HfpsRowDivider(Modifier.padding(start = 16.dp))
                    HfpsListRow(
                        title = stringResource(LICENCES[index].name),
                        subtitle = stringResource(LICENCES[index].licence),
                    )
                }
            }
        }
    }
}
