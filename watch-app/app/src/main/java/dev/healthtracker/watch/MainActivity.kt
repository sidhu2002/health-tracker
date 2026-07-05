package dev.healthtracker.watch

import android.Manifest
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.wear.compose.material.Button
import androidx.wear.compose.material.Chip
import androidx.wear.compose.material.ChipDefaults
import androidx.wear.compose.material.MaterialTheme
import androidx.wear.compose.material.Text
import androidx.wear.compose.foundation.lazy.ScalingLazyColumn
import androidx.wear.compose.foundation.lazy.rememberScalingLazyListState
import androidx.wear.compose.foundation.lazy.AutoCenteringParams
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.graphics.Brush
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.WorkManager
import kotlinx.coroutines.launch
import java.util.concurrent.TimeUnit

class MainActivity : ComponentActivity() {

    private val requestBodySensors =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            statusUpdate = if (granted) "BODY_SENSORS granted" else "BODY_SENSORS denied"
        }

    private val requestBackground =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            statusUpdate = if (granted) "Background granted" else "Background denied"
        }

    private val requestActivity =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            statusUpdate = if (granted) "ACTIVITY granted" else "ACTIVITY denied"
        }

    private var statusUpdate: String = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Schedule the uploader once. WorkManager de-dupes across launches.
        UploadWorker.schedule(applicationContext)

        setContent {
            MaterialTheme {
                var status by remember { mutableStateOf("Ready to track") }
                var lastUpload by remember { mutableStateOf("—") }
                
                val listState = rememberScalingLazyListState()

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
                                text = "HealthTracker OTA",
                                color = Color(0xFF00E676),
                                style = MaterialTheme.typography.title2,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.padding(bottom = 4.dp)
                            )
                        }
                        item {
                            Text(
                                text = status,
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
                                    requestBodySensors.launch(Manifest.permission.BODY_SENSORS)
                                    status = "Requesting sensors..."
                                },
                                label = { Text("1. Sensors") },
                                colors = ChipDefaults.primaryChipColors(backgroundColor = Color(0xFF1E88E5)),
                                modifier = Modifier.fillMaxWidth(0.9f)
                            )
                        }
                        item {
                            Chip(
                                onClick = {
                                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                                        requestBackground.launch("android.permission.BODY_SENSORS_BACKGROUND")
                                        status = "Requesting background..."
                                    } else {
                                        status = "Background sync ready"
                                    }
                                },
                                label = { Text("2. Background") },
                                colors = ChipDefaults.primaryChipColors(backgroundColor = Color(0xFF1E88E5)),
                                modifier = Modifier.fillMaxWidth(0.9f)
                            )
                        }
                        item {
                            Chip(
                                onClick = {
                                    requestActivity.launch(Manifest.permission.ACTIVITY_RECOGNITION)
                                    status = "Requesting activity..."
                                },
                                label = { Text("3. Activity") },
                                colors = ChipDefaults.primaryChipColors(backgroundColor = Color(0xFF1E88E5)),
                                modifier = Modifier.fillMaxWidth(0.9f)
                            )
                        }
                        item {
                            Chip(
                                onClick = {
                                    lifecycleScope.launch {
                                        status = "Starting listener..."
                                        try {
                                            HealthCollector.registerPassive(applicationContext)
                                            status = "Tracking Active"
                                        } catch (e: Exception) {
                                            status = "Error: ${e.message}"
                                        }
                                    }
                                },
                                label = { Text("Start Tracking") },
                                colors = ChipDefaults.primaryChipColors(backgroundColor = Color(0xFF00C853)),
                                modifier = Modifier.fillMaxWidth(0.9f)
                            )
                        }
                        item {
                            Chip(
                                onClick = {
                                    lifecycleScope.launch {
                                        status = "Syncing now..."
                                        val ok = UploadWorker.runNow(applicationContext)
                                        status = if (ok) "Sync queued!" else "Sync failed"
                                        lastUpload = "Just now"
                                    }
                                },
                                label = { Text("Force Sync") },
                                colors = ChipDefaults.secondaryChipColors(),
                                modifier = Modifier.fillMaxWidth(0.9f)
                            )
                        }
                        item {
                            Chip(
                                onClick = {
                                    lifecycleScope.launch {
                                        status = "Checking for update..."
                                        val result = OtaUpdater.checkAndUpdate(applicationContext)
                                        status = result
                                    }
                                },
                                label = { Text("Update App (OTA)", fontWeight = FontWeight.Medium) },
                                colors = ChipDefaults.primaryChipColors(backgroundColor = Color(0xFFD84315)),
                                modifier = Modifier.fillMaxWidth(0.9f).padding(bottom = 8.dp)
                            )
                        }
                        item {
                            Text(
                                text = "Version: 1.1.0-OTA",
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
}
