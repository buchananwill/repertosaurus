package dev.repertaurus.android

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.compose.foundation.layout.fillMaxSize
import androidx.lifecycle.viewmodel.compose.viewModel

/**
 * One Activity. Routing is a `when` inside [RepertaurusApp]; a navigation library arrives with
 * the library browser and song detail, neither of which is in phase 1.
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
                    RepertaurusApp(model)
                }
            }
        }
    }
}
