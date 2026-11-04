package com.bol.app.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.bol.app.data.local.entity.ItemEnrichmentEntity

@Dao
interface ItemEnrichmentDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: ItemEnrichmentEntity)

    /** Bulk-insert used by backup restore. IGNORE so a row whose
     *  itemId already has an enrichment locally is preserved (the
     *  user's existing enrichment may be more current than the
     *  imported one — pre-filter handles the common case). */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertAll(rows: List<ItemEnrichmentEntity>)

    @Query("DELETE FROM item_enrichments WHERE itemId = :itemId")
    suspend fun deleteForItem(itemId: String)

    @Query("SELECT * FROM item_enrichments WHERE itemId = :itemId")
    suspend fun getForItem(itemId: String): ItemEnrichmentEntity?

    /** Item IDs that don't yet have an enrichment — drives the backfill queue. */
    @Query(
        """
        SELECT items.id FROM items
          LEFT JOIN item_enrichments ON item_enrichments.itemId = items.id
          WHERE item_enrichments.itemId IS NULL
          ORDER BY items.createdAt DESC
          LIMIT :limit
        """
    )
    suspend fun missingItemIds(limit: Int): List<String>

    @Query("SELECT COUNT(*) FROM item_enrichments")
    suspend fun totalEnriched(): Int

    /** Reactive variant for the indexing banner. Re-emits the moment an
     *  enrichment row is inserted so pending count drops in real time. */
    @Query("SELECT COUNT(*) FROM item_enrichments")
    fun totalEnrichedFlow(): kotlinx.coroutines.flow.Flow<Int>

    /**
     * Snapshot of every enrichment blob. Used by the LIKE-based
     * [EnrichmentSearch] path — 50–10k rows of ~200 chars each scans in
     * single-digit milliseconds, so we don't need the FTS5 index (which
     * isn't available on every Android SQLite build anyway).
     */
    @Query("SELECT * FROM item_enrichments")
    suspend fun getAll(): List<ItemEnrichmentEntity>

    /** Alias for [getAll] used by the backup pipeline so every backed-up
     *  DAO follows the same `getAllSnapshot` convention. */
    @Query("SELECT * FROM item_enrichments ORDER BY itemId ASC")
    suspend fun getAllSnapshot(): List<ItemEnrichmentEntity>
}
