package com.snake.mobilespoofing.core.model

/** A user-saved position. */
data class FavoriteItem(
    val id: Long,
    val name: String,
    val point: GeoPoint,
)
