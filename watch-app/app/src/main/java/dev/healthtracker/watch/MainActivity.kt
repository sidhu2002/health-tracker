package dev.healthtracker.watch

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.wear.compose.foundation.lazy.AutoCenteringParams
import androidx.wear.compose.foundation.lazy.ScalingLazyColumn
import androidx.wear.compose.foundation.lazy.rememberScalingLazyListState
import androidx.wear.compose.material.Chip
import androidx.wear.compose.material.ChipDefaults
import androidx.wear.compose.material.MaterialTheme
import androidx.wear.compose.material.Text
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    private var currentStatus by mutableStateOf("Initializing...")

    private val requestPermissionsLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { permissions ->
            val sensors = permissions[Manifest.permission.BODY_SENSORS] ?: false
            val activity = permissions[Manifest.permission.ACTIVITY_RECOGNITION] ?: false
            
            if (sensors && activity) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    val bg = ContextCompat.checkSelfPermission(this, "android.permission.BODY_SENSORS_BACKGROUND")
                    if (bg != PackageManager.PERMISSION_GRANTED) {
                        requestBgSensorLauncher.launch("android.permission.BODY_SENSORS_BACKGROUND")
                    } else {
                        startTracking()
                    }
                } else {
                    startTracking()
                }
            } else {
                currentStatus = "Permissions denied"
            }
        }

    private val requestBgSensorLauncher = 
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) {
                startTracking()
            } else {
                currentStatus = "BG Sensor denied"
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        UploadWorker.schedule(applicationContext)

        setContent {
            MaterialTheme {
                var lastUpload by remember { mutableStateOf("-") }
                var updateAvailable by remember { mutableStateOf(false) }
                
                val listState = rememberScalingLazyListState()

                LaunchedEffect(Unit) {
                    checkAndRequestPermissions()
                    // Check for updates conditionally
                    updateAvailable = OtaUpdater.isUpdateAvailable(applicationContext)
                }

                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(
                            Brush.radialGradient(
                                colors = listOf(Color(0xFF0F2027), Color(0xFF203A43), Color(0xFF2C5364))
                            )
                        )
                ) {
                    ScalingLazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        state = listState,
                        autoCentering = AutoCenteringParams(itemIndex = 0),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        item {
                            Text(
                                text = "HealthTracker 2.0",
                                color = Color(0xFF00BFFF),
                                style = MaterialTheme.typography.title2,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.padding(bottom = 4.dp)
                            )
                        }
                        item {
                            Text(
                                text = currentStatus,
                                color = Color.White,
                                style = MaterialTheme.typography.body2,
                                textAlign = TextAlign.Center,
                                modifier = Modifier.padding(horizontal = 16.dp)
                            )
                        }
                        item {
                            Text(
                                text = "Last Sync: $lastUpload",
                                color = Color.Gray,
                                style = MaterialTheme.typography.caption2,
                                modifier = Modifier.padding(bottom = 8.dp)
                            )
                        }
                        
                        item {
                            Chip(
                                onClick = {
                                    lifecycleScope.launch {
                                        currentStatus = "Syncing now..."
                                        val ok = UploadWorker.runNow(applicationContext)
                                        currentStatus = if (ok) "Sync queued!" else "Sync failed"
                                        lastUpload = "Just now"
                                    }
                                },
                                label = { Text("Force Sync") },
                                colors = ChipDefaults.secondaryChipColors(),
                                modifier = Modifier.fillMaxWidth(0.9f)
                            )
                        }
                        
                        if (updateAvailable) {
                            item {
                                Chip(
                                    onClick = {
                                        lifecycleScope.launch {
                                            currentStatus = "Downloading update..."
                                            val result = OtaUpdater.checkAndUpdate(applicationContext)
                                            currentStatus = result
                                        }
                                    },
                                    label = { Text("Update App (OTA)", fontWeight = FontWeight.Medium) },
                                    colors = ChipDefaults.primaryChipColors(backgroundColor = Color(0xFFD84315)),
                                    modifier = Modifier.fillMaxWidth(0.9f).padding(top = 8.dp, bottom = 8.dp)
                                )
                            }
                        }

                        item {
                            Text(
                                text = "Version: 2.0.0-OTA-Test",
                                color = Color.Gray,
                                style = MaterialTheme.typography.caption2,
                                modifier = Modifier.padding(bottom = 24.dp)
                            )
                        }
                    }
                }
            }
        }
    }

    private fun checkAndRequestPermissions() {
        val sensors = ContextCompat.checkSelfPermission(this, Manifest.permission.BODY_SENSORS) == PackageManager.PERMISSION_GRANTED
        val activity = ContextCompat.checkSelfPermission(this, Manifest.permission.ACTIVITY_RECOGNITION) == PackageManager.PERMISSION_GRANTED
        
        if (!sensors || !activity) {
            requestPermissionsLauncher.launch(arrayOf(Manifest.permission.BODY_SENSORS, Manifest.permission.ACTIVITY_RECOGNITION))
        } else {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                val bg = ContextCompat.checkSelfPermission(this, "android.permission.BODY_SENSORS_BACKGROUND") == PackageManager.PERMISSION_GRANTED
                if (!bg) {
                    requestBgSensorLauncher.launch("android.permission.BODY_SENSORS_BACKGROUND")
                } else {
                    startTracking()
                }
            } else {
                startTracking()
            }
        }
    }

    private fun startTracking() {
        lifecycleScope.launch {
            currentStatus = "Starting listener..."
            try {
                HealthCollector.registerPassive(applicationContext)
                currentStatus = "Tracking Active"
            } catch (e: Exception) {
                currentStatus = "Error: ${e.message}"
            }
        }
    }
}