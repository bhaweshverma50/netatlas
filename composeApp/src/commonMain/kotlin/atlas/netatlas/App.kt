package atlas.netatlas

import androidx.compose.foundation.layout.Arrangement
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
import atlas.model.NetworkType

@Composable
fun App(
    onStartCollection: (() -> Unit)? = null,
    onStopCollection: (() -> Unit)? = null,
) {
    MaterialTheme {
        Scaffold { innerPadding ->
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                Text(
                    text = "netatlas",
                    style = MaterialTheme.typography.headlineMedium,
                )
                // Proves the :shared module is linked: reads a type from atlas.model.
                Text(
                    text = "network types: " + NetworkType.entries.joinToString(),
                    style = MaterialTheme.typography.bodyMedium,
                )
                // M2.4 smoke-test hooks (Android wires these to CollectorService).
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
