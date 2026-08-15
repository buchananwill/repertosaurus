package dev.songbook.android

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import dev.songbook.data.SongbookRepository

/**
 * The phase 1 placeholder. Row counts from the seeded tables and the staleness query run
 * against test data — enough to show that the app launches, the schema created, the seed
 * rows landed and the Session screen's query returns something sensible.
 *
 * Deliberately plain. The Session screen is the next dispatch and owes nothing to this.
 */
@Composable
fun FoundationScreen(
    today: String,
    counts: List<Pair<String, Int>>,
    songs: List<SongbookRepository.StaleSong>,
) {
    MaterialTheme {
        Surface(modifier = Modifier.fillMaxSize()) {
            LazyColumn(
                modifier = Modifier.fillMaxSize().padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                item {
                    Text("Songbook", style = MaterialTheme.typography.headlineMedium)
                    Text(
                        "Phase 1 foundation — not the Session screen",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }

                item {
                    HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
                    Text("Row counts", style = MaterialTheme.typography.titleMedium)
                }

                items(counts) { (table, count) ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Text(table, fontFamily = FontFamily.Monospace)
                        Text(count.toString(), fontFamily = FontFamily.Monospace)
                    }
                }

                item {
                    HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
                    Text("Coldest first — guitar", style = MaterialTheme.typography.titleMedium)
                    Text("today is $today", style = MaterialTheme.typography.bodySmall)
                }

                items(songs) { song ->
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(song.title, style = MaterialTheme.typography.bodyLarge)
                            Text(
                                song.artistName ?: "unknown artist",
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                        Text(
                            text = song.daysSince?.let { "${it}d · ×${song.timesPractised}" }
                                ?: "never",
                            style = MaterialTheme.typography.bodyMedium,
                            fontFamily = FontFamily.Monospace,
                        )
                    }
                }
            }
        }
    }
}
