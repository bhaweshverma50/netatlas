package atlas.netatlas

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.core.content.ContextCompat
import androidx.room.Room
import atlas.map.HexRepository
import atlas.map.MapViewModel
import atlas.net.defaultApiClient
import atlas.netatlas.collect.CollectorService
import atlas.netatlas.collect.db.CollectorDb
import atlas.netatlas.collect.upload.UploadWorker
import atlas.netatlas.map.HeatmapScreen
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

class MainActivity : ComponentActivity() {

    // A read-only handle on the same Room DB the collector writes to, for the live
    // "Your contributions: N" count. Opened lazily; closed in onDestroy.
    private val db by lazy {
        Room.databaseBuilder(applicationContext, CollectorDb::class.java, UploadWorker.DB_NAME).build()
    }

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
            val locationGranted = result[Manifest.permission.ACCESS_FINE_LOCATION] == true ||
                result[Manifest.permission.ACCESS_COARSE_LOCATION] == true
            val phoneGranted = result[Manifest.permission.READ_PHONE_STATE] == true
            if (locationGranted && phoneGranted) {
                CollectorService.start(this)
                Toast.makeText(
                    this,
                    "Collecting. For best results enable \"Allow all the time\" in Settings.",
                    Toast.LENGTH_LONG,
                ).show()
            } else {
                Toast.makeText(
                    this,
                    "Location and phone permissions are required to map coverage.",
                    Toast.LENGTH_LONG,
                ).show()
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val settings = Settings(this)
        setContent {
            MaterialTheme {
                // The base URL is user-configurable (10.0.2.2 only works on the emulator).
                // Hold it in state; the ApiClient reads it per request via the provider below,
                // so changing it re-points the heatmap + uploads without rebuilding the VM/map
                // (which would otherwise lose the current bounding box).
                var baseUrl by rememberSaveable { mutableStateOf(settings.baseUrl) }

                val viewModel = remember {
                    MapViewModel(
                        repo = HexRepository(defaultApiClient { baseUrl }),
                        scope = CoroutineScope(SupervisorJob() + Dispatchers.Main),
                    )
                }

                Scaffold { innerPadding ->
                    HeatmapScreen(
                        viewModel = viewModel,
                        contributionsFlow = db.readingDao().countFlow(),
                        onStartCollection = { requestPermissionsThenStart() },
                        onStopCollection = { CollectorService.stop(this) },
                        serverUrl = baseUrl,
                        onServerUrlChange = { newUrl ->
                            settings.baseUrl = newUrl     // persists the normalized value
                            baseUrl = settings.baseUrl    // provider now resolves the new server
                            viewModel.refresh()           // reload the heatmap for the new server
                            viewModel.loadCarriers()      // and refresh the carrier filter list
                        },
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(innerPadding),
                    )
                }
            }
        }
    }

    /** If the runtime permissions are already granted, start immediately; otherwise request them. */
    private fun requestPermissionsThenStart() {
        if (hasCollectionPermissions()) {
            CollectorService.start(this)
            return
        }
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

    private fun hasCollectionPermissions(): Boolean {
        fun granted(p: String) =
            ContextCompat.checkSelfPermission(this, p) == PackageManager.PERMISSION_GRANTED
        val location = granted(Manifest.permission.ACCESS_FINE_LOCATION) ||
            granted(Manifest.permission.ACCESS_COARSE_LOCATION)
        return location && granted(Manifest.permission.READ_PHONE_STATE)
    }

    override fun onDestroy() {
        super.onDestroy()
        if (isFinishing) db.close()
    }
}
