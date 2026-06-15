package atlas.netatlas

import android.Manifest
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import atlas.map.HexRepository
import atlas.map.MapViewModel
import atlas.net.defaultApiClient
import atlas.netatlas.collect.CollectorService
import atlas.netatlas.map.MapScreen
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

class MainActivity : ComponentActivity() {

    // 10.0.2.2 is the host loopback as seen from the Android emulator.
    private val viewModel by lazy {
        MapViewModel(
            repo = HexRepository(defaultApiClient("http://10.0.2.2:8080")),
            scope = CoroutineScope(SupervisorJob() + Dispatchers.Main),
        )
    }

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
            // Result handled implicitly; the service tolerates missing permissions gracefully.
            CollectorService.start(this)
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            val geoJson by viewModel.geoJson.collectAsState()
            App(
                onStartCollection = { requestPermissionsThenStart() },
                onStopCollection = { CollectorService.stop(this) },
                mapContent = { modifier ->
                    MapScreen(
                        geoJson = geoJson,
                        onBoundsChanged = viewModel::setBoundingBox,
                        modifier = modifier,
                    )
                },
            )
        }
    }

    private fun requestPermissionsThenStart() {
        val perms = buildList {
            add(Manifest.permission.ACCESS_FINE_LOCATION)
            add(Manifest.permission.ACCESS_COARSE_LOCATION)
            add(Manifest.permission.READ_PHONE_STATE)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                add(Manifest.permission.POST_NOTIFICATIONS)
            }
        }.toTypedArray()
        permissionLauncher.launch(perms)
    }
}
