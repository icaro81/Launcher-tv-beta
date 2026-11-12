package com.example.ui.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.data.model.AppItem
import com.example.data.repository.AppRepository
import com.example.updater.AppUpdater
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Clean MVVM ViewModel for Silvo Horizon Android TV Launcher.
 */
class LauncherViewModel(application: Application) : AndroidViewModel(application) {

    private val repository = AppRepository(application.applicationContext)
    val updater = AppUpdater(application.applicationContext)

    private val _favorites = MutableStateFlow<List<AppItem>>(emptyList())
    val favorites: StateFlow<List<AppItem>> = _favorites.asStateFlow()

    private val _allApps = MutableStateFlow<List<AppItem>>(emptyList())
    val allApps: StateFlow<List<AppItem>> = _allApps.asStateFlow()

    private val _focusedApp = MutableStateFlow<AppItem?>(null)
    val focusedApp: StateFlow<AppItem?> = _focusedApp.asStateFlow()

    private val _isDrawerOpen = MutableStateFlow(false)
    val isDrawerOpen: StateFlow<Boolean> = _isDrawerOpen.asStateFlow()

    val updateState: StateFlow<AppUpdater.UpdateState> = updater.updateState

    init {
        loadData()
        checkForUpdates()
    }

    fun loadData(forceRefresh: Boolean = false) {
        viewModelScope.launch {
            val all = repository.getInstalledApps(forceRefresh)
            _allApps.value = all

            val favs = repository.getFavorites(forceRefresh)
            _favorites.value = favs

            if (_focusedApp.value == null && favs.isNotEmpty()) {
                _focusedApp.value = favs.first()
            }
        }
    }

    fun setFocusedApp(app: AppItem) {
        _focusedApp.value = app
    }

    fun toggleFavorite(app: AppItem) {
        viewModelScope.launch {
            repository.toggleFavorite(app)
            loadData(forceRefresh = true)
        }
    }

    fun openDrawer() {
        _isDrawerOpen.value = true
    }

    fun closeDrawer() {
        _isDrawerOpen.value = false
    }

    fun toggleDrawer() {
        _isDrawerOpen.value = !_isDrawerOpen.value
    }

    fun launchApp(app: AppItem): Boolean {
        return repository.launchApp(app)
    }

    fun checkForUpdates() {
        viewModelScope.launch {
            updater.checkForUpdates()
        }
    }

    override fun onCleared() {
        super.onCleared()
        updater.unregister()
    }
}
