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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
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
    defaultOwner: String = DEFAULT_REPO_OWNER,
    defaultRepo: String = DEFAULT_REPO_NAME
) {

    sealed class UpdateState {
        object Idle : UpdateState()
        object Checking : UpdateState()
        object UpToDate : UpdateState()
        data class UpdateAvailable(
            val latestVersion: String,
            val downloadUrl: String,
            val releaseNotes: String
        ) : UpdateState()
        data class Downloading(val progressPercent: Int) : UpdateState()
        data class DownloadComplete(val apkFile: File) : UpdateState()
        data class Error(val message: String) : UpdateState()
    }

    companion object {
        const val DEFAULT_REPO_OWNER = "icarojose81"
        const val DEFAULT_REPO_NAME = "Launcher-tv-beta"
        private const val PREFS_NAME = "silvo_horizon_launcher_prefs"
        private const val KEY_REPO_OWNER = "updater_repo_owner"
        private const val KEY_REPO_NAME = "updater_repo_name"
    }

    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val updaterScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var progressJob: Job? = null

    var repoOwner: String = prefs.getString(KEY_REPO_OWNER, defaultOwner) ?: defaultOwner
        private set
    var repoName: String = prefs.getString(KEY_REPO_NAME, defaultRepo) ?: defaultRepo
        private set

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

    fun setRepository(owner: String, repo: String) {
        val cleanOwner = owner.trim()
        val cleanRepo = repo.trim()
        if (cleanOwner.isNotEmpty() && cleanRepo.isNotEmpty()) {
            repoOwner = cleanOwner
            repoName = cleanRepo
            prefs.edit()
                .putString(KEY_REPO_OWNER, cleanOwner)
                .putString(KEY_REPO_NAME, cleanRepo)
                .apply()
        }
    }

    fun getRepositorySlug(): String = "$repoOwner/$repoName"

    /**
     * Checks the public GitHub Releases API (latest release first, then all releases including pre-releases).
     */
    suspend fun checkForUpdates(): UpdateState = withContext(Dispatchers.IO) {
        _updateState.value = UpdateState.Checking
        val latestApiUrl = "https://api.github.com/repos/$repoOwner/$repoName/releases/latest"

        try {
            val latestResult = fetchUrl(latestApiUrl)
            val releaseJson: JSONObject? = when (latestResult.code) {
                HttpURLConnection.HTTP_OK -> JSONObject(latestResult.body)
                HttpURLConnection.HTTP_NOT_FOUND -> {
                    // Fallback: check /releases?per_page=1 in case only pre-releases/betas exist
                    val allReleasesUrl = "https://api.github.com/repos/$repoOwner/$repoName/releases?per_page=1"
                    val allResult = fetchUrl(allReleasesUrl)
                    if (allResult.code == HttpURLConnection.HTTP_OK) {
                        val array = JSONArray(allResult.body)
                        if (array.length() > 0) array.getJSONObject(0) else null
                    } else if (allResult.code == HttpURLConnection.HTTP_NOT_FOUND) {
                        val state = UpdateState.Error(
                            "Repositorio '$repoOwner/$repoName' no encontrado o privado"
                        )
                        _updateState.value = state
                        return@withContext state
                    } else {
                        val state = UpdateState.Error(
                            "Error HTTP ${allResult.code} al consultar Releases de GitHub"
                        )
                        _updateState.value = state
                        return@withContext state
                    }
                }
                else -> {
                    val state = UpdateState.Error(
                        "Error HTTP ${latestResult.code} al conectar con GitHub"
                    )
                    _updateState.value = state
                    return@withContext state
                }
            }

            if (releaseJson == null) {
                val state = UpdateState.Error(
                    "No hay Releases publicados en $repoOwner/$repoName (verifica la pestaña Releases en GitHub)"
                )
                _updateState.value = state
                return@withContext state
            }

            val tagName = releaseJson.optString("tag_name", "").trim()
            val releaseNotes = releaseJson.optString("body", "")
            val assets = releaseJson.optJSONArray("assets")

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

            val currentVersion = BuildConfig.VERSION_NAME
            val remoteClean = normalizeVersion(tagName)
            val currentClean = normalizeVersion(currentVersion)

            if (tagName.isEmpty()) {
                val state = UpdateState.Error("El Release en GitHub no tiene etiqueta (tag_name)")
                _updateState.value = state
                return@withContext state
            }

            if (isNewerVersion(remoteClean, currentClean)) {
                if (apkDownloadUrl.isNullOrEmpty()) {
                    val state = UpdateState.Error(
                        "Versión $tagName encontrada, pero sin archivo .apk adjunto en el Release"
                    )
                    _updateState.value = state
                    return@withContext state
                }

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
        } catch (e: Exception) {
            val state = UpdateState.Error(e.localizedMessage ?: "Error de conexión con GitHub")
            _updateState.value = state
            return@withContext state
        }
    }

    private data class HttpResponse(val code: Int, val body: String)

    private fun fetchUrl(apiUrl: String): HttpResponse {
        val url = URL(apiUrl)
        val connection = (url.openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 12000
            readTimeout = 12000
            setRequestProperty("Accept", "application/vnd.github.v3+json")
            setRequestProperty("User-Agent", "SilvoHorizonTVLauncher/${BuildConfig.VERSION_NAME}")
        }
        return try {
            val code = connection.responseCode
            val stream = if (code in 200..299) connection.inputStream else connection.errorStream
            val body = stream?.let {
                BufferedReader(InputStreamReader(it)).use { reader -> reader.readText() }
            }.orEmpty()
            HttpResponse(code, body)
        } finally {
            connection.disconnect()
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
        val destinationFile =
            File(context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS), fileName)
        if (destinationFile.exists()) {
            destinationFile.delete()
        }
        downloadedApkFile = destinationFile

        try {
            val request = DownloadManager.Request(Uri.parse(downloadUrl)).apply {
                setTitle("Silvo Horizon $versionTag")
                setDescription("Descargando actualización en segundo plano...")
                addRequestHeader("Accept", "application/octet-stream")
                addRequestHeader("User-Agent", "SilvoHorizonTVLauncher")
                setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                setDestinationUri(Uri.fromFile(destinationFile))
                setAllowedOverMetered(true)
                setAllowedOverRoaming(true)
            }

            currentDownloadId = downloadManager.enqueue(request)
            _updateState.value = UpdateState.Downloading(0)
            startProgressPolling(downloadManager, currentDownloadId)
        } catch (e: Exception) {
            _updateState.value = UpdateState.Error(e.localizedMessage ?: "Error al iniciar descarga")
        }
    }

    private fun startProgressPolling(downloadManager: DownloadManager, downloadId: Long) {
        progressJob?.cancel()
        progressJob = updaterScope.launch {
            while (isActive) {
                delay(300L)
                val query = DownloadManager.Query().setFilterById(downloadId)
                val cursor = downloadManager.query(query)
                var stillRunning = false
                if (cursor != null) {
                    cursor.use { c ->
                        if (c.moveToFirst()) {
                            val statusIdx = c.getColumnIndex(DownloadManager.COLUMN_STATUS)
                            val bytesIdx = c.getColumnIndex(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR)
                            val totalIdx = c.getColumnIndex(DownloadManager.COLUMN_TOTAL_SIZE_BYTES)
                            val status = if (statusIdx != -1) c.getInt(statusIdx) else -1

                            if (status == DownloadManager.STATUS_RUNNING || status == DownloadManager.STATUS_PENDING) {
                                stillRunning = true
                                val downloaded = if (bytesIdx != -1) c.getLong(bytesIdx) else 0L
                                val total = if (totalIdx != -1) c.getLong(totalIdx) else -1L
                                val percent = if (total > 0L) {
                                    ((downloaded * 100L) / total).toInt().coerceIn(0, 99)
                                } else {
                                    0
                                }
                                _updateState.value = UpdateState.Downloading(percent)
                            }
                        }
                    }
                }
                if (!stillRunning) break
            }
        }
    }

    private fun handleDownloadCompleted(downloadId: Long) {
        progressJob?.cancel()
        val downloadManager = context.getSystemService(Context.DOWNLOAD_SERVICE) as? DownloadManager
            ?: return

        val query = DownloadManager.Query().setFilterById(downloadId)
        val cursor = downloadManager.query(query)
        if (cursor != null) {
            cursor.use { c ->
                if (c.moveToFirst()) {
                    val statusIndex = c.getColumnIndex(DownloadManager.COLUMN_STATUS)
                    val status = if (statusIndex != -1) c.getInt(statusIndex) else -1
                    if (status == DownloadManager.STATUS_SUCCESSFUL) {
                        val apkFile = downloadedApkFile
                        if (apkFile != null && apkFile.exists() && apkFile.length() > 0L) {
                            _updateState.value = UpdateState.DownloadComplete(apkFile)
                            installApk(apkFile)
                        } else {
                            _updateState.value = UpdateState.Error("Archivo APK inválido o vacío tras descarga")
                        }
                    } else {
                        val reasonIdx = c.getColumnIndex(DownloadManager.COLUMN_REASON)
                        val reason = if (reasonIdx != -1) c.getInt(reasonIdx) else -1
                        _updateState.value = UpdateState.Error("La descarga del APK falló (código $reason)")
                    }
                }
            }
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
        progressJob?.cancel()
        if (downloadReceiverRegistered) {
            try {
                context.unregisterReceiver(downloadReceiver)
            } catch (_: Exception) {
                // Ignore if not registered
            }
            downloadReceiverRegistered = false
        }
    }

    private fun normalizeVersion(version: String): String {
        return version.trim()
            .removePrefix("v")
            .removePrefix("V")
            .substringBefore("-")
            .trim()
    }

    /**
     * Semantic version comparator. Returns true if remote is strictly greater than local.
     */
    fun isNewerVersion(remote: String, local: String): Boolean {
        val remoteClean = normalizeVersion(remote)
        val localClean = normalizeVersion(local)
        if (remoteClean.isEmpty()) return false
        return try {
            val remoteParts = remoteClean.split(".").map { it.filter { ch -> ch.isDigit() }.toIntOrNull() ?: 0 }
            val localParts = localClean.split(".").map { it.filter { ch -> ch.isDigit() }.toIntOrNull() ?: 0 }
            val maxLen = maxOf(remoteParts.size, localParts.size)

            for (i in 0 until maxLen) {
                val r = remoteParts.getOrElse(i) { 0 }
                val l = localParts.getOrElse(i) { 0 }
                if (r > l) return true
                if (r < l) return false
            }
            false
        } catch (_: Exception) {
            remoteClean != localClean
        }
    }
}
