package com.bol.app.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.bol.app.data.local.entity.CategoryEntity
import com.bol.app.data.local.model.CategoryWithCount
import kotlinx.coroutines.flow.Flow

@Dao
interface CategoryDao {
    @Query("SELECT * FROM categories WHERE isActive = 1 ORDER BY sortOrder ASC")
    fun getAllActive(): Flow<List<CategoryEntity>>

    @Query("""
        SELECT c.id, c.name, c.icon, c.color, c.sortOrder, c.isDefault, c.isActive, c.description,
               COUNT(CASE WHEN i.isCompleted = 0 THEN 1 END) AS itemCount,
               (SELECT text FROM items WHERE categoryId = c.id AND isCompleted = 0 ORDER BY createdAt DESC LIMIT 1) AS latestItemText
        FROM categories c
        LEFT JOIN items i ON c.id = i.categoryId
        WHERE c.isActive = 1
        GROUP BY c.id
        ORDER BY c.sortOrder ASC
    """)
    fun getCategoriesWithCounts(): Flow<List<CategoryWithCount>>

    @Query("SELECT * FROM categories WHERE id = :id")
    suspend fun getById(id: String): CategoryEntity?

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertAll(categories: List<CategoryEntity>)

    @Update
    suspend fun update(category: CategoryEntity)

    @Query("SELECT COUNT(*) FROM categories")
    suspend fun getCount(): Int

    @Query("SELECT COUNT(*) FROM categories WHERE isActive = 1")
    suspend fun getActiveCount(): Int

    @Query("UPDATE categories SET isActive = 0 WHERE id = :id")
    suspend fun deactivate(id: String)

    @Query("UPDATE categories SET isActive = 1 WHERE id = :id")
    suspend fun activate(id: String)

    @Query("DELETE FROM items WHERE categoryId = :categoryId")
    suspend fun deleteItemsByCategory(categoryId: String)

    @Query("SELECT * FROM categories WHERE LOWER(name) = LOWER(:name) LIMIT 1")
    suspend fun findByName(name: String): CategoryEntity?

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(category: CategoryEntity)

    @Query("SELECT * FROM categories WHERE isActive = 1 ORDER BY sortOrder ASC")
    suspend fun getAllActiveSnapshot(): List<CategoryEntity>

    /** Every category — including soft-deleted (`isActive = 0`) ones.
     *  Used by backup so items that still reference a deactivated
     *  category don't end up with a dangling FK in the export. */
    @Query("SELECT * FROM categories ORDER BY sortOrder ASC")
    suspend fun getAllSnapshot(): List<CategoryEntity>

    @Query("DELETE FROM categories")
    suspend fun deleteAll()
}
