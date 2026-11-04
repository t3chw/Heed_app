package com.bol.app.data.local

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.bol.app.data.local.dao.CategoryDao
import com.bol.app.data.local.dao.ItemDao
import com.bol.app.data.local.dao.EvalQueryDao
import com.bol.app.data.local.dao.ItemEmbeddingDao
import com.bol.app.data.local.dao.ItemEnrichmentDao
import com.bol.app.data.local.dao.ItemTagDao
import com.bol.app.data.local.dao.LlmUpgradeDao
import com.bol.app.data.local.dao.PerformanceLogDao
import com.bol.app.data.local.dao.RecordingDao
import com.bol.app.data.local.dao.RecordingTagDao
import com.bol.app.data.local.dao.SearchQueryDao
import com.bol.app.data.local.dao.UserContextDao
import com.bol.app.data.local.dao.VoiceQueueDao
import com.bol.app.data.local.entity.CategoryEntity
import com.bol.app.data.local.entity.EvalQueryEntity
import com.bol.app.data.local.entity.ItemEmbeddingEntity
import com.bol.app.data.local.entity.ItemEnrichmentEntity
import com.bol.app.data.local.entity.ItemEntity
import com.bol.app.data.local.entity.ItemTagEntity
import com.bol.app.data.local.entity.LlmUpgradeEntity
import com.bol.app.data.local.entity.RecordingEntity
import com.bol.app.data.local.entity.UserContextEntity
import com.bol.app.data.local.entity.RecordingTagEntity
import com.bol.app.data.local.entity.SearchQueryEntity
import com.bol.app.data.local.entity.PerformanceLogEntity
import com.bol.app.data.local.entity.VoiceQueueEntity

@Database(
    entities = [
        CategoryEntity::class,
        EvalQueryEntity::class,
        ItemEntity::class,
        ItemEmbeddingEntity::class,
        ItemEnrichmentEntity::class,
        ItemTagEntity::class,
        LlmUpgradeEntity::class,
        PerformanceLogEntity::class,
        RecordingEntity::class,
        RecordingTagEntity::class,
        SearchQueryEntity::class,
        UserContextEntity::class,
        VoiceQueueEntity::class
    ],
    version = 21,
    exportSchema = false
)
abstract class BolDatabase : RoomDatabase() {
    abstract fun categoryDao(): CategoryDao
    abstract fun evalQueryDao(): EvalQueryDao
    abstract fun itemDao(): ItemDao
    abstract fun itemEmbeddingDao(): ItemEmbeddingDao
    abstract fun itemEnrichmentDao(): ItemEnrichmentDao
    abstract fun itemTagDao(): ItemTagDao
    abstract fun llmUpgradeDao(): LlmUpgradeDao
    abstract fun performanceLogDao(): PerformanceLogDao
    abstract fun recordingDao(): RecordingDao
    abstract fun recordingTagDao(): RecordingTagDao
    abstract fun userContextDao(): UserContextDao
    abstract fun voiceQueueDao(): VoiceQueueDao
    abstract fun searchQueryDao(): SearchQueryDao

