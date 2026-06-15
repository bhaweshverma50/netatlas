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

    // 10.0.2.2 is the host loopback as seen from the Android emulator.
    private val viewModel by lazy {
        MapViewModel(
            repo = HexRepository(defaultApiClient("http://10.0.2.2:8080")),
            scope = CoroutineScope(SupervisorJob() + Dispatchers.Main),
        )
    }

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
        setContent {
            MaterialTheme {
                Scaffold { innerPadding ->
                    HeatmapScreen(
                        viewModel = viewModel,
                        contributionsFlow = db.readingDao().countFlow(),
                        onStartCollection = { requestPermissionsThenStart() },
                        onStopCollection = { CollectorService.stop(this) },
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
