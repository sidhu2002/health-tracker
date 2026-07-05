package dev.healthtracker.watch

import android.content.Context
import android.util.Log
import androidx.health.services.client.HealthServices
import androidx.health.services.client.PassiveListenerCallback
import androidx.health.services.client.PassiveMonitoringClient
import androidx.health.services.client.data.DataPointContainer
import androidx.health.services.client.data.DataType
import androidx.health.services.client.data.IntervalDataPoint
import androidx.health.services.client.data.PassiveListenerConfig
import androidx.health.services.client.data.SampleDataPoint
import kotlinx.coroutines.guava.await
import java.time.Instant

/**
 * Wires up the PassiveMonitoringClient to receive health samples in the background.
 *
 * Samples are buffered into SampleBuffer. UploadWorker drains that buffer and POSTs to
 * the backend on its own schedule.
 */
object HealthCollector {

    private const val TAG = "HealthCollector"

    private val dataTypes = setOf(
        DataType.HEART_RATE_BPM,
        DataType.STEPS_DAILY,
        DataType.CALORIES_DAILY,
        DataType.DISTANCE_DAILY,
        DataType.FLOORS_DAILY,
        DataType.ELEVATION_GAIN_DAILY
    )

    private val callback = object : PassiveListenerCallback {
        override fun onNewDataPointsReceived(dataPoints: DataPointContainer) {
            // Use wall-clock receive time. Health Services stamps samples using SystemClock
            // elapsed-nanos which requires an accurate "boot instant" to translate — the
            // emulator often gets that wrong. For a personal tracker, receive-time is fine
            // (samples arrive within a second of being taken anyway).
            val now = System.currentTimeMillis()
            val samples = mutableListOf<BufferedSample>()

            for (dp in dataPoints.getData(DataType.HEART_RATE_BPM)) {
                samples += BufferedSample("heart_rate", now, dp.value, "bpm")
            }
            for (dp in dataPoints.getData(DataType.STEPS_DAILY)) {
                samples += BufferedSample("steps_daily", now, dp.value.toDouble(), "count")
            }
            for (dp in dataPoints.getData(DataType.CALORIES_DAILY)) {
                samples += BufferedSample("calories_daily", now, dp.value, "kcal")
            }
            for (dp in dataPoints.getData(DataType.DISTANCE_DAILY)) {
                samples += BufferedSample("distance_daily", now, dp.value, "m")
            }
            for (dp in dataPoints.getData(DataType.FLOORS_DAILY)) {
                samples += BufferedSample("floors_daily", now, dp.value, "count")
            }
            for (dp in dataPoints.getData(DataType.ELEVATION_GAIN_DAILY)) {
                samples += BufferedSample("elevation_gain_daily", now, dp.value, "m")
            }

            if (samples.isEmpty()) return
            Log.d(TAG, "buffering ${samples.size} samples")
            SampleBuffer.enqueueAll(samples)
        }
    }

    /**
     * Health Services SampleDataPoint exposes its timestamp as an Instant, but the getter
     * requires a "boot instant" reference to translate from the SystemClock elapsed-nanos
     * origin. Passing Instant.now() is close enough for our sub-second-not-required use case.
     */
    private fun SampleDataPoint<Double>.timeInEpochMillis(): Long {
        return try {
            this.getTimeInstant(Instant.now()).toEpochMilli()
        } catch (t: Throwable) {
            System.currentTimeMillis()
        }
    }

    private fun <T : Number> IntervalDataPoint<T>.endMillis(): Long {
        return try {
            this.getEndInstant(Instant.now()).toEpochMilli()
        } catch (t: Throwable) {
            System.currentTimeMillis()
        }
    }

    suspend fun registerPassive(context: Context) {
        val client: PassiveMonitoringClient = HealthServices.getClient(context).passiveMonitoringClient
        val caps = client.getCapabilitiesAsync().await()
        val supported = dataTypes.filter { it in caps.supportedDataTypesPassiveMonitoring }.toSet()
        Log.i(TAG, "registering passive listener for: $supported")
        if (supported.isEmpty()) {
            Log.w(TAG, "no supported passive data types on this device")
            return
        }
        val config = PassiveListenerConfig.builder()
            .setDataTypes(supported)
            .build()
        client.setPassiveListenerCallback(config, callback)
    }
}
