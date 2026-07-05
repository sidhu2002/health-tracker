package dev.healthtracker.watch

import android.content.Context
import android.util.Log
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

class UploadWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {

    private val http = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val batch = SampleBuffer.drainAll()
        if (batch.isEmpty()) {
            Log.d(TAG, "no samples to upload")
            return@withContext Result.success()
        }

        val body = JSONObject().apply {
            put("device_id", Config.DEVICE_ID)
            put("samples", JSONArray().apply {
                batch.forEach { s ->
                    put(JSONObject().apply {
                        put("metric", s.metric)
                        put("ts", s.ts)
                        put("value", s.value)
                        put("unit", s.unit)
                    })
                }
            })
        }

        val req = Request.Builder()
            .url("${Config.BACKEND_URL}/v1/ingest")
            .addHeader("Authorization", "Bearer ${Config.WATCH_TOKEN}")
            .addHeader("Content-Type", "application/json")
            .post(body.toString().toRequestBody("application/json".toMediaType()))
            .build()

        try {
            http.newCall(req).execute().use { resp ->
                if (resp.isSuccessful) {
                    Log.i(TAG, "uploaded ${batch.size} samples: HTTP ${resp.code}")
                    Result.success()
                } else {
                    Log.w(TAG, "upload rejected: HTTP ${resp.code}")
                    // Re-queue for next attempt so we don't lose data.
                    SampleBuffer.enqueueAll(batch)
                    Result.retry()
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "upload error", e)
            SampleBuffer.enqueueAll(batch)
            Result.retry()
        }
    }

    companion object {
        private const val TAG = "UploadWorker"
        private const val NAME = "health-upload"

        fun schedule(context: Context) {
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build()

            // Calculate delay until next 2:00 AM for nightly sync
            val now = java.util.Calendar.getInstance()
            val target = java.util.Calendar.getInstance().apply {
                set(java.util.Calendar.HOUR_OF_DAY, 2)
                set(java.util.Calendar.MINUTE, 0)
                set(java.util.Calendar.SECOND, 0)
                set(java.util.Calendar.MILLISECOND, 0)
            }
            if (now.after(target)) {
                target.add(java.util.Calendar.DAY_OF_MONTH, 1)
            }
            val delayMs = target.timeInMillis - now.timeInMillis

            val req = PeriodicWorkRequestBuilder<UploadWorker>(24, TimeUnit.HOURS)
                .setConstraints(constraints)
                .setInitialDelay(delayMs, TimeUnit.MILLISECONDS)
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
                .build()

            WorkManager.getInstance(context)
                .enqueueUniquePeriodicWork(NAME, ExistingPeriodicWorkPolicy.UPDATE, req)
        }

        /**
         * Fires a one-shot upload immediately. Called from the "Sync now" UI button.
         * Returns true if WorkManager accepted the request (not upload success).
         */
        suspend fun runNow(context: Context): Boolean {
            return try {
                val req = OneTimeWorkRequestBuilder<UploadWorker>()
                    .setConstraints(
                        Constraints.Builder()
                            .setRequiredNetworkType(NetworkType.CONNECTED)
                            .build()
                    )
                    .build()
                WorkManager.getInstance(context).enqueue(req)
                true
            } catch (e: Exception) {
                Log.e(TAG, "runNow failed", e)
                false
            }
        }
    }
}
