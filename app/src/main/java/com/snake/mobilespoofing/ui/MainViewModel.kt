package com.snake.mobilespoofing.ui

import android.Manifest
import android.app.Application
import android.content.Context
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.amap.api.services.core.AMapException
import com.amap.api.services.core.PoiItem
import com.amap.api.services.poisearch.PoiResult
import com.amap.api.services.poisearch.PoiSearch
import com.snake.mobilespoofing.SpoofingApp
import com.snake.mobilespoofing.data.FavoriteItem
import com.snake.mobilespoofing.data.FavoriteRepository
import com.snake.mobilespoofing.domain.StartMockUseCase
import com.snake.mobilespoofing.domain.StopMockUseCase
import com.snake.mobilespoofing.location.CoordType
import com.snake.mobilespoofing.location.GeoPoint
import com.snake.mobilespoofing.location.MockStatusStore
import com.snake.mobilespoofing.settings.MockPermissionChecker
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

class MainViewModel(application: Application) : AndroidViewModel(application) {
    private val container = (application as SpoofingApp).container
    private val favoriteRepository: FavoriteRepository = container.favoriteRepository
    private val startMockUseCase: StartMockUseCase = container.startMockUseCase
    private val stopMockUseCase: StopMockUseCase = container.stopMockUseCase
    private val statusStore: MockStatusStore = container.statusStore

    private val _uiState = MutableStateFlow(MainUiState())
    val uiState: StateFlow<MainUiState> = _uiState.asStateFlow()

    init {
        observeStatus()
        observeFavorites()
        refreshCapabilities()
    }

    fun onMapPointSelected(latitude: Double, longitude: Double) {
        _uiState.update { it.copy(selectedPoint = GeoPoint(latitude, longitude, it.selectedCoordType), message = null) }
    }

    fun onCoordTypeChanged(type: CoordType) {
        _uiState.update { current ->
            val selected = current.selectedPoint?.copy(coordType = type)
            current.copy(selectedCoordType = type, selectedPoint = selected)
        }
    }

    fun onSearchKeywordChange(value: String) {
        _uiState.update { it.copy(searchKeyword = value) }
    }

    fun searchPoi(context: Context) {
        val keyword = _uiState.value.searchKeyword.trim()
        if (keyword.isEmpty()) {
            _uiState.update { it.copy(message = "请输入搜索关键字") }
            return
        }

        val query = PoiSearch.Query(keyword, "", "")
        val poiSearch = PoiSearch(context, query)
        poiSearch.setOnPoiSearchListener(object : PoiSearch.OnPoiSearchListener {
            override fun onPoiSearched(result: PoiResult?, code: Int) {
                if (code != AMapException.CODE_AMAP_SUCCESS || result == null) {
                    _uiState.update { it.copy(message = "搜索失败，错误码：$code") }
                    return
                }
                val mapped = result.pois.orEmpty().mapNotNull { item: PoiItem? ->
                    val latLon = item?.latLonPoint ?: return@mapNotNull null
                    SearchResultItem(
                        title = item.title ?: "未命名",
                        address = item.snippet ?: "",
                        point = GeoPoint(latLon.latitude, latLon.longitude, CoordType.GCJ02)
                    )
                }
                _uiState.update { it.copy(searchResults = mapped, message = null) }
            }

            override fun onPoiItemSearched(p0: PoiItem?, p1: Int) = Unit
        })
        poiSearch.searchPOIAsyn()
    }

    fun onSelectSearchResult(item: SearchResultItem) {
        _uiState.update { it.copy(selectedPoint = item.point, message = null) }
    }

    fun saveFavorite(name: String) {
        val target = _uiState.value.selectedPoint ?: run {
            _uiState.update { it.copy(message = "请先选择位置") }
            return
        }
        viewModelScope.launch {
            favoriteRepository.save(name, target)
            _uiState.update { it.copy(message = "已收藏") }
        }
    }

    fun removeFavorite(id: Long) {
        viewModelScope.launch { favoriteRepository.delete(id) }
    }

    fun pickFavorite(item: FavoriteItem) {
        _uiState.update { it.copy(selectedPoint = item.point) }
    }

    fun startMock() {
        val state = _uiState.value
        if (!state.permissionGranted) {
            _uiState.update { it.copy(message = "请先授予定位权限") }
            return
        }
        if (!state.canMock) {
            _uiState.update { it.copy(message = "请在开发者选项中将本应用设为模拟位置应用") }
            return
        }
        val point = state.selectedPoint ?: run {
            _uiState.update { it.copy(message = "请先在地图上选点") }
            return
        }
        startMockUseCase(point)
    }

    fun stopMock() {
        stopMockUseCase()
    }

    fun refreshCapabilities() {
        val context = getApplication<Application>()
        val hasLocationPermission = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        val canMock = MockPermissionChecker(context).canMockLocation()
        _uiState.update { it.copy(permissionGranted = hasLocationPermission, canMock = canMock) }
    }

    fun clearMessage() {
        _uiState.update { it.copy(message = null) }
    }

    private fun observeStatus() {
        viewModelScope.launch {
            statusStore.status.collect { status ->
                _uiState.update { it.copy(status = status) }
            }
        }
    }

    private fun observeFavorites() {
        viewModelScope.launch {
            favoriteRepository.observeAll().collect { favorites ->
                _uiState.update { it.copy(favorites = favorites) }
            }
        }
    }
}
