package com.example

import android.Manifest
import android.app.Activity
import android.app.Dialog
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
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
import com.example.util.VoiceCommandParser
import kotlinx.coroutines.launch
import java.util.Locale

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

    // Top Bar & Drawer Buttons
    private lateinit var btnVoiceSearch: ImageButton
    private lateinit var btnDrawerVoiceSearch: ImageButton
    private lateinit var btnHomeDefault: ImageButton
    private lateinit var btnWifi: ImageButton
    private lateinit var btnBluetooth: ImageButton
    private lateinit var btnUpdate: ImageButton
    private lateinit var btnSettings: ImageButton

    // Adapters
    private lateinit var carouselAdapter: CarouselAppAdapter
    private lateinit var gridAdapter: AllAppsGridAdapter

    // Dialog & SpeechRecognizer references
    private var activeOptionsDialog: Dialog? = null
    private var activeUpdaterDialog: Dialog? = null
    private var activeLauncherDialog: Dialog? = null
    private var activeVoiceDialog: Dialog? = null
    private var speechRecognizer: SpeechRecognizer? = null

    private var lastTitle: String? = null

    private val recordAudioPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { isGranted ->
            if (isGranted) {
                startVoiceSearchListening()
            } else {
                Toast.makeText(
                    this,
                    getString(R.string.voice_permission_denied),
                    Toast.LENGTH_LONG
                ).show()
            }
        }

    private val voiceIntentLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode == Activity.RESULT_OK) {
                val spokenText = result.data
                    ?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)
                    ?.firstOrNull()
                if (!spokenText.isNullOrBlank()) {
                    handleVoiceSearchResult(spokenText)
                }
            }
        }

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
            stopVoiceSearchDialog()
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

        btnVoiceSearch = findViewById(R.id.btnVoiceSearch)
        btnDrawerVoiceSearch = findViewById(R.id.btnDrawerVoiceSearch)
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

        btnDrawerVoiceSearch.setOnClickListener {
            initiateVoiceSearch()
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
        btnVoiceSearch.setOnClickListener {
            initiateVoiceSearch()
        }

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
                        updateUpdateButtonIndicator(state)
                        updateUpdaterDialogState(state)
                    }
                }
            }
        }
    }

    private fun updateUpdateButtonIndicator(state: AppUpdater.UpdateState) {
        val tintColor = when (state) {
            is AppUpdater.UpdateState.UpdateAvailable,
            is AppUpdater.UpdateState.DownloadComplete -> ContextCompat.getColor(this, R.color.silvo_cyan_neon)
            else -> ContextCompat.getColor(this, R.color.silvo_text_secondary)
        }
        btnUpdate.imageTintList = ColorStateList.valueOf(tintColor)
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
        updaterRepoInfo.text =
            "Repositorio: ${viewModel.updater.getRepositorySlug()} • v${BuildConfig.VERSION_NAME}"

        val btnCancel: Button = dialog.findViewById(R.id.btnCancelUpdater)
        btnCancel.setOnClickListener {
            dialog.dismiss()
        }

        activeUpdaterDialog = dialog
        dialog.show()
        updateUpdaterDialogState(viewModel.updateState.value)
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
                statusText.text = "${getString(R.string.updater_up_to_date)} (v${BuildConfig.VERSION_NAME})"
                progressBar.visibility = View.GONE
                actionButton.visibility = View.VISIBLE
                actionButton.text = "Aceptar"
                actionButton.setOnClickListener { dialog.dismiss() }
                actionButton.requestFocus()
            }
            is AppUpdater.UpdateState.UpdateAvailable -> {
                statusText.text = getString(R.string.updater_update_found, state.latestVersion)
                progressBar.visibility = View.GONE
                actionButton.visibility = View.VISIBLE
                actionButton.text = "Descargar e Instalar"
                actionButton.setOnClickListener {
                    viewModel.updater.startDownload(state.downloadUrl, state.latestVersion)
                }
                actionButton.requestFocus()
            }
            is AppUpdater.UpdateState.Downloading -> {
                statusText.text = getString(R.string.updater_downloading, state.progressPercent)
                progressBar.visibility = View.VISIBLE
                if (state.progressPercent > 0) {
                    progressBar.isIndeterminate = false
                    progressBar.max = 100
                    progressBar.progress = state.progressPercent
                } else {
                    progressBar.isIndeterminate = true
                }
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
                actionButton.requestFocus()
            }
            is AppUpdater.UpdateState.Error -> {
                statusText.text = "${getString(R.string.updater_error)}: ${state.message}"
                progressBar.visibility = View.GONE
                actionButton.visibility = View.VISIBLE
                actionButton.text = "Reintentar"
                actionButton.setOnClickListener {
                    viewModel.checkForUpdates()
                }
                actionButton.requestFocus()
            }
            else -> {}
        }
    }

    // =================================================================
    // VOICE SEARCH (Android TV Microphone Permission + SpeechRecognizer)
    // =================================================================

    private fun initiateVoiceSearch() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED
        ) {
            startVoiceSearchListening()
        } else {
            recordAudioPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    private fun startVoiceSearchListening() {
        if (SpeechRecognizer.isRecognitionAvailable(this)) {
            showVoiceSearchDialog()
        } else {
            launchRecognizerIntentFallback()
        }
    }

    private fun launchRecognizerIntentFallback() {
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(
                RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                RecognizerIntent.LANGUAGE_MODEL_FREE_FORM
            )
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault())
            putExtra(RecognizerIntent.EXTRA_PROMPT, getString(R.string.voice_speak_now))
        }
        try {
            voiceIntentLauncher.launch(intent)
        } catch (_: Exception) {
            Toast.makeText(this, getString(R.string.voice_not_available), Toast.LENGTH_LONG).show()
        }
    }

    private fun showVoiceSearchDialog() {
        stopVoiceSearchDialog()

        val dialog = Dialog(this)
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
        dialog.setContentView(R.layout.dialog_voice_search)
        dialog.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))

        val pulseRing: View = dialog.findViewById(R.id.voicePulseRing)
        val micIcon: ImageView = dialog.findViewById(R.id.voiceMicIcon)
        val statusText: TextView = dialog.findViewById(R.id.voiceStatusText)
        val transcriptText: TextView = dialog.findViewById(R.id.voiceTranscriptText)
        val btnCancel: Button = dialog.findViewById(R.id.btnCancelVoice)

        micIcon.imageTintList =
            ColorStateList.valueOf(ContextCompat.getColor(this, R.color.silvo_cyan_neon))

        val recognizer = SpeechRecognizer.createSpeechRecognizer(this)
        speechRecognizer = recognizer

        recognizer.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) {
                statusText.text = getString(R.string.voice_listening)
                transcriptText.text = getString(R.string.voice_speak_now)
            }

            override fun onBeginningOfSpeech() {
                pulseRing.animate().scaleX(1.25f).scaleY(1.25f).alpha(0.85f).setDuration(150).start()
            }

            override fun onRmsChanged(rmsdB: Float) {
                val scale = (1.0f + (rmsdB.coerceIn(0f, 12f) / 22f))
                pulseRing.scaleX = scale
                pulseRing.scaleY = scale
            }

            override fun onBufferReceived(buffer: ByteArray?) {}

            override fun onEndOfSpeech() {
                pulseRing.animate().scaleX(1.0f).scaleY(1.0f).alpha(0.4f).setDuration(180).start()
            }

            override fun onError(error: Int) {
                val msg = when (error) {
                    SpeechRecognizer.ERROR_NO_MATCH,
                    SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "No se escuchó ninguna aplicación"
                    SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> getString(R.string.voice_permission_denied)
                    SpeechRecognizer.ERROR_NETWORK,
                    SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "Error de red en reconocimiento de voz"
                    else -> getString(R.string.voice_not_available)
                }
                Toast.makeText(this@MainActivity, msg, Toast.LENGTH_SHORT).show()
                stopVoiceSearchDialog()
            }

            override fun onResults(results: Bundle?) {
                val spoken = results
                    ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    ?.firstOrNull()
                stopVoiceSearchDialog()
                if (!spoken.isNullOrBlank()) {
                    handleVoiceSearchResult(spoken)
                }
            }

            override fun onPartialResults(partialResults: Bundle?) {
                val partial = partialResults
                    ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    ?.firstOrNull()
                if (!partial.isNullOrBlank()) {
                    transcriptText.text = "“$partial”"
                }
            }

            override fun onEvent(eventType: Int, params: Bundle?) {}
        })

        btnCancel.setOnClickListener {
            stopVoiceSearchDialog()
        }

        dialog.setOnDismissListener {
            destroySpeechRecognizer()
        }

        activeVoiceDialog = dialog
        dialog.show()
        btnCancel.requestFocus()

        val recognizerIntent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(
                RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                RecognizerIntent.LANGUAGE_MODEL_FREE_FORM
            )
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault())
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3)
            putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, packageName)
        }

        try {
            recognizer.startListening(recognizerIntent)
        } catch (_: Exception) {
            stopVoiceSearchDialog()
            launchRecognizerIntentFallback()
        }
    }

    private fun handleVoiceSearchResult(spokenText: String) {
        val parsed = VoiceCommandParser.parse(spokenText)
        val matches = VoiceCommandParser.findBestMatches(viewModel.allApps.value, parsed.searchQuery)

        if (parsed.isExplicitLaunch && matches.size == 1) {
            val appToLaunch = matches.first()
            Toast.makeText(
                this,
                getString(R.string.voice_opening_app, appToLaunch.label),
                Toast.LENGTH_SHORT
            ).show()
            handleAppClick(appToLaunch)
            return
        }

        // Open All Apps drawer filtered to the spoken voice query
        viewModel.openDrawer()
        searchEditText.post {
            searchEditText.setText(parsed.searchQuery)
            searchEditText.setSelection(parsed.searchQuery.length)
            gridAdapter.filter(parsed.searchQuery)
            if (matches.isEmpty() && parsed.searchQuery.isNotBlank()) {
                Toast.makeText(
                    this,
                    getString(R.string.voice_no_match, parsed.searchQuery),
                    Toast.LENGTH_SHORT
                ).show()
            } else {
                allAppsGridView.post {
                    allAppsGridView.requestFocus()
                }
            }
        }
    }

    private fun stopVoiceSearchDialog() {
        activeVoiceDialog?.dismiss()
        activeVoiceDialog = null
        destroySpeechRecognizer()
    }

    private fun destroySpeechRecognizer() {
        try {
            speechRecognizer?.cancel()
            speechRecognizer?.destroy()
        } catch (_: Exception) {
            // Ignore cleanup errors
        }
        speechRecognizer = null
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (keyCode == KeyEvent.KEYCODE_MENU) {
            val focusedApp = viewModel.focusedApp.value
            if (focusedApp != null && !focusedApp.isDrawerShortcut) {
                showAppOptionsDialog(focusedApp)
                return true
            }
        } else if (keyCode == KeyEvent.KEYCODE_SEARCH || keyCode == KeyEvent.KEYCODE_VOICE_ASSIST) {
            initiateVoiceSearch()
            return true
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
        stopVoiceSearchDialog()
    }
}
