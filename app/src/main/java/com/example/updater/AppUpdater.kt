package com.example.updater

import android.app.DownloadManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.Settings
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import com.example.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL

/**
 * Robust in-app GitHub Releases auto-updater for Android TV without requiring web browsers.
 */
class AppUpdater(
    private val context: Context,
    private val repoOwner: String = "icarojose81",
    private val repoName: String = "silvo-horizon-tv"
) {

    sealed class UpdateState {
        object Idle : UpdateState()
        object Checking : UpdateState()
        object UpToDate : UpdateState()
        data class UpdateAvailable(val latestVersion: String, val downloadUrl: String, val releaseNotes: String) : UpdateState()
        data class Downloading(val progressPercent: Int) : UpdateState()
        data class DownloadComplete(val apkFile: File) : UpdateState()
        data class Error(val message: String) : UpdateState()
    }

    private val _updateState = MutableStateFlow<UpdateState>(UpdateState.Idle)
    val updateState: StateFlow<UpdateState> = _updateState.asStateFlow()

    private var currentDownloadId: Long = -1L
    private var downloadedApkFile: File? = null
    private var downloadReceiverRegistered = false

    private val downloadReceiver = object : BroadcastReceiver() {
        override fun onReceive(recvContext: Context?, intent: Intent?) {
            if (intent?.action == DownloadManager.ACTION_DOWNLOAD_COMPLETE) {
                val id = intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1L)
                if (id != -1L && id == currentDownloadId) {
                    handleDownloadCompleted(id)
                }
            }
        }
    }

    /**
     * Checks the public GitHub Releases API for new releases.
     */
    suspend fun checkForUpdates(): UpdateState = withContext(Dispatchers.IO) {
        _updateState.value = UpdateState.Checking
        val apiUrl = "https://api.github.com/repos/$repoOwner/$repoName/releases/latest"

        try {
            val url = URL(apiUrl)
            val connection = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 10000
                readTimeout = 10000
                setRequestProperty("Accept", "application/vnd.github.v3+json")
                setRequestProperty("User-Agent", "SilvoHorizonTVLauncher")
            }

            val responseCode = connection.responseCode
            if (responseCode == HttpURLConnection.HTTP_OK) {
                val reader = BufferedReader(InputStreamReader(connection.inputStream))
                val response = reader.readText()
                reader.close()

                val json = JSONObject(response)
                val tagName = json.optString("tag_name", "")
                val releaseNotes = json.optString("body", "")
                val assets = json.optJSONArray("assets")

                var apkDownloadUrl: String? = null
                if (assets != null) {
                    for (i in 0 until assets.length()) {
                        val asset = assets.getJSONObject(i)
                        val name = asset.optString("name", "")
                        if (name.endsWith(".apk", ignoreCase = true)) {
                            apkDownloadUrl = asset.optString("browser_download_url")
                            break
                        }
                    }
                }

                // If no direct APK asset found, check html_url or direct download
                if (apkDownloadUrl == null) {
                    apkDownloadUrl = json.optString("html_url")
                }

                val currentVersion = BuildConfig.VERSION_NAME
                val remoteClean = tagName.removePrefix("v").trim()
                val currentClean = currentVersion.removePrefix("v").trim()

                if (isNewerVersion(remoteClean, currentClean) && !apkDownloadUrl.isNullOrEmpty()) {
                    val state = UpdateState.UpdateAvailable(
                        latestVersion = tagName,
                        downloadUrl = apkDownloadUrl,
                        releaseNotes = releaseNotes
                    )
                    _updateState.value = state
                    return@withContext state
                } else {
                    _updateState.value = UpdateState.UpToDate
                    return@withContext UpdateState.UpToDate
                }
            } else if (responseCode == HttpURLConnection.HTTP_NOT_FOUND) {
                val state = UpdateState.UpToDate
                _updateState.value = state
                return@withContext state
            } else {
                val state = UpdateState.Error("Error HTTP $responseCode al conectar con GitHub")
                _updateState.value = state
                return@withContext state
            }
        } catch (e: Exception) {
            val state = UpdateState.Error(e.localizedMessage ?: "Error de conexión")
            _updateState.value = state
            return@withContext state
        }
    }

    /**
     * Downloads the APK file in the background using Android system DownloadManager.
     */
    fun startDownload(downloadUrl: String, versionTag: String) {
        val downloadManager = context.getSystemService(Context.DOWNLOAD_SERVICE) as? DownloadManager
        if (downloadManager == null) {
            _updateState.value = UpdateState.Error("DownloadManager no disponible")
            return
        }

        registerDownloadReceiver()

        val cleanTag = versionTag.replace("[^a-zA-Z0-9.-]".toRegex(), "_")
        val fileName = "silvo-horizon-$cleanTag.apk"
        val destinationFile = File(context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS), fileName)
        if (destinationFile.exists()) {
            destinationFile.delete()
        }
        downloadedApkFile = destinationFile

        try {
            val request = DownloadManager.Request(Uri.parse(downloadUrl)).apply {
                setTitle("Silvo Horizon $versionTag")
                setDescription("Descargando actualización en segundo plano...")
                setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                setDestinationUri(Uri.fromFile(destinationFile))
                setAllowedOverMetered(true)
                setAllowedOverRoaming(true)
            }

            currentDownloadId = downloadManager.enqueue(request)
            _updateState.value = UpdateState.Downloading(0)
        } catch (e: Exception) {
            _updateState.value = UpdateState.Error(e.localizedMessage ?: "Error al iniciar descarga")
        }
    }

    private fun handleDownloadCompleted(downloadId: Long) {
        val downloadManager = context.getSystemService(Context.DOWNLOAD_SERVICE) as? DownloadManager
            ?: return

        val query = DownloadManager.Query().setFilterById(downloadId)
        val cursor = downloadManager.query(query)
        if (cursor != null && cursor.moveToFirst()) {
            val statusIndex = cursor.getColumnIndex(DownloadManager.COLUMN_STATUS)
            if (statusIndex != -1) {
                val status = cursor.getInt(statusIndex)
                if (status == DownloadManager.STATUS_SUCCESSFUL) {
                    val apkFile = downloadedApkFile
                    if (apkFile != null && apkFile.exists()) {
                        _updateState.value = UpdateState.DownloadComplete(apkFile)
                        installApk(apkFile)
                    } else {
                        _updateState.value = UpdateState.Error("Archivo APK no encontrado tras descarga")
                    }
                } else {
                    _updateState.value = UpdateState.Error("La descarga del APK falló o fue cancelada")
                }
            }
            cursor.close()
        }
    }

    /**
     * Prompts the native package installer to install the downloaded APK via FileProvider.
     */
    fun installApk(apkFile: File) {
        if (!apkFile.exists()) {
            _updateState.value = UpdateState.Error("Archivo APK no existe")
            return
        }

        // On Android 8.0+ (API 26+), verify if install permission is granted
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            if (!context.packageManager.canRequestPackageInstalls()) {
                val permissionIntent = Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES).apply {
                    data = Uri.parse("package:${context.packageName}")
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(permissionIntent)
                return
            }
        }

        try {
            val apkUri = FileProvider.getUriForFile(
                context,
                "${context.packageName}.fileprovider",
                apkFile
            )

            val installIntent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(apkUri, "application/vnd.android.package-archive")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }

            context.startActivity(installIntent)
        } catch (e: Exception) {
            _updateState.value = UpdateState.Error("Error al iniciar instalación: ${e.message}")
        }
    }

    private fun registerDownloadReceiver() {
        if (!downloadReceiverRegistered) {
            val filter = IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE)
            ContextCompat.registerReceiver(
                context,
                downloadReceiver,
                filter,
                ContextCompat.RECEIVER_NOT_EXPORTED
            )
            downloadReceiverRegistered = true
        }
    }

    fun unregister() {
        if (downloadReceiverRegistered) {
            try {
                context.unregisterReceiver(downloadReceiver)
            } catch (e: Exception) {
                // Ignore if not registered
            }
            downloadReceiverRegistered = false
        }
    }

    /**
     * Semantic version comparator. Returns true if remote is strictly greater than local.
     */
    private fun isNewerVersion(remote: String, local: String): Boolean {
        try {
            val remoteParts = remote.split(".").map { it.filter { ch -> ch.isDigit() }.toIntOrNull() ?: 0 }
            val localParts = local.split(".").map { it.filter { ch -> ch.isDigit() }.toIntOrNull() ?: 0 }
            val maxLen = maxOf(remoteParts.size, localParts.size)

            for (i in 0 until maxLen) {
                val r = remoteParts.getOrElse(i) { 0 }
                val l = localParts.getOrElse(i) { 0 }
                if (r > l) return true
                if (r < l) return false
            }
        } catch (e: Exception) {
            return remote != local
        }
        return false
    }
}
