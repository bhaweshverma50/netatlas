package atlas.netatlas

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier

/**
 * Root UI for non-Android targets. The Android app drives the real coverage heatmap directly
 * from `MainActivity` (see `atlas.netatlas.map.HeatmapScreen`); this commonMain entry is a
 * simple placeholder so the multiplatform module still has a shared composable to grow into.
 */
@Composable
fun App() {
    MaterialTheme {
        Scaffold { innerPadding ->
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding),
            ) {
                Text(
                    text = "netatlas",
                    modifier = Modifier.align(Alignment.Center),
                    style = MaterialTheme.typography.headlineMedium,
                )
            }
        }
    }
}
