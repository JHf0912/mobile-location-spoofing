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
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.snake.mobilespoofing.core.geo.EndBehavior
import com.snake.mobilespoofing.core.geo.TravelSpeeds
import com.snake.mobilespoofing.core.model.FavoriteItem
import com.snake.mobilespoofing.core.model.MockStatus
import com.snake.mobilespoofing.ui.MainUiState
import com.snake.mobilespoofing.ui.MainViewModel
import com.snake.mobilespoofing.ui.SimulationMode
import com.snake.mobilespoofing.ui.map.AMapPicker
import dagger.hilt.android.AndroidEntryPoint

// Required for @HiltViewModel to resolve. Without it the mismatch is a *runtime* crash
// ("does not implement interface GeneratedComponentManager"), not a compile error.
@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    private val viewModel by viewModels<MainViewModel>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // targetSdk 35+ draws edge-to-edge unconditionally; opt in explicitly so the layout below
        // is responsible for its own insets rather than being clipped by the system bars.
        enableEdgeToEdge()
        setContent {
            MaterialTheme {
                MainRoute(viewModel)
            }
        }
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

    LaunchedEffect(uiState.message) {
        uiState.message?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.clearMessage()
        }
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.surface,
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .padding(horizontal = 14.dp, vertical = 12.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            HeroHeader()
            StatusCard(uiState.status)

            SectionTitle(
                title = "位置选择",
                subtitle = when (uiState.mode) {
                    SimulationMode.STATIC -> "点击地图设置模拟坐标"
                    SimulationMode.ROUTE -> "依次点击地图添加途经点"
                },
            )

            ModeSelector(
                selected = uiState.mode,
                onSelected = viewModel::onModeChanged,
            )

            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(20.dp),
                elevation = CardDefaults.cardElevation(defaultElevation = 4.dp),
            ) {
                AMapPicker(
                    waypointsWgs84 = uiState.routeWaypoints,
                    selectedPointWgs84 = uiState.selectedPoint,
                    onPointClick = { latLng ->
                        viewModel.onMapPointSelected(latLng.latitude, latLng.longitude)
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(280.dp),
                )
            }

            if (uiState.mode == SimulationMode.ROUTE) {
                RouteControls(
                    waypointCount = uiState.routeWaypoints.size,
                    travelSpeed = uiState.travelSpeed,
                    endBehavior = uiState.endBehavior,
                    onSpeedChanged = viewModel::onTravelSpeedChanged,
                    onEndBehaviorChanged = viewModel::onEndBehaviorChanged,
                    onUndo = viewModel::onUndoLastWaypoint,
                    onClear = viewModel::onClearRoute,
                )
            }

            SectionTitle("快速准备", "按顺序完成权限与系统设置")
            PermissionRow(
                onRequestPermission = {
                    val permissions = mutableListOf(
                        Manifest.permission.ACCESS_FINE_LOCATION,
                        Manifest.permission.ACCESS_COARSE_LOCATION,
                    )
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        permissions += Manifest.permission.POST_NOTIFICATIONS
                    }
                    permissionLauncher.launch(permissions.toTypedArray())
                },
                onOpenDeveloperOption = { openDeveloperOptions(context) },
                onOpenBatteryOptimization = { openBatteryOptimizationSettings(context) },
            )

            SectionTitle("当前选点与模拟")
            ControlSection(
                pointText = when (uiState.mode) {
                    SimulationMode.STATIC ->
                        uiState.selectedPoint?.let { formatPoint(it.latitude, it.longitude) } ?: "未选点"
                    SimulationMode.ROUTE -> "${uiState.routeWaypoints.size} 个途经点"
                },
                canStart = uiState.canStart,
                onSaveFavorite = viewModel::saveFavorite,
                onStartMock = viewModel::startMock,
                onStopMock = viewModel::stopMock,
                saveEnabled = uiState.selectedPoint != null,
            )

            SectionTitle("收藏位置")
            FavoriteSection(
                favorites = uiState.favorites,
                onPick = viewModel::pickFavorite,
                onDelete = viewModel::removeFavorite,
            )
        }
    }
}

@Composable
private fun HeroHeader() {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("Mock Location Tool", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
            Text("轻量、直接、可控", style = MaterialTheme.typography.bodyMedium)
        }
    }
}

@Composable
private fun SectionTitle(title: String, subtitle: String? = null) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(text = title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        if (!subtitle.isNullOrBlank()) {
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun StatusCard(status: MockStatus) {
    val text = when (status) {
        is MockStatus.Idle -> "状态：未模拟"
        is MockStatus.Running ->
            "状态：模拟中 ${formatPoint(status.pointWgs84.latitude, status.pointWgs84.longitude)}"
        is MockStatus.Error -> "状态：错误 ${status.message}"
    }
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
    ) {
        Text(
            text = text,
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
            style = MaterialTheme.typography.bodyMedium,
        )
    }
}

@Composable
private fun ModeSelector(selected: SimulationMode, onSelected: (SimulationMode) -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        FilterChip(
            selected = selected == SimulationMode.STATIC,
            onClick = { onSelected(SimulationMode.STATIC) },
            label = { Text("单点") },
        )
        FilterChip(
            selected = selected == SimulationMode.ROUTE,
            onClick = { onSelected(SimulationMode.ROUTE) },
            label = { Text("路线") },
        )
    }
}

