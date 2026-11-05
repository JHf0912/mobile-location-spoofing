package com.snake.mobilespoofing

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.core.net.toUri
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.snake.mobilespoofing.ui.MainActions
import com.snake.mobilespoofing.ui.MainScreen
import com.snake.mobilespoofing.ui.MainViewModel
import com.snake.mobilespoofing.ui.theme.MockLocationTheme
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    private val viewModel by viewModels<MainViewModel>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            MockLocationTheme {
                MainRoute(viewModel)
            }
        }
    }

    override fun onResume() {
        super.onResume()
        // The mock-location AppOp and the runtime permissions are changed in Settings while this
        // activity is backgrounded. Without this refresh, returning from Developer Options left
        // canMock=false and the Start button blocked until the process was restarted.
        viewModel.refreshCapabilities()
    }
}

@Composable
private fun MainRoute(viewModel: MainViewModel) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    val context = LocalContext.current

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) {
        viewModel.refreshCapabilities()
    }

    // Ask for location as soon as the screen appears; the editor wants the current position as
    // its default, and the checklist row is too easy to overlook. The flag keeps a denial from
    // re-prompting on every recomposition.
    val locationPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) {
        viewModel.refreshCapabilities()
    }
    var askedForLocationOnLaunch by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        if (!uiState.permissionGranted && !askedForLocationOnLaunch) {
            askedForLocationOnLaunch = true
            locationPermissionLauncher.launch(locationPermissions())
        }
    }

    // The default fix is only meaningful once permission exists. Keying on the flag also covers a
    // grant from the checklist row or from Settings while the app was backgrounded.
    LaunchedEffect(uiState.permissionGranted) {
        if (uiState.permissionGranted) {
            viewModel.useCurrentLocationAsDefault()
        }
    }

    LaunchedEffect(uiState.message) {
        uiState.message?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.clearMessage()
        }
    }

    MainScreen(
        state = uiState,
        actions = MainActions(
            onMapPointSelected = viewModel::onMapPointSelected,
            onCoordinateInput = viewModel::onCoordinateInput,
            onModeChanged = viewModel::onModeChanged,
            onTravelSpeedChanged = viewModel::onTravelSpeedChanged,
            onEndBehaviorChanged = viewModel::onEndBehaviorChanged,
            onUndoWaypoint = viewModel::onUndoLastWaypoint,
            onClearRoute = viewModel::onClearRoute,
            onSaveFavorite = { name, onSaved -> viewModel.saveFavorite(name, onSaved) },
            onRemoveFavorite = viewModel::removeFavorite,
            onPickFavorite = viewModel::pickFavorite,
            onStart = viewModel::startMock,
            onStop = viewModel::stopMock,
            onRequestPermissions = { permissionLauncher.launch(requiredPermissions()) },
            onOpenDeveloperOptions = { openDeveloperOptions(context) },
            onOpenBatterySettings = { openBatteryOptimizationSettings(context) },
            onOpenNotificationSettings = { openNotificationSettings(context) },
        ),
        snackbarHostState = snackbarHostState,
    )
}

private fun locationPermissions(): Array<String> = arrayOf(
    Manifest.permission.ACCESS_FINE_LOCATION,
    Manifest.permission.ACCESS_COARSE_LOCATION,
)

private fun requiredPermissions(): Array<String> = buildList {
    add(Manifest.permission.ACCESS_FINE_LOCATION)
    add(Manifest.permission.ACCESS_COARSE_LOCATION)
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        add(Manifest.permission.POST_NOTIFICATIONS)
    }
}.toTypedArray()

private fun openDeveloperOptions(context: Context) {
    val intent = Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS).apply {
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
    val fallback = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
        data = Uri.parse("package:${context.packageName}")
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
    try {
        val target = if (intent.resolveActivity(context.packageManager) != null) intent else fallback
        context.startActivity(target)
    } catch (_: ActivityNotFoundException) {
        startActivitySafely(context, fallback)
    }
}

private fun openNotificationSettings(context: Context) {
    val intent = Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).apply {
        putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
    val fallback = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
        data = "package:${context.packageName}".toUri()
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }

    try {
        val target = if (intent.resolveActivity(context.packageManager) != null) intent else fallback
        context.startActivity(target)
    } catch (_: ActivityNotFoundException) {
        startActivitySafely(context, fallback)
    }
}

private fun openBatteryOptimizationSettings(context: Context) {
    val packageUri = Uri.parse("package:${context.packageName}")
    val requestIntent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
        data = packageUri
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
    val listIntent = Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS).apply {
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
    val fallback = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
        data = packageUri
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }

    try {
        val target = when {
            requestIntent.resolveActivity(context.packageManager) != null -> requestIntent
            listIntent.resolveActivity(context.packageManager) != null -> listIntent
            else -> fallback
        }
        context.startActivity(target)
    } catch (_: Exception) {
        // The fallback can itself be unresolvable on heavily stripped OEM builds; crashing the
        // app is worse than doing nothing and leaving the user in the current screen.
        startActivitySafely(context, fallback)
    }
}

private fun startActivitySafely(context: Context, intent: Intent) {
    try {
        context.startActivity(intent)
    } catch (_: ActivityNotFoundException) {
        // No settings activity handled the intent.
    }
}
