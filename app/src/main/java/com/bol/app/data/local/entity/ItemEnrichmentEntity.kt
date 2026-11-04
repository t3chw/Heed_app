package com.bol.app.data.local.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.PrimaryKey

/**
 * Pre-computed "search context" for an item. At save time, the model is asked
 * to describe the item as a search query would — related concepts, synonyms,
 * phrasings, intent framings. This text lives in its own FTS index so a
 * query like "outdoor activities" can match an item like "wash the car"
 * whose enrichment blob contains "outdoor chore, weekend task, car washing".
 *
 * The expensive the model work happens ONCE per item at index time, never at
 * query time — query-time model calls were unreliable (LiteRT-LM hangs),
 * and moving the work here puts us on the same code path that categorization
 * uses, which has been stable.
 */
@Entity(
    tableName = "item_enrichments",
    foreignKeys = [
        ForeignKey(
            entity = ItemEntity::class,
            parentColumns = ["id"],
            childColumns = ["itemId"],
            onDelete = ForeignKey.CASCADE,
        )
    ],
)
data class ItemEnrichmentEntity(
    @PrimaryKey val itemId: String,
    val searchContext: String,
    val createdAt: Long = System.currentTimeMillis(),
)
