package dev.healthtracker.watch

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream

object OtaUpdater {
    private const val TAG = "OtaUpdater"
    private const val GITHUB_REPO = "sidhu2002/health-tracker"

    suspend fun checkAndUpdate(context: Context): String = withContext(Dispatchers.IO) {
        try {
            val client = OkHttpClient()
            
            // 1. Fetch latest release from GitHub API
            Log.d(TAG, "Checking for updates...")
            val apiUrl = "https://api.github.com/repos/$GITHUB_REPO/releases/latest"
            val request = Request.Builder().url(apiUrl).build()
            
            val response = client.newCall(request).execute()
            if (!response.isSuccessful) return@withContext "No updates found (or private repo)."
            
            val json = JSONObject(response.body?.string() ?: "")
            val assets = json.getJSONArray("assets")
            if (assets.length() == 0) return@withContext "No APK attached to latest release."
            
            val apkAsset = assets.getJSONObject(0)
            val downloadUrl = apkAsset.getString("browser_download_url")
            val fileName = apkAsset.getString("name")
            
            Log.d(TAG, "Downloading $fileName from $downloadUrl")
            
            // 2. Download the APK
            val downloadRequest = Request.Builder().url(downloadUrl).build()
            val downloadResponse = client.newCall(downloadRequest).execute()
            
            if (!downloadResponse.isSuccessful) return@withContext "Failed to download APK."
            
            val apkFile = File(context.externalCacheDir, "update.apk")
            if (apkFile.exists()) apkFile.delete()
            
            val inputStream = downloadResponse.body?.byteStream()
            val outputStream = FileOutputStream(apkFile)
            
            inputStream?.use { input ->
                outputStream.use { output ->
                    input.copyTo(output)
                }
            }
            
            Log.d(TAG, "APK downloaded to ${apkFile.absolutePath}")
            
            // 3. Trigger Installation
            withContext(Dispatchers.Main) {
                val uri: Uri = FileProvider.getUriForFile(
                    context,
                    "${context.packageName}.fileprovider",
                    apkFile
                )
                
                val intent = Intent(Intent.ACTION_VIEW).apply {
                    setDataAndType(uri, "application/vnd.android.package-archive")
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION
                }
                
                context.startActivity(intent)
            }
            
            return@withContext "Starting update..."
        } catch (e: Exception) {
            Log.e(TAG, "Update failed", e)
            return@withContext "Error: ${e.localizedMessage}"
        }
    }
}
