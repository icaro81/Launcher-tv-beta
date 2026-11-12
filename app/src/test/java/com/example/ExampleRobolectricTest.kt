package com.example

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import androidx.test.core.app.ApplicationProvider
import com.example.data.model.AppItem
import com.example.data.repository.AppRepository
import com.example.ui.adapter.CarouselAppAdapter
import com.example.ui.view.SilvoHorizonBackgroundView
import com.example.updater.AppUpdater
import com.example.util.DefaultLauncherHelper
import com.example.util.VoiceCommandParser
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ExampleRobolectricTest {

    @Test
    fun `read string from context matches Silvo Horizon`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val appName = context.getString(R.string.app_name)
        assertEquals("Silvo Horizon", appName)
    }

    @Test
    fun `repository provides favorites with drawer shortcut at the end`() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val repository = AppRepository(context)

        val favorites = repository.getFavorites(forceRefresh = true)
        assertNotNull(favorites)
        assertTrue(favorites.isNotEmpty())

        val lastItem = favorites.last()
        assertTrue("Last item must be the drawer shortcut", lastItem.isDrawerShortcut)
        assertEquals(context.getString(R.string.drawer_shortcut_label), lastItem.label)
    }

    @Test
    fun `updater starts in Idle state with correct repository and version logic`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val updater = AppUpdater(context)
        assertEquals(AppUpdater.UpdateState.Idle, updater.updateState.value)
        assertEquals("icaro81/Launcher-tv-beta", updater.getRepositorySlug())
        assertTrue(updater.isNewerVersion("v1.0.5", "1.0"))
        assertTrue(updater.isNewerVersion("v1.1.0", "1.0.5"))
        assertTrue(!updater.isNewerVersion("v1.0.5", "1.0.5"))
    }

    @Test
    fun `carousel adapter wraps infinitely around center position`() {
        val adapter = CarouselAppAdapter(
            onItemClick = { _, _ -> },
            onItemLongClick = { false },
            onItemFocused = { _, _, _ -> }
        )

        val items = listOf(
            AppItem("com.a", "A", "App A", null),
            AppItem("com.b", "B", "App B", null),
            AppItem("com.c", "C", "App C", null)
        )
        adapter.submitItems(items)

        assertEquals(CarouselAppAdapter.VIRTUAL_LOOP_COUNT, adapter.itemCount)

        val centerStart = adapter.getCenterStartPosition()
        assertEquals("App A", adapter.getRealItem(centerStart)?.label)
        assertEquals("App B", adapter.getRealItem(centerStart + 1)?.label)
        assertEquals("App C", adapter.getRealItem(centerStart - 1)?.label)
        assertEquals("App C", adapter.getRealItem(centerStart + 2)?.label)
        assertEquals("App A", adapter.getRealItem(centerStart + 3)?.label)
    }

    @Test
    fun `background color extractor derives accent color from drawable`() {
        val redColor = SilvoHorizonBackgroundView.extractAccentColor(
            key = "test_red_app",
            drawable = ColorDrawable(Color.RED),
            isDrawerShortcut = false
        )
        assertEquals(Color.RED, redColor)

        val shortcutColor = SilvoHorizonBackgroundView.extractAccentColor(
            key = "test_shortcut",
            drawable = null,
            isDrawerShortcut = true
        )
        assertEquals(Color.parseColor("#00E5FF"), shortcutColor)
    }

    @Test
    fun `manifest resolves MainActivity for HOME intent`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val homeIntent = Intent(Intent.ACTION_MAIN).apply {
            addCategory(Intent.CATEGORY_HOME)
            setPackage(context.packageName)
        }
        val resolved = context.packageManager.queryIntentActivities(homeIntent, 0)
        assertTrue(
            "MainActivity should handle CATEGORY_HOME",
            resolved.any { it.activityInfo.name.endsWith("MainActivity") }
        )
        assertNotNull(DefaultLauncherHelper.buildSetDefaultLauncherIntent(context))
    }

    @Test
    fun `voice command parser extracts app query and launch intent`() {
        val openYoutube = VoiceCommandParser.parse("Abrir YouTube")
        assertTrue(openYoutube.isExplicitLaunch)
        assertEquals("YouTube", openYoutube.searchQuery)

        val searchNetflix = VoiceCommandParser.parse("buscar Netflix")
        assertTrue(!searchNetflix.isExplicitLaunch)
        assertEquals("Netflix", searchNetflix.searchQuery)

        val apps = listOf(
            AppItem("com.google.android.youtube.tv", "Main", "YouTube", null),
            AppItem("com.netflix.ninja", "Main", "Netflix", null)
        )
        val matches = VoiceCommandParser.findBestMatches(apps, openYoutube.searchQuery)
        assertEquals(1, matches.size)
        assertEquals("YouTube", matches.first().label)
    }

    @Test
    fun `manifest declares RECORD_AUDIO permission for voice search`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val pkgInfo = context.packageManager.getPackageInfo(
            context.packageName,
            PackageManager.GET_PERMISSIONS
        )
        val requested = pkgInfo.requestedPermissions?.toList().orEmpty()
        assertTrue(requested.contains(Manifest.permission.RECORD_AUDIO))
    }
}
