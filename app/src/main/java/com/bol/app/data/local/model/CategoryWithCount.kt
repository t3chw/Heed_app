package com.bol.app.data.local.model

data class CategoryWithCount(
    val id: String,
    val name: String,
    val icon: String,
    val color: String,
    val sortOrder: Int,
    val isDefault: Boolean,
    val isActive: Boolean,
    val description: String,
    val itemCount: Int,
    val latestItemText: String?
)
