package com.bol.app.data.local.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.PrimaryKey

/**
 * One dense embedding per item. Kept in a separate table (vs. a BLOB column
 * on `items`) so:
 *   - schema changes are local to the vector store and don't churn the items
 *     table's migrations
 *   - we can store per-model-version rows side-by-side during a model upgrade
 *   - deletion cascades automatically via the items FK; orphaned rows can't
 *     accumulate
 *
 * [embedding] is packed as Little-Endian Float32 (4 bytes per dim) — we don't
 * gain anything from int8 quant at 10K-item scale, and 4× faster cosine on
 * the hot path trumps the storage cost.
 */
@Entity(
    tableName = "item_embeddings",
    foreignKeys = [
        ForeignKey(
            entity = ItemEntity::class,
            parentColumns = ["id"],
            childColumns = ["itemId"],
            onDelete = ForeignKey.CASCADE,
        )
    ],
)
data class ItemEmbeddingEntity(
    @PrimaryKey val itemId: String,
    val embedding: ByteArray,
    val modelVersion: Int,
    val dim: Int,
    val createdAt: Long = System.currentTimeMillis(),
) {
    // Room requires content-equality so change detection works for BLOB fields.
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is ItemEmbeddingEntity) return false
        return itemId == other.itemId &&
            modelVersion == other.modelVersion &&
            dim == other.dim &&
            createdAt == other.createdAt &&
            embedding.contentEquals(other.embedding)
    }

    override fun hashCode(): Int {
        var result = itemId.hashCode()
        result = 31 * result + modelVersion
        result = 31 * result + dim
        result = 31 * result + createdAt.hashCode()
        result = 31 * result + embedding.contentHashCode()
        return result
    }
}
