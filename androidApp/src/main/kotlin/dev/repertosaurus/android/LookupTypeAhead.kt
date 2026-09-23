package dev.repertosaurus.android

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.FilterChip
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.repertosaurus.core.NearMatches

/**
 * What a [LookupTypeAhead]'s supporting line has to say, decided once by the type-ahead and
 * worded by each caller: the add sheet and the song detail say different things about the same
 * four states.
 */
internal sealed interface TypeAheadHint<out T> {
    /** A suggestion was picked; the field carries its id. */
    data object Picked : TypeAheadHint<Nothing>

    /** The typed text lands on an existing row ([NearMatches.exact], the one exact-match rule). */
    data class Exact<T>(val match: T) : TypeAheadHint<T>

    /** Nothing typed. */
    data object Blank : TypeAheadHint<Nothing>

    /** A name that matches no row exactly: saving creates it (E5). */
    data class New(val text: String) : TypeAheadHint<Nothing>
}

/**
 * **R16 / E5: a create-on-enter type-ahead over an in-memory lookup list — one composable**
 * (style review F22 B3), for the add sheet's artist and the song detail's artist and groove.
 *
 * While the text is typed (nothing [picked]) the near-matches from [suggest] show as chips under
 * the field; picking one calls [onPick], which must carry its id. [onType] must drop a picked id
 * (package 1 ruling), which brings the chips back. **Once a row is picked the chips are hidden** —
 * the field already names it, and the detail opens with its stored artist picked, where a chip
 * row repeating it would be noise.
 *
 * [hint] words the supporting line for each [TypeAheadHint]; an [error] replaces it.
 */
@Composable
internal fun <T> LookupTypeAhead(
    label: String,
    text: String,
    picked: Boolean,
    items: List<T>,
    suggest: (query: String, items: List<T>) -> List<T>,
    name: (T) -> String,
    hint: (TypeAheadHint<T>) -> String?,
    onType: (String) -> Unit,
    onPick: (T) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    error: String? = null,
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
    keyboardActions: KeyboardActions = KeyboardActions.Default,
) {
    val suggestions = remember(text, picked, items) { if (picked) emptyList() else suggest(text, items) }
    val exact = remember(text, suggestions) { NearMatches.exact(text, suggestions, name) }
    val state: TypeAheadHint<T> = when {
        picked -> TypeAheadHint.Picked
        exact != null -> TypeAheadHint.Exact(exact)
        text.isBlank() -> TypeAheadHint.Blank
        else -> TypeAheadHint.New(text.trim())
    }
    val supporting = error ?: hint(state)
    OutlinedTextField(
        value = text,
        onValueChange = onType,
        enabled = enabled,
        label = { Text(label) },
        isError = error != null,
        supportingText = supporting?.let { line -> @Composable { Text(line) } },
        singleLine = true,
        keyboardOptions = keyboardOptions,
        keyboardActions = keyboardActions,
        modifier = modifier.fillMaxWidth(),
    )
    if (suggestions.isNotEmpty()) {
        Row(
            modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            for (match in suggestions) {
                FilterChip(
                    selected = false,
                    enabled = enabled,
                    onClick = { onPick(match) },
                    label = { Text(name(match)) },
                    modifier = Modifier.height(44.dp),
                )
            }
        }
    }
}
