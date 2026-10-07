package dev.pschmitt.jellyfin.presentation.film.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import dev.pschmitt.jellyfin.core.R as CoreR
import dev.pschmitt.jellyfin.models.JollyfinItemPerson
import dev.pschmitt.jellyfin.presentation.theme.spacings
import java.util.UUID

@Composable
fun ActorsRow(
    actors: List<JollyfinItemPerson>,
    onActorClick: (personId: UUID) -> Unit,
    contentPadding: PaddingValues,
) {
    Column(modifier = Modifier.padding(contentPadding)) {
        Text(
            text = stringResource(CoreR.string.cast_amp_crew),
            style = MaterialTheme.typography.titleMedium,
        )
        Spacer(Modifier.height(MaterialTheme.spacings.small))
    }
    LazyRow(
        contentPadding = contentPadding,
        horizontalArrangement = Arrangement.spacedBy(MaterialTheme.spacings.medium),
    ) {
        // The same person can be credited more than once (e.g. voicing two characters), so the
        // id alone isn't a unique key - a duplicate key crashes the whole LazyRow.
        itemsIndexed(items = actors, key = { index, person -> "${person.id}-$index" }) { _, person
            ->
            PersonItem(person = person, onClick = { onActorClick(person.id) })
        }
    }
}
