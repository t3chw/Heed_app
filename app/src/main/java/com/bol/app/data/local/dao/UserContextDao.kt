package com.bol.app.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.bol.app.data.local.entity.UserContextEntity

@Dao
interface UserContextDao {
    @Query("SELECT value FROM user_context WHERE `key` = :key")
    suspend fun getValue(key: String): String?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entry: UserContextEntity)

    /** Bulk-insert used by backup restore. IGNORE so a key that's
     *  already set locally keeps its current (likely fresher) value;
     *  the import pipeline pre-filters by key for the common case. */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertAll(rows: List<UserContextEntity>)

    @Query("DELETE FROM user_context")
    suspend fun deleteAll()

    /** Every user-context row — used by backup. Backticks around `key`
     *  because it's a SQLite reserved word. */
    @Query("SELECT * FROM user_context ORDER BY `key` ASC")
    suspend fun getAllSnapshot(): List<UserContextEntity>
}
