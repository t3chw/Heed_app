package com.bol.app.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.bol.app.data.local.entity.ItemEmbeddingEntity

@Dao
interface ItemEmbeddingDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: ItemEmbeddingEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(entities: List<ItemEmbeddingEntity>)

    @Query("SELECT * FROM item_embeddings WHERE modelVersion = :modelVersion")
    suspend fun getAllForModel(modelVersion: Int): List<ItemEmbeddingEntity>

    /** Item IDs that don't yet have an embedding for [modelVersion] — backfill target. */
    @Query(
        """
        SELECT items.id FROM items
          LEFT JOIN item_embeddings ON item_embeddings.itemId = items.id
            AND item_embeddings.modelVersion = :modelVersion
          WHERE item_embeddings.itemId IS NULL
          ORDER BY items.createdAt DESC
          LIMIT :limit
        """
    )
    suspend fun missingItemIds(modelVersion: Int, limit: Int): List<String>

    @Query("SELECT COUNT(*) FROM item_embeddings WHERE modelVersion = :modelVersion")
    suspend fun countForModel(modelVersion: Int): Int

    @Query("DELETE FROM item_embeddings WHERE modelVersion = :modelVersion")
    suspend fun deleteModel(modelVersion: Int)
}
