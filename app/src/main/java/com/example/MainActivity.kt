package com.example

import android.app.Dialog
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.text.Editable
import android.text.TextWatcher
import android.view.KeyEvent
import android.view.View
import android.view.Window
import android.view.WindowManager
import android.widget.Button
import android.widget.EditText
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.fragment.app.FragmentActivity
import androidx.leanback.widget.HorizontalGridView
import androidx.leanback.widget.VerticalGridView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.example.data.model.AppItem
import com.example.ui.adapter.AllAppsGridAdapter
import com.example.ui.adapter.CarouselAppAdapter
import com.example.ui.view.SilvoHorizonBackgroundView
import com.example.ui.viewmodel.LauncherViewModel
import com.example.updater.AppUpdater
import com.example.util.DefaultLauncherHelper
import kotlinx.coroutines.launch

/**
 * Silvo Horizon - Ultra-minimalist, high-performance Android TV Launcher
 * inspired by Aeon Nox Silvo (Kodi).
 */
class MainActivity : FragmentActivity() {

    private val viewModel: LauncherViewModel by viewModels()

    // Views
    private lateinit var dynamicBackgroundView: SilvoHorizonBackgroundView
    private lateinit var favoritesGridView: HorizontalGridView
    private lateinit var focusedAppNameTextView: TextView
    private lateinit var appDrawerContainer: View
    private lateinit var allAppsGridView: VerticalGridView
    private lateinit var searchEditText: EditText
    private lateinit var btnCloseDrawer: ImageButton

    // Top Bar Buttons
    private lateinit var btnHomeDefault: ImageButton
    private lateinit var btnWifi: ImageButton
    private lateinit var btnBluetooth: ImageButton
    private lateinit var btnUpdate: ImageButton
    private lateinit var btnSettings: ImageButton

    // Adapters
    private lateinit var carouselAdapter: CarouselAppAdapter
    private lateinit var gridAdapter: AllAppsGridAdapter

    // Dialog references
    private var activeOptionsDialog: Dialog? = null
    private var activeUpdaterDialog: Dialog? = null
    private var activeLauncherDialog: Dialog? = null

    private var lastTitle: String? = null

    private val defaultLauncherResult =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
            updateHomeDefaultIndicator()
            refreshDefaultLauncherDialogIfOpen()
            if (DefaultLauncherHelper.isDefaultLauncher(this)) {
                Toast.makeText(
                    this,
                    "Silvo Horizon establecido como Launcher predeterminado",
                    Toast.LENGTH_SHORT
                ).show()
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        hideSystemUI()
        setContentView(R.layout.activity_main)

        initViews()
        setupCarousel()
        setupAppDrawer()
        setupTopBar()
        setupBackNavigation()
        observeViewModel()
        updateHomeDefaultIndicator()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (intent.hasCategory(Intent.CATEGORY_HOME)) {
            activeOptionsDialog?.dismiss()
            activeUpdaterDialog?.dismiss()
            activeLauncherDialog?.dismiss()
            if (viewModel.isDrawerOpen.value) {
                viewModel.closeDrawer()
            }
            favoritesGridView.post {
                favoritesGridView.requestFocus()
            }
        }
    }

    override fun onResume() {
        super.onResume()
        hideSystemUI()
        updateHomeDefaultIndicator()
        viewModel.loadData(forceRefresh = true)
    }

