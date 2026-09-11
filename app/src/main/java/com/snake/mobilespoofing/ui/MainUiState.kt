package com.snake.mobilespoofing.ui

import com.snake.mobilespoofing.core.model.CoordType
import com.snake.mobilespoofing.core.model.FavoriteItem
import com.snake.mobilespoofing.core.model.GeoPoint
import com.snake.mobilespoofing.core.model.MockStatus

data class MainUiState(
    val selectedPoint: GeoPoint? = null,
    val selectedCoordType: CoordType = CoordType.GCJ02,
    val status: MockStatus = MockStatus.Idle,
    val favorites: List<FavoriteItem> = emptyList(),
    val searchKeyword: String = "",
    val searchResults: List<SearchResultItem> = emptyList(),
    val permissionGranted: Boolean = false,
    val canMock: Boolean = false,
    val message: String? = null
)

data class SearchResultItem(
    val title: String,
    val address: String,
    val point: GeoPoint
)
