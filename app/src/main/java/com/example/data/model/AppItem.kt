package com.example.data.model

import android.graphics.drawable.Drawable

/**
 * Data model representing an application or action item in the Silvo Horizon launcher.
 */
data class AppItem(
    val packageName: String,
    val activityName: String,
    val label: String,
    val icon: Drawable?,
    val isDrawerShortcut: Boolean = false,
    var isFavorite: Boolean = false
) {
    val uniqueId: String
        get() = if (isDrawerShortcut) "ACTION_DRAWER_SHORTCUT" else "$packageName/$activityName"
}
