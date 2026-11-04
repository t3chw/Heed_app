package com.bol.app.data.local.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * One row per (item, tag). Tags come from the closed [TagVocabulary].
 * Cascade delete on the parent item prevents orphaned tags.
 *
 * Indexed on both columns: `itemId` for joining back to items, `tag`
 * for reverse-lookup ("give me all items with tag=outdoor").
 */
@Entity(
    tableName = "item_tags",
    foreignKeys = [
        ForeignKey(
            entity = ItemEntity::class,
            parentColumns = ["id"],
            childColumns = ["itemId"],
            onDelete = ForeignKey.CASCADE,
        )
    ],
    indices = [
        Index("itemId"),
        Index("tag"),
        Index(value = ["itemId", "tag"], unique = true),
    ],
)
data class ItemTagEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val itemId: String,
    val tag: String,
    val createdAt: Long = System.currentTimeMillis(),
)
