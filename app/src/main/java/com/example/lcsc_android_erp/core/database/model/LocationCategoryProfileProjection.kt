package com.example.lcsc_android_erp.core.database.model

data class LocationCategoryProfileProjection(
    val locationId: Long,
    val category: String?,
    val packageName: String?,
    val quantity: Int
)
