package atlas.netatlas

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * Root UI. The [mapContent] slot hosts the platform map (MapLibre on Android); the
 * collection Start/Stop controls float in a top overlay until proper map controls land in
 * M3.3. When no map is supplied (e.g. a non-Android target) a simple placeholder is shown.
 */
@Composable
fun App(
    onStartCollection: (() -> Unit)? = null,
    onStopCollection: (() -> Unit)? = null,
    mapContent: (@Composable (Modifier) -> Unit)? = null,
) {
    MaterialTheme {
        Scaffold { innerPadding ->
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding),
            ) {
                if (mapContent != null) {
                    mapContent(Modifier.fillMaxSize())
                } else {
                    Text(
                        text = "netatlas",
                        modifier = Modifier.align(Alignment.Center),
                        style = MaterialTheme.typography.headlineMedium,
                    )
                }

                // M2.4 collection hooks float above the map (temporary controls; M3.3 replaces these).
                Column(
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .padding(12.dp),
                    horizontalAlignment = Alignment.Start,
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    if (onStartCollection != null) {
                        Button(onClick = onStartCollection) { Text("Start collection") }
                    }
                    if (onStopCollection != null) {
                        Button(onClick = onStopCollection) { Text("Stop collection") }
                    }
                }
            }
        }
    }
}
