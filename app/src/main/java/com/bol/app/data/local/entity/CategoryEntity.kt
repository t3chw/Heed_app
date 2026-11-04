package com.bol.app.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "categories")
data class CategoryEntity(
    @PrimaryKey val id: String,
    val name: String,
    val icon: String,
    val color: String,
    val sortOrder: Int,
    val isDefault: Boolean,
    val isActive: Boolean = true,
    val description: String = ""
)
