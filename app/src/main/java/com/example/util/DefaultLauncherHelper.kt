package com.example.util

import android.app.role.RoleManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings

/**
 * Helper for checking and requesting the default Home / Launcher role on Android TV
 * devices including Xiaomi Mi Box S.
 */
object DefaultLauncherHelper {

    fun isDefaultLauncher(context: Context): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val roleManager = context.getSystemService(RoleManager::class.java)
            if (roleManager != null && roleManager.isRoleAvailable(RoleManager.ROLE_HOME)) {
                return roleManager.isRoleHeld(RoleManager.ROLE_HOME)
            }
        }

        val homeIntent = Intent(Intent.ACTION_MAIN).apply {
            addCategory(Intent.CATEGORY_HOME)
        }
        val resolveInfo = context.packageManager.resolveActivity(
            homeIntent,
            PackageManager.MATCH_DEFAULT_ONLY
        )
        return resolveInfo?.activityInfo?.packageName == context.packageName
    }

    fun getCurrentDefaultLauncherLabel(context: Context): String {
        val homeIntent = Intent(Intent.ACTION_MAIN).apply {
            addCategory(Intent.CATEGORY_HOME)
        }
        val resolveInfo = context.packageManager.resolveActivity(
            homeIntent,
            PackageManager.MATCH_DEFAULT_ONLY
        ) ?: return "Ninguno"

        val pkg = resolveInfo.activityInfo?.packageName ?: return "Sistema"
        if (pkg == "android") return "Selector del sistema (sin predeterminado)"
        return try {
            resolveInfo.loadLabel(context.packageManager).toString()
        } catch (_: Exception) {
            pkg
        }
    }

    /**
     * Builds the best intent to prompt the user to set Silvo Horizon as default Home launcher.
     */
    fun buildSetDefaultLauncherIntent(context: Context): Intent? {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val roleManager = context.getSystemService(RoleManager::class.java)
            if (roleManager != null &&
                roleManager.isRoleAvailable(RoleManager.ROLE_HOME) &&
                !roleManager.isRoleHeld(RoleManager.ROLE_HOME)
            ) {
                return roleManager.createRequestRoleIntent(RoleManager.ROLE_HOME)
            }
        }

        val pm = context.packageManager

        val homeSettingsIntent = Intent(Settings.ACTION_HOME_SETTINGS)
        if (homeSettingsIntent.resolveActivity(pm) != null) {
            return homeSettingsIntent
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            val defaultAppsIntent = Intent(Settings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS)
            if (defaultAppsIntent.resolveActivity(pm) != null) {
                return defaultAppsIntent
            }
        }

        val chooserIntent = Intent.createChooser(
            Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME),
            "Seleccionar Launcher predeterminado"
        )
        return chooserIntent
    }
}
