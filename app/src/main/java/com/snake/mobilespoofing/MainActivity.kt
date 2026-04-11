package com.snake.mobilespoofing

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.ExposedDropdownMenu
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.amap.api.maps.MapView
import com.amap.api.maps.model.LatLng
import com.amap.api.maps.model.MarkerOptions
import com.snake.mobilespoofing.data.FavoriteItem
import com.snake.mobilespoofing.location.CoordType
import com.snake.mobilespoofing.location.MockStatus
import com.snake.mobilespoofing.ui.MainViewModel
import com.snake.mobilespoofing.ui.SearchResultItem

class MainActivity : ComponentActivity() {
    private val viewModel by viewModels<MainViewModel>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
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

    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) {
        viewModel.refreshCapabilities()
    }

    LaunchedEffect(uiState.message) {
        uiState.message?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.clearMessage()
        }
    }

    Scaffold(snackbarHost = { SnackbarHost(snackbarHostState) }) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            StatusBar(uiState.status)

            AMapPicker(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(300.dp),
                onPointClick = { latLng -> viewModel.onMapPointSelected(latLng.latitude, latLng.longitude) }
            )

            PermissionRow(
                onRequestPermission = {
                    val permissions = mutableListOf(
                        Manifest.permission.ACCESS_FINE_LOCATION,
                        Manifest.permission.ACCESS_COARSE_LOCATION
                    )
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        permissions += Manifest.permission.POST_NOTIFICATIONS
                    }
                    launcher.launch(permissions.toTypedArray())
                },
                onOpenDeveloperOption = {
                    context.startActivity(Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS))
                },
                onOpenBatteryOptimization = {
                    val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                        data = Uri.parse("package:${context.packageName}")
                    }
                    context.startActivity(intent)
                }
            )

            CoordTypeSelector(selected = uiState.selectedCoordType, onSelected = viewModel::onCoordTypeChanged)

            SearchSection(
                keyword = uiState.searchKeyword,
                onKeywordChanged = viewModel::onSearchKeywordChange,
                onSearch = { viewModel.searchPoi(context) },
                results = uiState.searchResults,
                onSelect = viewModel::onSelectSearchResult
            )

            LocationInputSection(
                pointText = uiState.selectedPoint?.let { "${it.latitude}, ${it.longitude}" } ?: "未选点",
                onSaveFavorite = viewModel::saveFavorite,
                onStartMock = viewModel::startMock,
                onStopMock = viewModel::stopMock
            )

            FavoriteSection(
                favorites = uiState.favorites,
                onPick = viewModel::pickFavorite,
                onDelete = viewModel::removeFavorite
            )
        }
    }
}

@Composable
private fun StatusBar(status: MockStatus) {
    val text = when (status) {
        is MockStatus.Idle -> "状态：未模拟"
        is MockStatus.Running -> "状态：模拟中 ${status.pointWgs84.latitude}, ${status.pointWgs84.longitude}"
        is MockStatus.Error -> "状态：错误 ${status.message}"
    }
    Card(modifier = Modifier.fillMaxWidth()) {
        Text(text = text, modifier = Modifier.padding(12.dp))
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CoordTypeSelector(selected: CoordType, onSelected: (CoordType) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = !expanded }) {
        OutlinedTextField(
            modifier = Modifier
                .menuAnchor()
                .fillMaxWidth(),
            readOnly = true,
            value = selected.name,
            onValueChange = {},
            label = { Text("坐标系") },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) }
        )
        ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            CoordType.entries.forEach { type ->
                DropdownMenuItem(
                    text = { Text(type.name) },
                    onClick = {
                        onSelected(type)
                        expanded = false
                    }
                )
            }
        }
    }
}

@Composable
private fun PermissionRow(
    onRequestPermission: () -> Unit,
    onOpenDeveloperOption: () -> Unit,
    onOpenBatteryOptimization: () -> Unit
) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
        Button(onClick = onRequestPermission, modifier = Modifier.weight(1f)) { Text("授权权限") }
        Button(onClick = onOpenDeveloperOption, modifier = Modifier.weight(1f)) { Text("开发者选项") }
        Button(onClick = onOpenBatteryOptimization, modifier = Modifier.weight(1f)) { Text("电池白名单") }
    }
}

@Composable
private fun SearchSection(
    keyword: String,
    onKeywordChanged: (String) -> Unit,
    onSearch: () -> Unit,
    results: List<SearchResultItem>,
    onSelect: (SearchResultItem) -> Unit
) {
    OutlinedTextField(
        value = keyword,
        onValueChange = onKeywordChanged,
        modifier = Modifier.fillMaxWidth(),
        label = { Text("搜索地点") }
    )
    Button(onClick = onSearch) { Text("搜索") }

    LazyColumn(modifier = Modifier.height(90.dp)) {
        items(results) { item ->
            TextButton(onClick = { onSelect(item) }) {
                Text("${item.title} ${item.address}")
            }
        }
    }
}

@Composable
private fun LocationInputSection(
    pointText: String,
    onSaveFavorite: (String) -> Unit,
    onStartMock: () -> Unit,
    onStopMock: () -> Unit
) {
    var name by rememberSaveable { mutableStateOf("") }
    OutlinedTextField(
        value = pointText,
        onValueChange = {},
        readOnly = true,
        label = { Text("当前选点") },
        modifier = Modifier.fillMaxWidth()
    )

    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
        OutlinedTextField(
            value = name,
            onValueChange = { name = it },
            label = { Text("收藏名") },
            modifier = Modifier.weight(1f)
        )
        Button(onClick = { onSaveFavorite(name.ifBlank { "未命名收藏" }) }) { Text("收藏") }
    }

    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
        Button(onClick = onStartMock, modifier = Modifier.weight(1f)) { Text("开始模拟") }
        Button(onClick = onStopMock, modifier = Modifier.weight(1f)) { Text("停止模拟") }
    }
}

@Composable
private fun FavoriteSection(
    favorites: List<FavoriteItem>,
    onPick: (FavoriteItem) -> Unit,
    onDelete: (Long) -> Unit
) {
    Text("收藏位置")
    HorizontalDivider()
    LazyColumn(modifier = Modifier.height(120.dp)) {
        items(favorites, key = { it.id }) { item ->
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                TextButton(onClick = { onPick(item) }, modifier = Modifier.weight(1f)) {
                    Text("${item.name}: ${item.point.latitude}, ${item.point.longitude}")
                }
                TextButton(onClick = { onDelete(item.id) }, modifier = Modifier.width(56.dp)) {
                    Text("删")
                }
            }
        }
    }
}

@Composable
private fun AMapPicker(modifier: Modifier, onPointClick: (LatLng) -> Unit) {
    val context = LocalContext.current
    val mapView = remember {
        MapView(context).apply {
            onCreate(null)
            map.uiSettings.isZoomControlsEnabled = false
            map.setOnMapClickListener { latLng ->
                map.clear()
                map.addMarker(MarkerOptions().position(latLng))
                onPointClick(latLng)
            }
        }
    }

    DisposableEffect(Unit) {
        mapView.onResume()
        onDispose {
            mapView.onPause()
            mapView.onDestroy()
        }
    }

    AndroidView(modifier = modifier, factory = { mapView })
}
