package com.example.data.repository

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.content.pm.ResolveInfo
import androidx.core.content.ContextCompat
import com.example.R
import com.example.data.model.AppItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray

/**
 * Lightweight, zero-heavy-database repository for managing installed apps
 * and favorites using in-memory cache and SharedPreferences.
 */
class AppRepository(private val context: Context) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private val packageManager: PackageManager = context.packageManager

    // In-memory cache for instant start and zero lag
    private var cachedAllApps: List<AppItem> = emptyList()
    private var cachedFavorites: List<AppItem> = emptyList()

    companion object {
        private const val PREFS_NAME = "silvo_horizon_launcher_prefs"
        private const val KEY_FAVORITES_JSON = "favorites_list_json"
        const val DRAWER_SHORTCUT_PACKAGE = "com.silvohorizon.drawer"
    }

    /**
     * Loads all installed Leanback and sideloaded apps from PackageManager.
     */
    suspend fun getInstalledApps(forceRefresh: Boolean = false): List<AppItem> =
        withContext(Dispatchers.IO) {
            if (cachedAllApps.isNotEmpty() && !forceRefresh) {
                return@withContext cachedAllApps
            }

            val appMap = LinkedHashMap<String, AppItem>()

            // 1. Query Leanback Launcher apps (Android TV native)
            val tvIntent = Intent(Intent.ACTION_MAIN, null).apply {
                addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER)
            }
            val tvApps: List<ResolveInfo> = packageManager.queryIntentActivities(tvIntent, 0)
            for (resolveInfo in tvApps) {
                val pkg = resolveInfo.activityInfo.packageName
                if (pkg == context.packageName) continue // Skip this launcher

                val label = resolveInfo.loadLabel(packageManager).toString()
                val icon = resolveInfo.loadIcon(packageManager)
                val item = AppItem(
                    packageName = pkg,
                    activityName = resolveInfo.activityInfo.name,
                    label = label,
                    icon = icon,
                    isFavorite = isFavorite(pkg)
                )
                appMap[pkg] = item
            }

            // 2. Query Standard / Sideloaded Launcher apps (phone/tablet apps on TV)
            val standardIntent = Intent(Intent.ACTION_MAIN, null).apply {
                addCategory(Intent.CATEGORY_LAUNCHER)
            }
            val standardApps: List<ResolveInfo> =
                packageManager.queryIntentActivities(standardIntent, 0)
            for (resolveInfo in standardApps) {
                val pkg = resolveInfo.activityInfo.packageName
                if (pkg == context.packageName) continue // Skip this launcher

                // Only add if not already registered as a native TV app
                if (!appMap.containsKey(pkg)) {
                    val label = resolveInfo.loadLabel(packageManager).toString()
                    val icon = resolveInfo.loadIcon(packageManager)
                    val item = AppItem(
                        packageName = pkg,
                        activityName = resolveInfo.activityInfo.name,
                        label = label,
                        icon = icon,
                        isFavorite = isFavorite(pkg)
                    )
                    appMap[pkg] = item
                }
            }

            // Sort alphabetically by label
            val sortedList = appMap.values.sortedBy { it.label.lowercase() }
            cachedAllApps = sortedList
            sortedList
        }

    /**
     * Retrieves the favorite apps for the main infinite carousel.
     * Always appends the "Todas las Apps" drawer shortcut icon at the end.
     */
    suspend fun getFavorites(forceRefresh: Boolean = false): List<AppItem> =
        withContext(Dispatchers.IO) {
            if (cachedFavorites.isNotEmpty() && !forceRefresh) {
                return@withContext cachedFavorites
            }

            val all = getInstalledApps(forceRefresh)
            val savedFavoritePkgs = getSavedFavoritePackages()

            val favoriteItems = mutableListOf<AppItem>()

            if (savedFavoritePkgs.isNotEmpty()) {
                // Keep the exact saved order
                val allByPkg = all.associateBy { it.packageName }
                for (pkg in savedFavoritePkgs) {
                    allByPkg[pkg]?.let { item ->
                        item.isFavorite = true
                        favoriteItems.add(item)
                    }
                }
            }

            // If no favorites are configured, seed intelligently with available apps
            if (favoriteItems.isEmpty()) {
                // Priority packages if installed (YouTube, Netflix, Kodi, SmartTube, Plex, Prime Video)
                val priorityPrefixes = listOf(
                    "com.google.android.youtube",
                    "com.netflix.ninja",
                    "org.xbmc.kodi",
                    "com.teamsmart.videomanager.tv",
                    "com.amazon.amazonvideo",
                    "com.plexapp.android"
                )

                for (prefix in priorityPrefixes) {
                    val match = all.firstOrNull { it.packageName.startsWith(prefix) }
                    if (match != null && !favoriteItems.contains(match)) {
                        match.isFavorite = true
                        favoriteItems.add(match)
                    }
                }

                // Fill up to first 5 apps
                for (app in all) {
                    if (favoriteItems.size >= 5) break
                    if (!favoriteItems.contains(app)) {
                        app.isFavorite = true
                        favoriteItems.add(app)
                    }
                }

                // Save initial defaults
                saveFavoritePackages(favoriteItems.map { it.packageName })
            }

            // Create and append the "Todas las Apps" drawer shortcut as the final carousel item
            val drawerIcon = ContextCompat.getDrawable(context, R.drawable.ic_apps_grid)
            val drawerShortcut = AppItem(
                packageName = DRAWER_SHORTCUT_PACKAGE,
                activityName = "DrawerActivity",
                label = context.getString(R.string.drawer_shortcut_label),
                icon = drawerIcon,
                isDrawerShortcut = true,
                isFavorite = false
            )

            val finalList = favoriteItems + drawerShortcut
            cachedFavorites = finalList
            finalList
        }

    /**
     * Toggles an app's favorite status.
     */
    suspend fun toggleFavorite(appItem: AppItem): Boolean = withContext(Dispatchers.IO) {
        if (appItem.isDrawerShortcut) return@withContext false

        val currentList = getSavedFavoritePackages().toMutableList()
        val isNowFavorite: Boolean

        if (currentList.contains(appItem.packageName)) {
            currentList.remove(appItem.packageName)
            appItem.isFavorite = false
            isNowFavorite = false
        } else {
            currentList.add(appItem.packageName)
            appItem.isFavorite = true
            isNowFavorite = true
        }

        saveFavoritePackages(currentList)
        // Invalidate cache
        cachedFavorites = emptyList()
        getFavorites(forceRefresh = true)
        isNowFavorite
    }

    fun isFavorite(packageName: String): Boolean {
        return getSavedFavoritePackages().contains(packageName)
    }

    private fun getSavedFavoritePackages(): List<String> {
        val json = prefs.getString(KEY_FAVORITES_JSON, null) ?: return emptyList()
        return try {
            val jsonArray = JSONArray(json)
            val list = mutableListOf<String>()
            for (i in 0 until jsonArray.length()) {
                list.add(jsonArray.getString(i))
            }
            list
        } catch (e: Exception) {
            emptyList()
        }
    }

    private fun saveFavoritePackages(packages: List<String>) {
        val jsonArray = JSONArray()
        packages.forEach { jsonArray.put(it) }
        prefs.edit().putString(KEY_FAVORITES_JSON, jsonArray.toString()).apply()
    }

    /**
     * Launches the given app securely using explicit intent.
     */
    fun launchApp(app: AppItem): Boolean {
        return try {
            val intent = if (app.activityName.isNotEmpty()) {
                Intent(Intent.ACTION_MAIN).apply {
                    component = ComponentName(app.packageName, app.activityName)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
                }
            } else {
                packageManager.getLeanbackLaunchIntentForPackage(app.packageName)
                    ?: packageManager.getLaunchIntentForPackage(app.packageName)
            }

            if (intent != null) {
                context.startActivity(intent)
                true
            } else {
                false
            }
        } catch (e: Exception) {
            false
        }
    }
}
