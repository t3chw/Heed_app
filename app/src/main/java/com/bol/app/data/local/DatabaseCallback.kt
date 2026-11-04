package com.bol.app.data.local

import androidx.room.RoomDatabase
import androidx.sqlite.db.SupportSQLiteDatabase
import com.bol.app.data.local.dao.CategoryDao
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class DatabaseCallback(
    private val scope: CoroutineScope,
    private val daoProvider: () -> CategoryDao
) : RoomDatabase.Callback() {
    override fun onCreate(db: SupportSQLiteDatabase) {
        super.onCreate(db)

        // No full-text tables here any more: search has its own index (ai/retrieval/SearchIndex), and the FTS4
        // tables this used to create were written on every save and never read (dropped by MIGRATION_20_21).

        scope.launch(Dispatchers.IO) {
            daoProvider().insertAll(DefaultCategories.list)
        }
    }
}
