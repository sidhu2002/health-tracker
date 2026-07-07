package dev.healthtracker.watch

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.speech.RecognizerIntent
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
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

    private val speechLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == RESULT_OK) {
            val spokenText = result.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull()
            if (spokenText != null) {
                currentStatus = "Parsing: $spokenText..."
                lifecycleScope.launch(kotlinx.coroutines.Dispatchers.IO) {
                    try {
                        val ok = processSpeech(spokenText)
                        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                            currentStatus = if (ok) "Meal logged!" else "Failed to parse meal"
                        }
                    } catch (e: Exception) {
                        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                            currentStatus = "Error: ${e.message}"
                        }
                    }
                }
            }
        }
    }

    private fun promptSpeech() {
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_PROMPT, "What did you eat?")
        }
        speechLauncher.launch(intent)
    }

    private fun processSpeech(text: String): Boolean {
        val http = okhttp3.OkHttpClient()
        val mediaType = "application/json".toMediaType()
        val body = org.json.JSONObject().put("text", text).toString().toRequestBody(mediaType)
            
        val req1 = okhttp3.Request.Builder()
            .url("${Config.BACKEND_URL}/v1/ai/parse-food")
            .addHeader("Authorization", "Bearer ${Config.WATCH_TOKEN}")
            .post(body)
            .build()

        val res1 = http.newCall(req1).execute()
        if (!res1.isSuccessful) return false
        
        val aiJson = org.json.JSONObject(res1.body!!.string())
        if (!aiJson.optBoolean("ok")) return false
        
        val result = aiJson.getJSONObject("result")
        result.put("source", "watch_voice")
        result.put("logged_at", System.currentTimeMillis())
        
        val meta = org.json.JSONObject().put("explanation", result.optString("explanation", ""))
        result.put("meta", meta)

        val req2 = okhttp3.Request.Builder()
            .url("${Config.BACKEND_URL}/v1/food-logs")
            .addHeader("Authorization", "Bearer ${Config.WATCH_TOKEN}")
            .post(result.toString().toRequestBody(mediaType))
            .build()

        val res2 = http.newCall(req2).execute()
        return res2.isSuccessful
    }

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
                    // Initial load of last sync time
                    lastUpload = getLastSyncTime()
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
                                    promptSpeech()
                                    currentStatus = "Listening..."
                                },
                                label = { Text("Voice Log Food") },
                                colors = ChipDefaults.primaryChipColors(backgroundColor = Color(0xFF00796B)),
                                modifier = Modifier.fillMaxWidth(0.9f)
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
                                            if (result.startsWith("Error")) {
                                                android.util.Log.e("MainActivity", "OTA Check Error: $result")
                                            }
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

    private fun getLastSyncTime(): String {
        val prefs = getSharedPreferences("health_prefs", android.content.Context.MODE_PRIVATE)
        val time = prefs.getLong("last_sync_time", 0L)
        if (time == 0L) return "-"
        val format = java.text.SimpleDateFormat("MMM dd, HH:mm", java.util.Locale.getDefault())
        return format.format(java.util.Date(time))
    }
}