    companion object {
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS `voice_queue` (
                        `id` TEXT NOT NULL PRIMARY KEY,
                        `rawText` TEXT NOT NULL,
                        `status` TEXT NOT NULL DEFAULT 'PENDING',
                        `parsedItemCount` INTEGER NOT NULL DEFAULT 0,
                        `errorMessage` TEXT,
                        `retryCount` INTEGER NOT NULL DEFAULT 0,
                        `createdAt` INTEGER NOT NULL,
                        `processedAt` INTEGER
                    )
                """.trimIndent())
            }
        }

        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE voice_queue ADD COLUMN recordingId TEXT")
                // Seed Journal category for existing users
                db.execSQL("""
                    INSERT OR IGNORE INTO categories (id, name, icon, color, sortOrder, isDefault, isActive)
                    VALUES ('journal', 'Journal', '📔', '#8E44AD', 6, 1, 1)
                """.trimIndent())
            }
        }

        val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE categories ADD COLUMN description TEXT NOT NULL DEFAULT ''")
                // Backfill descriptions for default categories
                db.execSQL("UPDATE categories SET description = 'things to do' WHERE id = 'tasks'")
                db.execSQL("UPDATE categories SET description = 'things to purchase' WHERE id = 'buy'")
                db.execSQL("UPDATE categories SET description = 'movies, shows, videos to see' WHERE id = 'watch'")
                db.execSQL("UPDATE categories SET description = 'thoughts to remember' WHERE id = 'ideas'")
                db.execSQL("UPDATE categories SET description = 'office and job tasks' WHERE id = 'work'")
                db.execSQL("UPDATE categories SET description = 'time-based reminders' WHERE id = 'reminders'")
                db.execSQL("UPDATE categories SET description = 'feelings, emotions, stories with no action' WHERE id = 'journal'")
            }
        }

        val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS `recording_tags` (
                        `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        `recordingId` TEXT NOT NULL,
                        `tag` TEXT NOT NULL,
                        `tagType` TEXT NOT NULL DEFAULT 'person',
                        FOREIGN KEY(`recordingId`) REFERENCES `recordings`(`id`) ON DELETE CASCADE
                    )
                """.trimIndent())
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_recording_tags_recordingId` ON `recording_tags` (`recordingId`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_recording_tags_tag` ON `recording_tags` (`tag`)")
            }
        }

        val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS `search_queries` (
                        `id` TEXT NOT NULL PRIMARY KEY,
                        `query` TEXT NOT NULL,
                        `answer` TEXT,
                        `sourcesJson` TEXT,
                        `status` TEXT NOT NULL DEFAULT 'PENDING',
                        `errorMessage` TEXT,
                        `createdAt` INTEGER NOT NULL,
                        `completedAt` INTEGER
                    )
                """.trimIndent())
            }
        }

        val MIGRATION_6_7 = object : Migration(6, 7) {
            override fun migrate(db: SupportSQLiteDatabase) {
                try {
                    // FTS4 full-text search on recordings — Porter stemmer + Unicode
                    db.execSQL("CREATE VIRTUAL TABLE IF NOT EXISTS recordings_fts USING fts4(rawText, content='recordings', tokenize=porter)")
                    db.execSQL("CREATE TRIGGER IF NOT EXISTS recordings_fts_ai AFTER INSERT ON recordings BEGIN INSERT INTO recordings_fts(docid, rawText) VALUES (new.rowid, new.rawText); END")
                    db.execSQL("CREATE TRIGGER IF NOT EXISTS recordings_fts_ad AFTER DELETE ON recordings BEGIN DELETE FROM recordings_fts WHERE docid = old.rowid; END")
                    db.execSQL("CREATE TRIGGER IF NOT EXISTS recordings_fts_au AFTER UPDATE OF rawText ON recordings BEGIN DELETE FROM recordings_fts WHERE docid = old.rowid; INSERT INTO recordings_fts(docid, rawText) VALUES (new.rowid, new.rawText); END")
                    db.execSQL("INSERT INTO recordings_fts(docid, rawText) SELECT rowid, rawText FROM recordings")

                    // FTS4 full-text search on items
                    db.execSQL("CREATE VIRTUAL TABLE IF NOT EXISTS items_fts USING fts4(text, content='items', tokenize=porter)")
                    db.execSQL("CREATE TRIGGER IF NOT EXISTS items_fts_ai AFTER INSERT ON items BEGIN INSERT INTO items_fts(docid, text) VALUES (new.rowid, new.text); END")
                    db.execSQL("CREATE TRIGGER IF NOT EXISTS items_fts_ad AFTER DELETE ON items BEGIN DELETE FROM items_fts WHERE docid = old.rowid; END")
                    db.execSQL("CREATE TRIGGER IF NOT EXISTS items_fts_au AFTER UPDATE OF text ON items BEGIN DELETE FROM items_fts WHERE docid = old.rowid; INSERT INTO items_fts(docid, text) VALUES (new.rowid, new.text); END")
                    db.execSQL("INSERT INTO items_fts(docid, text) SELECT rowid, text FROM items")
                } catch (e: Exception) {
                    // FTS not available — search falls back to LIKE queries
                }
            }
        }

        val MIGRATION_7_8 = object : Migration(7, 8) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // Mark items as provisional (rule-based) or permanent (LLM-categorized)
                db.execSQL("ALTER TABLE items ADD COLUMN isProvisional INTEGER NOT NULL DEFAULT 0")

                // Queue for background LLM re-categorization of rule-based items
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS `llm_upgrade_queue` (
                        `id` TEXT NOT NULL PRIMARY KEY,
                        `recordingId` TEXT NOT NULL,
                        `rawText` TEXT NOT NULL,
                        `status` TEXT NOT NULL DEFAULT 'PENDING',
                        `retryCount` INTEGER NOT NULL DEFAULT 0,
                        `errorMessage` TEXT,
                        `createdAt` INTEGER NOT NULL,
                        `processedAt` INTEGER
                    )
                """.trimIndent())
            }
        }

        val MIGRATION_8_9 = object : Migration(8, 9) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS `performance_logs` (
                        `id` TEXT NOT NULL PRIMARY KEY,
                        `timestamp` INTEGER NOT NULL,
                        `level` TEXT NOT NULL,
                        `category` TEXT NOT NULL,
                        `event` TEXT NOT NULL,
                        `message` TEXT NOT NULL,
                        `durationMs` INTEGER,
                        `metricsJson` TEXT,
                        `extrasJson` TEXT,
                        `processName` TEXT NOT NULL
                    )
                """.trimIndent())
                db.execSQL("CREATE INDEX IF NOT EXISTS `idx_perf_logs_timestamp` ON `performance_logs` (`timestamp`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `idx_perf_logs_category` ON `performance_logs` (`category`)")
            }
        }

        /** v10 — bypassStt flag for share-to-Bol (text-only queue entries that
         *  skip Whisper). Default 0 preserves existing audio-path entries. */
        val MIGRATION_9_10 = object : Migration(9, 10) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `voice_queue` ADD COLUMN `bypassStt` INTEGER NOT NULL DEFAULT 0")
            }
        }

        /**
         * v11 — upgrade FTS4 → FTS5 for items_fts and recordings_fts so we get
         * built-in `bm25()` ranking. FTS5 uses `rowid` (not `docid`) and a
         * different delete semantics for external-content tables (requires the
         * `'delete'` command rather than plain DELETE). Triggers, virtual
         * tables, and the index content are all rebuilt from the source rows.
         *
         * Falls through silently if FTS5 isn't available — search falls back
         * to LIKE, same as the original migration's guard.
         */
        val MIGRATION_10_11 = object : Migration(10, 11) {
            override fun migrate(db: SupportSQLiteDatabase) {
                try {
                    // Drop old FTS4 tables + triggers so we can recreate cleanly.
                    db.execSQL("DROP TRIGGER IF EXISTS recordings_fts_ai")
                    db.execSQL("DROP TRIGGER IF EXISTS recordings_fts_ad")
                    db.execSQL("DROP TRIGGER IF EXISTS recordings_fts_au")
                    db.execSQL("DROP TABLE IF EXISTS recordings_fts")

                    db.execSQL("DROP TRIGGER IF EXISTS items_fts_ai")
                    db.execSQL("DROP TRIGGER IF EXISTS items_fts_ad")
                    db.execSQL("DROP TRIGGER IF EXISTS items_fts_au")
                    db.execSQL("DROP TABLE IF EXISTS items_fts")

                    // FTS5 virtual tables, external-content linked via rowid.
                    // Porter stemmer + unicode61 = handles both ASCII stemming
                    // ("movies"→"movi") and Unicode tokenization (Devanagari).
                    db.execSQL(
                        """
                        CREATE VIRTUAL TABLE IF NOT EXISTS recordings_fts USING fts5(
                            rawText,
                            content='recordings',
                            content_rowid='rowid',
                            tokenize='porter unicode61'
                        )
                        """.trimIndent()
                    )
                    db.execSQL(
                        """
                        CREATE VIRTUAL TABLE IF NOT EXISTS items_fts USING fts5(
                            text,
                            content='items',
                            content_rowid='rowid',
                            tokenize='porter unicode61'
                        )
                        """.trimIndent()
                    )

                    // External-content triggers. FTS5 requires the 'delete'
                    // command to remove a row from the index — plain DELETE
                    // FROM items_fts wouldn't work on external-content tables.
                    db.execSQL(
                        """
                        CREATE TRIGGER IF NOT EXISTS recordings_fts_ai AFTER INSERT ON recordings BEGIN
                            INSERT INTO recordings_fts(rowid, rawText) VALUES (new.rowid, new.rawText);
                        END
                        """.trimIndent()
                    )
                    db.execSQL(
                        """
                        CREATE TRIGGER IF NOT EXISTS recordings_fts_ad AFTER DELETE ON recordings BEGIN
                            INSERT INTO recordings_fts(recordings_fts, rowid, rawText) VALUES ('delete', old.rowid, old.rawText);
                        END
                        """.trimIndent()
                    )
                    db.execSQL(
                        """
                        CREATE TRIGGER IF NOT EXISTS recordings_fts_au AFTER UPDATE OF rawText ON recordings BEGIN
                            INSERT INTO recordings_fts(recordings_fts, rowid, rawText) VALUES ('delete', old.rowid, old.rawText);
                            INSERT INTO recordings_fts(rowid, rawText) VALUES (new.rowid, new.rawText);
                        END
                        """.trimIndent()
                    )

                    db.execSQL(
                        """
                        CREATE TRIGGER IF NOT EXISTS items_fts_ai AFTER INSERT ON items BEGIN
                            INSERT INTO items_fts(rowid, text) VALUES (new.rowid, new.text);
                        END
                        """.trimIndent()
                    )
                    db.execSQL(
                        """
                        CREATE TRIGGER IF NOT EXISTS items_fts_ad AFTER DELETE ON items BEGIN
                            INSERT INTO items_fts(items_fts, rowid, text) VALUES ('delete', old.rowid, old.text);
                        END
                        """.trimIndent()
                    )
                    db.execSQL(
                        """
                        CREATE TRIGGER IF NOT EXISTS items_fts_au AFTER UPDATE OF text ON items BEGIN
                            INSERT INTO items_fts(items_fts, rowid, text) VALUES ('delete', old.rowid, old.text);
                            INSERT INTO items_fts(rowid, text) VALUES (new.rowid, new.text);
                        END
                        """.trimIndent()
                    )

                    // Rebuild indexes from existing rows.
                    db.execSQL("INSERT INTO recordings_fts(rowid, rawText) SELECT rowid, rawText FROM recordings")
                    db.execSQL("INSERT INTO items_fts(rowid, text) SELECT rowid, text FROM items")
                } catch (_: Exception) {
                    // FTS5 not available on this SQLite build — searches fall
                    // back to LIKE queries in the repository layer.
                }
            }
        }

        /**
         * v12 — dense-embedding store for hybrid retrieval. One row per
         * (item, modelVersion) so future model upgrades (MiniLM → Embeddingthe model)
         * can store new vectors side-by-side. ON DELETE CASCADE from items
         * keeps orphan rows out of the index.
         */
        val MIGRATION_11_12 = object : Migration(11, 12) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `item_embeddings` (
                        `itemId` TEXT NOT NULL PRIMARY KEY,
                        `embedding` BLOB NOT NULL,
                        `modelVersion` INTEGER NOT NULL,
                        `dim` INTEGER NOT NULL,
                        `createdAt` INTEGER NOT NULL,
                        FOREIGN KEY(`itemId`) REFERENCES `items`(`id`) ON DELETE CASCADE
                    )
                    """.trimIndent()
                )
            }
        }

        /**
         * v13 — `item_tags` table for LLM-generated semantic tags.
         * One row per (item, tag); tag values come from a closed vocabulary
         * (see TagVocabulary). Indices on itemId and tag for both directions
         * of lookup. Unique composite index on (itemId, tag) so repeated
         * backfills don't insert duplicates.
         */
        val MIGRATION_12_13 = object : Migration(12, 13) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `item_tags` (
                        `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        `itemId` TEXT NOT NULL,
                        `tag` TEXT NOT NULL,
                        `createdAt` INTEGER NOT NULL,
                        FOREIGN KEY(`itemId`) REFERENCES `items`(`id`) ON DELETE CASCADE
                    )
                    """.trimIndent()
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_item_tags_itemId` ON `item_tags` (`itemId`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_item_tags_tag` ON `item_tags` (`tag`)")
                db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_item_tags_itemId_tag` ON `item_tags` (`itemId`, `tag`)")
            }
        }

        /**
         * v14 — item_enrichments table stores a pre-computed "search context"
         * blob per item (the model-generated at save time). A paired FTS5 virtual
         * table indexes the blob so a paraphrase query at retrieval time can
         * match without ever invoking the model again. Triggers keep FTS in sync
         * with the base table on insert/delete/update.
         */
        val MIGRATION_13_14 = object : Migration(13, 14) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `item_enrichments` (
                        `itemId` TEXT NOT NULL PRIMARY KEY,
                        `searchContext` TEXT NOT NULL,
                        `createdAt` INTEGER NOT NULL,
                        FOREIGN KEY(`itemId`) REFERENCES `items`(`id`) ON DELETE CASCADE
                    )
                    """.trimIndent()
                )

                try {
                    db.execSQL(
                        """
                        CREATE VIRTUAL TABLE IF NOT EXISTS item_enrichments_fts USING fts5(
                            searchContext,
                            content='item_enrichments',
                            content_rowid='rowid',
                            tokenize='porter unicode61'
                        )
                        """.trimIndent()
                    )
                    db.execSQL(
                        """
                        CREATE TRIGGER IF NOT EXISTS item_enrichments_fts_ai AFTER INSERT ON item_enrichments BEGIN
                            INSERT INTO item_enrichments_fts(rowid, searchContext) VALUES (new.rowid, new.searchContext);
                        END
                        """.trimIndent()
                    )
                    db.execSQL(
                        """
                        CREATE TRIGGER IF NOT EXISTS item_enrichments_fts_ad AFTER DELETE ON item_enrichments BEGIN
                            INSERT INTO item_enrichments_fts(item_enrichments_fts, rowid, searchContext) VALUES ('delete', old.rowid, old.searchContext);
                        END
                        """.trimIndent()
                    )
                    db.execSQL(
                        """
                        CREATE TRIGGER IF NOT EXISTS item_enrichments_fts_au AFTER UPDATE OF searchContext ON item_enrichments BEGIN
                            INSERT INTO item_enrichments_fts(item_enrichments_fts, rowid, searchContext) VALUES ('delete', old.rowid, old.searchContext);
                            INSERT INTO item_enrichments_fts(rowid, searchContext) VALUES (new.rowid, new.searchContext);
                        END
                        """.trimIndent()
                    )
                } catch (_: Exception) {
                    // FTS5 unavailable on this SQLite build — enrichment lane
                    // will degrade to LIKE fallback in the repository layer.
                }
            }
        }

        /**
         * v15 — eval_queries: synthetic the model-generated queries for measuring
         * retrieval quality. Each row pairs a query with its expected source
         * item. Cascade delete on the parent item keeps the test set coherent.
         */
        val MIGRATION_14_15 = object : Migration(14, 15) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `eval_queries` (
                        `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        `itemId` TEXT NOT NULL,
                        `query` TEXT NOT NULL,
                        `generatorVersion` INTEGER NOT NULL,
                        `createdAt` INTEGER NOT NULL,
                        FOREIGN KEY(`itemId`) REFERENCES `items`(`id`) ON DELETE CASCADE
                    )
                    """.trimIndent()
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_eval_queries_itemId` ON `eval_queries` (`itemId`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_eval_queries_generatorVersion` ON `eval_queries` (`generatorVersion`)")
            }
        }

        /**
         * v16 — persist the final ranked item IDs behind every search so the
         * Search Detail screen can render the ranked list the synthesis saw.
         * Nullable so rows created on ≤v15 keep displaying (they fall back
         * to answer + recording sources only).
         */
        val MIGRATION_15_16 = object : Migration(15, 16) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE search_queries ADD COLUMN rankedItemIdsJson TEXT")
            }
        }

        /**
         * v17 — live progress columns. `stage` and `stageDetail` drive the
         * Detail-screen checklist ("Retrieve ✓", "Rerank — batch 2 of 4").
         * `streamingAnswer` holds the partial synthesis output as the model
         * streams tokens. All nullable so legacy rows just render the final
         * answer without the narration.
         */
        val MIGRATION_16_17 = object : Migration(16, 17) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE search_queries ADD COLUMN stage TEXT")
                db.execSQL("ALTER TABLE search_queries ADD COLUMN stageDetail TEXT")
                db.execSQL("ALTER TABLE search_queries ADD COLUMN streamingAnswer TEXT")
            }
        }

        /**
         * v18 — user-driven cancellation. UI writes cancelRequested=1 on a
         * Stop tap; the processing use case polls this at every stage
         * boundary and bails out when it flips.
         */
        val MIGRATION_17_18 = object : Migration(17, 18) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE search_queries ADD COLUMN cancelRequested INTEGER NOT NULL DEFAULT 0")
            }
        }

        /**
         * v19 — same cancel pattern for voice recordings. cancelRequested on
         * voice_queue is polled by QueueProcessingUseCase at every stage
         * boundary; cancelled on recordings is the permanent flag that makes
         * the row render as "Cancelled · Retry" in History.
         */
        val MIGRATION_18_19 = object : Migration(18, 19) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE voice_queue ADD COLUMN cancelRequested INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE recordings ADD COLUMN cancelled INTEGER NOT NULL DEFAULT 0")
            }
        }

        /**
         * v20 — track how many leading entries in `rankedItemIdsJson` are
         * LLM-picked (rerank survivors) vs the RRF fallback tail. Drives
         * the SearchDetailScreen "+ N more related" disclosure on the
         * ranked-results section. Nullable so legacy rows render flat.
         */
        val MIGRATION_19_20 = object : Migration(19, 20) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE search_queries ADD COLUMN llmPickedCount INTEGER")
            }
        }

        /**
         * v21 — drop the full-text tables and their triggers. Search moved to its own index (SearchIndex, FTS5 on
         * the bundled SQLite), so these were written on every item / recording / enrichment change and never read.
         * Fresh installs also had FTS4 external-content tables with AFTER DELETE triggers, which cannot remove the
         * old tokens. Only derived data goes; no note, task or recording is touched.
         */
        val MIGRATION_20_21 = object : Migration(20, 21) {
            override fun migrate(db: SupportSQLiteDatabase) {
                for (table in listOf("items_fts", "recordings_fts", "item_enrichments_fts")) {
                    for (suffix in listOf("ai", "ad", "au")) db.execSQL("DROP TRIGGER IF EXISTS ${table}_$suffix")
                    db.execSQL("DROP TABLE IF EXISTS $table")
                }
            }
        }
    }
}