@Composable
private fun RouteControls(
    waypointCount: Int,
    travelSpeed: TravelSpeeds,
    endBehavior: EndBehavior,
    onSpeedChanged: (TravelSpeeds) -> Unit,
    onEndBehaviorChanged: (EndBehavior) -> Unit,
    onUndo: () -> Unit,
    onClear: () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f)),
    ) {
        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text("途经点 $waypointCount", style = MaterialTheme.typography.bodyMedium)
                Row {
                    TextButton(onClick = onUndo, enabled = waypointCount > 0) { Text("撤销") }
                    TextButton(onClick = onClear, enabled = waypointCount > 0) { Text("清空") }
                }
            }

            Text("速度", style = MaterialTheme.typography.labelLarge)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TravelSpeeds.entries.forEach { speed ->
                    FilterChip(
                        selected = speed == travelSpeed,
                        onClick = { onSpeedChanged(speed) },
                        label = { Text("${speed.label} ${speed.kilometersPerHour.toInt()}") },
                    )
                }
            }

            Text("到达终点后", style = MaterialTheme.typography.labelLarge)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                EndBehavior.entries.forEach { behavior ->
                    FilterChip(
                        selected = behavior == endBehavior,
                        onClick = { onEndBehaviorChanged(behavior) },
                        label = { Text(behavior.label()) },
                    )
                }
            }
        }
    }
}

private fun EndBehavior.label(): String = when (this) {
    EndBehavior.STOP -> "停止"
    EndBehavior.LOOP -> "环线"
    EndBehavior.PING_PONG -> "往返"
}

@Composable
private fun PermissionRow(
    onRequestPermission: () -> Unit,
    onOpenDeveloperOption: () -> Unit,
    onOpenBatteryOptimization: () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f)),
    ) {
        Column(modifier = Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
                onClick = onRequestPermission,
                modifier = Modifier.fillMaxWidth().height(48.dp),
                shape = RoundedCornerShape(12.dp),
            ) { Text("1. 授权权限") }
            Button(
                onClick = onOpenDeveloperOption,
                modifier = Modifier.fillMaxWidth().height(48.dp),
                shape = RoundedCornerShape(12.dp),
                colors = ButtonDefaults.filledTonalButtonColors(),
            ) { Text("2. 开发者选项") }
            Button(
                onClick = onOpenBatteryOptimization,
                modifier = Modifier.fillMaxWidth().height(48.dp),
                shape = RoundedCornerShape(12.dp),
                colors = ButtonDefaults.filledTonalButtonColors(),
            ) { Text("3. 电池白名单") }
        }
    }
}

@Composable
private fun ControlSection(
    pointText: String,
    canStart: Boolean,
    saveEnabled: Boolean,
    onSaveFavorite: (String) -> Unit,
    onStartMock: () -> Unit,
    onStopMock: () -> Unit,
) {
    var name by rememberSaveable { mutableStateOf("") }

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f)),
    ) {
        Column(modifier = Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(
                value = pointText,
                onValueChange = {},
                readOnly = true,
                shape = RoundedCornerShape(12.dp),
                label = { Text("当前选点") },
                modifier = Modifier.fillMaxWidth(),
            )

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("收藏名") },
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.weight(1f),
                )
                Button(
                    onClick = { onSaveFavorite(name.ifBlank { "未命名收藏" }) },
                    enabled = saveEnabled,
                    modifier = Modifier.height(56.dp),
                    shape = RoundedCornerShape(12.dp),
                ) { Text("收藏") }
            }

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                Button(
                    onClick = onStartMock,
                    enabled = canStart,
                    modifier = Modifier.weight(1f).height(46.dp),
                    shape = RoundedCornerShape(12.dp),
                ) { Text("开始模拟") }
                Button(
                    onClick = onStopMock,
                    modifier = Modifier.weight(1f).height(46.dp),
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.filledTonalButtonColors(),
                ) { Text("停止模拟") }
            }
        }
    }
}

@Composable
private fun FavoriteSection(
    favorites: List<FavoriteItem>,
    onPick: (FavoriteItem) -> Unit,
    onDelete: (Long) -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
    ) {
        Column(modifier = Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            if (favorites.isEmpty()) {
                Text("暂无收藏", style = MaterialTheme.typography.bodySmall)
            } else {
                favorites.forEachIndexed { index, item ->
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        TextButton(onClick = { onPick(item) }, modifier = Modifier.weight(1f)) {
                            Text("${item.name}: ${formatPoint(item.point.latitude, item.point.longitude)}")
                        }
                        TextButton(onClick = { onDelete(item.id) }) { Text("删") }
                    }
                    if (index < favorites.lastIndex) {
                        HorizontalDivider()
                    }
                }
            }
        }
    }
}

private fun formatPoint(latitude: Double, longitude: Double): String =
    "%.5f, %.5f".format(latitude, longitude)

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
        context.startActivity(fallback)
    }
}

private fun openBatteryOptimizationSettings(context: Context) {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return

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
        context.startActivity(fallback)
    }
}
