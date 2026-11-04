package com.bol.app.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.bol.app.data.local.entity.ItemTagEntity

@Dao
interface ItemTagDao {

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertAll(rows: List<ItemTagEntity>)

    @Query("DELETE FROM item_tags WHERE itemId = :itemId")
    suspend fun deleteForItem(itemId: String)

    @Query("SELECT tag FROM item_tags WHERE itemId = :itemId")
    suspend fun tagsForItem(itemId: String): List<String>

    /**
     * Reverse lookup used by search: find items whose tags overlap the
     * query tag set by AT LEAST [minMatches] tags. Ranked by how many
     * matched — an item covering 3 of the query's tags outranks one
     * covering 1.
     *
     * The [minMatches] threshold is the guardrail against the q05-style
     * noise we saw in the multi-config bench: when a broad tag like `chore`
     * matches a single query token, it drags in every chore-ish item
     * across categories. Callers pass `minMatches = 2` when the query
     * produced ≥2 tags, so only items that hit multiple facets of the
     * query surface — single-tag noise is filtered out.
     */
    @Query(
        """
        SELECT itemId, COUNT(*) AS matches
          FROM item_tags
          WHERE tag IN (:tags)
          GROUP BY itemId
          HAVING COUNT(*) >= :minMatches
          ORDER BY matches DESC, itemId
          LIMIT :limit
        """
    )
    suspend fun itemIdsByTags(tags: List<String>, limit: Int, minMatches: Int = 1): List<ItemIdMatch>

    /** Items that have no tags yet — drives the backfill queue. */
    @Query(
        """
        SELECT items.id FROM items
          LEFT JOIN item_tags ON item_tags.itemId = items.id
          WHERE item_tags.itemId IS NULL
          ORDER BY items.createdAt DESC
          LIMIT :limit
        """
    )
    suspend fun untaggedItemIds(limit: Int): List<String>

    @Query("SELECT COUNT(DISTINCT itemId) FROM item_tags")
    suspend fun distinctTaggedItems(): Int

    /** Every tag row — used by backup. Order is stable so backups are
     *  byte-deterministic for the same DB state, which makes diffing
     *  successive backups easy. */
    @Query("SELECT * FROM item_tags ORDER BY id ASC")
    suspend fun getAllSnapshot(): List<ItemTagEntity>

    /** Reactive variant — used by [com.bol.app.ai.indexing.IndexingCoordinator]
     *  so the banner's pending count drops the instant tagOne/tagMany
     *  inserts complete, without waiting for an explicit refresh call. */
    @Query("SELECT COUNT(DISTINCT itemId) FROM item_tags")
    fun distinctTaggedItemsFlow(): kotlinx.coroutines.flow.Flow<Int>
}

/** Projection for ranked tag-based item lookups. */
data class ItemIdMatch(
    val itemId: String,
    val matches: Int,
)
