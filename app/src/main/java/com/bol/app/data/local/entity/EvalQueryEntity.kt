package com.bol.app.data.local.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * A synthetic query the model generated for an item at index time. Together
 * with its source [itemId], it forms a (query → expected-result) pair for
 * measuring retrieval quality.
 *
 * Generated once per item on a "Run evaluation" trigger. Subsequent eval
 * runs reuse the stored queries — regeneration is expensive, scoring is
 * cheap. The generator is versioned via [generatorVersion] so we can
 * rebuild the query set if we change the generator prompt.
 */
@Entity(
    tableName = "eval_queries",
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
        Index("generatorVersion"),
    ],
)
data class EvalQueryEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val itemId: String,
    val query: String,
    val generatorVersion: Int,
    val createdAt: Long = System.currentTimeMillis(),
)
