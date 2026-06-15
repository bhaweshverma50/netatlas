package atlas.netatlas

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import atlas.model.NetworkType

@Composable
fun App() {
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
            }
        }
    }
}
