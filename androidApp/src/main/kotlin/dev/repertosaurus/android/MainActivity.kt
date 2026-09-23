package dev.repertosaurus.android

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.compose.foundation.layout.fillMaxSize
import androidx.lifecycle.viewmodel.compose.viewModel

/**
 * One Activity. Routing is a `when` inside [RepertosaurusApp]. The song detail arrived without a
 * navigation library: it is the Songs route's own state (repertoire-editing R27, E25 declined).
 *
 * Nothing is opened here: the database lives in [AppGraph] for the life of the process, so
 * a rotation does not reopen SQLite and an import can close and swap the file underneath.
 */
public class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val graph = AppGraph.of(applicationContext)

        setContent {
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    val model: SessionViewModel =
                        viewModel(factory = SessionViewModel.factory(graph))
                    // R28: one ViewModel per repertoire-editing route. None reads anything in
                    // its constructor — each loads when its screen is entered — so building all
                    // three up front costs nothing on the boot path.
                    val repertoire: RepertoireViewModel =
                        viewModel(factory = RepertoireViewModel.factory(graph))
                    val songs: SongsViewModel = viewModel(factory = SongsViewModel.factory(graph))
                    val artists: ArtistsViewModel =
                        viewModel(factory = ArtistsViewModel.factory(graph))
                    val settings: DeviceSettings = viewModel(factory = DeviceSettings.factory(graph))
                    RepertosaurusApp(model, repertoire, songs, artists, settings)
                }
            }
        }
    }
}