    private fun hideSystemUI() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        val controller = WindowCompat.getInsetsController(window, window.decorView)
        controller.hide(WindowInsetsCompat.Type.systemBars())
        controller.systemBarsBehavior =
            WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }

    private fun initViews() {
        dynamicBackgroundView = findViewById(R.id.dynamicBackgroundView)
        favoritesGridView = findViewById(R.id.favoritesGridView)
        focusedAppNameTextView = findViewById(R.id.focusedAppNameTextView)
        appDrawerContainer = findViewById(R.id.appDrawerContainer)
        allAppsGridView = findViewById(R.id.allAppsGridView)
        searchEditText = findViewById(R.id.searchEditText)
        btnCloseDrawer = findViewById(R.id.btnCloseDrawer)

        btnHomeDefault = findViewById(R.id.btnHomeDefault)
        btnWifi = findViewById(R.id.btnWifi)
        btnBluetooth = findViewById(R.id.btnBluetooth)
        btnUpdate = findViewById(R.id.btnUpdate)
        btnSettings = findViewById(R.id.btnSettings)
    }

    private fun setupCarousel() {
        carouselAdapter = CarouselAppAdapter(
            onItemClick = { app, virtualPosition ->
                if (favoritesGridView.selectedPosition != virtualPosition) {
                    favoritesGridView.setSelectedPositionSmooth(virtualPosition)
                } else {
                    handleAppClick(app)
                }
            },
            onItemLongClick = { app ->
                if (!app.isDrawerShortcut) {
                    showAppOptionsDialog(app)
                    true
                } else {
                    false
                }
            },
            onItemFocused = { app, virtualPosition, _ ->
                onCarouselItemCentered(app, virtualPosition)
            }
        )

        favoritesGridView.apply {
            adapter = carouselAdapter
            // WINDOW_ALIGN_NO_EDGE + 50f keeps the focused item strictly at the center of the screen
            setFocusScrollStrategy(HorizontalGridView.FOCUS_SCROLL_ALIGNED)
            windowAlignment = HorizontalGridView.WINDOW_ALIGN_NO_EDGE
            windowAlignmentOffsetPercent = 50f
            itemAlignmentOffsetPercent = 50f
            setItemMargin(16)
        }
    }

    private fun onCarouselItemCentered(app: AppItem, virtualPosition: Int) {
        updateFocusedAppTitle(app.label)
        viewModel.setFocusedApp(app)

        val accentColor = SilvoHorizonBackgroundView.extractAccentColor(
            key = app.uniqueId,
            drawable = app.icon,
            isDrawerShortcut = app.isDrawerShortcut
        )
        dynamicBackgroundView.animateToAccentColor(accentColor)

        // Silently re-center if user ever scrolls near the edges of the 100,000 virtual span
        if (virtualPosition < 200 || virtualPosition > CarouselAppAdapter.VIRTUAL_LOOP_COUNT - 200) {
            val recentered = carouselAdapter.getCenterStartPosition(app.uniqueId)
            favoritesGridView.post {
                favoritesGridView.setSelectedPosition(recentered)
            }
        }
    }

    private fun setupAppDrawer() {
        gridAdapter = AllAppsGridAdapter(
            onItemClick = { app ->
                handleAppClick(app)
            },
            onItemLongClick = { app ->
                showAppOptionsDialog(app)
                true
            }
        )

        allAppsGridView.apply {
            setNumColumns(5)
            setItemSpacing(16)
            adapter = gridAdapter
        }

        btnCloseDrawer.setOnClickListener {
            viewModel.closeDrawer()
        }

        searchEditText.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                gridAdapter.filter(s?.toString().orEmpty())
            }
            override fun afterTextChanged(s: Editable?) {}
        })
    }

    private fun setupTopBar() {
        btnHomeDefault.setOnClickListener {
            showDefaultLauncherDialog()
        }

        btnWifi.setOnClickListener {
            launchSafeIntent(Intent(Settings.ACTION_WIFI_SETTINGS))
        }

        btnBluetooth.setOnClickListener {
            launchSafeIntent(Intent(Settings.ACTION_BLUETOOTH_SETTINGS))
        }

        btnUpdate.setOnClickListener {
            showUpdaterDialog()
            viewModel.checkForUpdates()
        }

        btnSettings.setOnClickListener {
            launchSafeIntent(Intent(Settings.ACTION_SETTINGS))
        }
    }

    private fun updateHomeDefaultIndicator() {
        val isDefault = DefaultLauncherHelper.isDefaultLauncher(this)
        val tintColor = if (isDefault) {
            ContextCompat.getColor(this, R.color.silvo_cyan_neon)
        } else {
            ContextCompat.getColor(this, R.color.silvo_text_secondary)
        }
        btnHomeDefault.imageTintList = ColorStateList.valueOf(tintColor)
    }

    private fun setupBackNavigation() {
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (viewModel.isDrawerOpen.value) {
                    viewModel.closeDrawer()
                }
                // Do not close launcher on home screen
            }
        })
    }

    private fun observeViewModel() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    viewModel.favorites.collect { list ->
                        val structureChanged = carouselAdapter.submitItems(list)
                        if (structureChanged && list.isNotEmpty()) {
                            val targetId = viewModel.focusedApp.value?.uniqueId
                            val centerPos = carouselAdapter.getCenterStartPosition(targetId)
                            favoritesGridView.setSelectedPosition(centerPos)

                            val centeredItem = carouselAdapter.getRealItem(centerPos) ?: list.first()
                            onCarouselItemCentered(centeredItem, centerPos)

                            if (!viewModel.isDrawerOpen.value) {
                                favoritesGridView.post {
                                    favoritesGridView.requestFocus()
                                }
                            }
                        }
                    }
                }

                launch {
                    viewModel.allApps.collect { list ->
                        gridAdapter.submitOriginalList(list)
                    }
                }

                launch {
                    viewModel.isDrawerOpen.collect { isOpen ->
                        if (isOpen) {
                            appDrawerContainer.visibility = View.VISIBLE
                            appDrawerContainer.alpha = 0f
                            appDrawerContainer.animate().alpha(1f).setDuration(200).start()
                            allAppsGridView.post {
                                allAppsGridView.requestFocus()
                            }
                        } else {
                            if (appDrawerContainer.visibility == View.VISIBLE) {
                                appDrawerContainer.animate()
                                    .alpha(0f)
                                    .setDuration(160)
                                    .withEndAction {
                                        appDrawerContainer.visibility = View.GONE
                                        searchEditText.setText("")
                                        favoritesGridView.requestFocus()
                                    }
                                    .start()
                            }
                        }
                    }
                }

                launch {
                    viewModel.updateState.collect { state ->
                        updateUpdaterDialogState(state)
                    }
                }
            }
        }
    }

    private fun updateFocusedAppTitle(title: String) {
        if (title == lastTitle && focusedAppNameTextView.text.toString() == title) return
        lastTitle = title

        focusedAppNameTextView.animate().cancel()
        focusedAppNameTextView.animate()
            .alpha(0f)
            .setDuration(75)
            .withEndAction {
                focusedAppNameTextView.text = title
                focusedAppNameTextView.animate()
                    .alpha(1f)
                    .setDuration(130)
                    .start()
            }
            .start()
    }

    private fun handleAppClick(app: AppItem) {
        if (app.isDrawerShortcut) {
            viewModel.openDrawer()
        } else {
            val launched = viewModel.launchApp(app)
            if (!launched) {
                Toast.makeText(this, "No se pudo iniciar ${app.label}", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun showDefaultLauncherDialog() {
        activeLauncherDialog?.dismiss()

        val dialog = Dialog(this)
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
        dialog.setContentView(R.layout.dialog_default_launcher)
        dialog.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))

        activeLauncherDialog = dialog
        refreshDefaultLauncherDialogContent(dialog)

        val btnSetDefault: Button = dialog.findViewById(R.id.btnSetDefaultLauncher)
        val btnOpenHomeSettings: Button = dialog.findViewById(R.id.btnOpenHomeSettings)
        val btnClose: Button = dialog.findViewById(R.id.btnCloseLauncherDialog)

        btnSetDefault.setOnClickListener {
            val intent = DefaultLauncherHelper.buildSetDefaultLauncherIntent(this)
            if (intent != null) {
                try {
                    defaultLauncherResult.launch(intent)
                } catch (_: Exception) {
                    openHomeSettingsFallback()
                }
            } else {
                openHomeSettingsFallback()
            }
        }

        btnOpenHomeSettings.setOnClickListener {
            openHomeSettingsFallback()
        }

        btnClose.setOnClickListener {
            dialog.dismiss()
        }

        dialog.show()
        btnSetDefault.requestFocus()
    }

    private fun refreshDefaultLauncherDialogIfOpen() {
        val dialog = activeLauncherDialog ?: return
        if (dialog.isShowing) {
            refreshDefaultLauncherDialogContent(dialog)
        }
    }

    private fun refreshDefaultLauncherDialogContent(dialog: Dialog) {
        val statusView: TextView = dialog.findViewById(R.id.launcherDialogStatus)
        val isDefault = DefaultLauncherHelper.isDefaultLauncher(this)
        statusView.text = if (isDefault) {
            getString(R.string.default_launcher_status_active)
        } else {
            val currentLabel = DefaultLauncherHelper.getCurrentDefaultLauncherLabel(this)
            getString(R.string.default_launcher_status_inactive, currentLabel)
        }
        statusView.setTextColor(
            ContextCompat.getColor(
                this,
                if (isDefault) R.color.silvo_accent_green else R.color.silvo_text_primary
            )
        )
    }

    private fun openHomeSettingsFallback() {
        val candidates = mutableListOf(
            Intent(Settings.ACTION_HOME_SETTINGS)
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            candidates.add(Intent(Settings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS))
        }
        candidates.add(Intent(Settings.ACTION_SETTINGS))

        for (intent in candidates) {
            try {
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                startActivity(intent)
                return
            } catch (_: Exception) {
                // Try next settings screen
            }
        }
        Toast.makeText(this, "Abre Ajustes > Aplicaciones > Apps predeterminadas", Toast.LENGTH_LONG).show()
    }

    private fun showAppOptionsDialog(app: AppItem) {
        activeOptionsDialog?.dismiss()

        val dialog = Dialog(this)
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
        dialog.setContentView(R.layout.dialog_app_options)
        dialog.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))

        val dialogAppIcon: ImageView = dialog.findViewById(R.id.dialogAppIcon)
        val dialogAppTitle: TextView = dialog.findViewById(R.id.dialogAppTitle)
        val dialogAppPackage: TextView = dialog.findViewById(R.id.dialogAppPackage)
        val btnOpenApp: Button = dialog.findViewById(R.id.btnOpenApp)
        val btnToggleFavorite: Button = dialog.findViewById(R.id.btnToggleFavorite)
        val btnAppInfo: Button = dialog.findViewById(R.id.btnAppInfo)
        val btnUninstall: Button = dialog.findViewById(R.id.btnUninstall)

        if (app.icon != null) {
            dialogAppIcon.setImageDrawable(app.icon)
        } else {
            dialogAppIcon.setImageResource(R.drawable.ic_apps_grid)
        }

        dialogAppTitle.text = app.label
        dialogAppPackage.text = app.packageName

        btnToggleFavorite.text = if (app.isFavorite) {
            getString(R.string.action_remove_favorite)
        } else {
            getString(R.string.action_add_favorite)
        }

        btnOpenApp.setOnClickListener {
            dialog.dismiss()
            handleAppClick(app)
        }

        btnToggleFavorite.setOnClickListener {
            dialog.dismiss()
            viewModel.toggleFavorite(app)
            val msg = if (app.isFavorite) "Añadido a favoritos" else "Quitado de favoritos"
            Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
        }

        btnAppInfo.setOnClickListener {
            dialog.dismiss()
            try {
                val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                    data = Uri.fromParts("package", app.packageName, null)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                startActivity(intent)
            } catch (_: Exception) {
                Toast.makeText(this, "Error al abrir información", Toast.LENGTH_SHORT).show()
            }
        }

        btnUninstall.setOnClickListener {
            dialog.dismiss()
            try {
                val intent = Intent(Intent.ACTION_DELETE).apply {
                    data = Uri.fromParts("package", app.packageName, null)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                startActivity(intent)
            } catch (_: Exception) {
                Toast.makeText(this, "Error al solicitar desinstalación", Toast.LENGTH_SHORT).show()
            }
        }

        activeOptionsDialog = dialog
        dialog.show()
        btnOpenApp.requestFocus()
    }

    private fun showUpdaterDialog() {
        activeUpdaterDialog?.dismiss()

        val dialog = Dialog(this)
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
        dialog.setContentView(R.layout.dialog_updater)
        dialog.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))

        val updaterRepoInfo: TextView = dialog.findViewById(R.id.updaterRepoInfo)
        updaterRepoInfo.text = "Repositorio: icarojose81/silvo-horizon-tv • v${BuildConfig.VERSION_NAME}"

        val btnCancel: Button = dialog.findViewById(R.id.btnCancelUpdater)
        btnCancel.setOnClickListener {
            dialog.dismiss()
        }

        activeUpdaterDialog = dialog
        dialog.show()
    }

    private fun updateUpdaterDialogState(state: AppUpdater.UpdateState) {
        val dialog = activeUpdaterDialog ?: return
        if (!dialog.isShowing) return

        val statusText: TextView = dialog.findViewById(R.id.updaterStatus)
        val progressBar: ProgressBar = dialog.findViewById(R.id.updaterProgressBar)
        val actionButton: Button = dialog.findViewById(R.id.btnActionUpdater)

        when (state) {
            is AppUpdater.UpdateState.Checking -> {
                statusText.text = getString(R.string.updater_checking)
                progressBar.visibility = View.VISIBLE
                progressBar.isIndeterminate = true
                actionButton.visibility = View.GONE
            }
            is AppUpdater.UpdateState.UpToDate -> {
                statusText.text = getString(R.string.updater_up_to_date)
                progressBar.visibility = View.GONE
                actionButton.visibility = View.VISIBLE
                actionButton.text = "Aceptar"
                actionButton.setOnClickListener { dialog.dismiss() }
            }
            is AppUpdater.UpdateState.UpdateAvailable -> {
                statusText.text = getString(R.string.updater_update_found, state.latestVersion)
                progressBar.visibility = View.GONE
                actionButton.visibility = View.VISIBLE
                actionButton.text = "Descargar e Instalar"
                actionButton.setOnClickListener {
                    viewModel.updater.startDownload(state.downloadUrl, state.latestVersion)
                }
            }
            is AppUpdater.UpdateState.Downloading -> {
                statusText.text = getString(R.string.updater_downloading, state.progressPercent)
                progressBar.visibility = View.VISIBLE
                progressBar.isIndeterminate = true
                actionButton.visibility = View.GONE
            }
            is AppUpdater.UpdateState.DownloadComplete -> {
                statusText.text = getString(R.string.updater_installing)
                progressBar.visibility = View.GONE
                actionButton.visibility = View.VISIBLE
                actionButton.text = "Instalar Ahora"
                actionButton.setOnClickListener {
                    viewModel.updater.installApk(state.apkFile)
                }
            }
            is AppUpdater.UpdateState.Error -> {
                statusText.text = "${getString(R.string.updater_error)}: ${state.message}"
                progressBar.visibility = View.GONE
                actionButton.visibility = View.VISIBLE
                actionButton.text = "Reintentar"
                actionButton.setOnClickListener {
                    viewModel.checkForUpdates()
                }
            }
            else -> {}
        }
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (keyCode == KeyEvent.KEYCODE_MENU) {
            val focusedApp = viewModel.focusedApp.value
            if (focusedApp != null && !focusedApp.isDrawerShortcut) {
                showAppOptionsDialog(focusedApp)
                return true
            }
        }
        return super.onKeyDown(keyCode, event)
    }

    private fun launchSafeIntent(intent: Intent) {
        try {
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            startActivity(intent)
        } catch (_: Exception) {
            Toast.makeText(this, "Opción no disponible en este dispositivo", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        activeOptionsDialog?.dismiss()
        activeUpdaterDialog?.dismiss()
        activeLauncherDialog?.dismiss()
    }
}
