package com.example

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.data.repository.AppRepository
import com.example.updater.AppUpdater
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
    fun `updater starts in Idle state`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val updater = AppUpdater(context)
        assertEquals(AppUpdater.UpdateState.Idle, updater.updateState.value)
    }
}
