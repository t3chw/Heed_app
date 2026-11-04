package com.bol.app.ai.retrieval

import androidx.sqlite.SQLiteConnection
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * The search index: its own SQLite file (`search_index.db`, not Room) opened with the bundled SQLite, so FTS5 and
 * `bm25()` work on every phone. It is derived data: [SearchIndexer] fills it from Room and can rebuild it at any time.
 *
 *  - `docs`: one row per task (`t:<item id>`, text `"<text> (<category id>)"`) and per voice note (`n:<recording id>`).
 *    `id` is an explicit INTEGER PRIMARY KEY so the FTS rowids never move.
 *  - `docs_fts`: FTS5 over `docs.text` (`porter unicode61`, as measured), kept in step by triggers.
 *  - `vecs`: one EmbeddingGemma vector per doc and model version (little-endian float32).
 *
 * One connection, every call synchronised; call from a background thread.
 */
class SearchIndex(private val file: File) {
    class Doc(val key: String, val kind: String, val refId: String, val day: Long, val text: String)

    private var conn: SQLiteConnection? = null
    /** Days of every doc and the vectors of one model version, read once and dropped on any write. */
    private var cache: Cache? = null

    private class Cache(val version: String, val lib: Router.Lib, val keys: Array<String>, val vecs: FloatArray, val dim: Int)

    @Synchronized
    private fun db(): SQLiteConnection = conn ?: try {
        open()
    } catch (e: Exception) {
        // Derived data: a damaged file is deleted and rebuilt from Room by the next sync.
        deleteFiles()
        open()
    }.also { conn = it }

    private fun open(): SQLiteConnection {
        val c = BundledSQLiteDriver().open(file.absolutePath)
        try {
            if (c.longOf("PRAGMA user_version") != SCHEMA_VERSION) {
                for (t in listOf("docs_fts", "vecs", "docs")) c.exec("DROP TABLE IF EXISTS $t")
                c.exec("CREATE TABLE docs(id INTEGER PRIMARY KEY, key TEXT NOT NULL UNIQUE, kind TEXT NOT NULL, ref_id TEXT NOT NULL, day INTEGER NOT NULL, text TEXT NOT NULL)")
                c.exec("CREATE VIRTUAL TABLE docs_fts USING fts5(text, content='docs', content_rowid='id', tokenize='porter unicode61')")
                c.exec("CREATE TRIGGER docs_ai AFTER INSERT ON docs BEGIN INSERT INTO docs_fts(rowid, text) VALUES (new.id, new.text); END")
                c.exec("CREATE TRIGGER docs_ad AFTER DELETE ON docs BEGIN INSERT INTO docs_fts(docs_fts, rowid, text) VALUES ('delete', old.id, old.text); END")
                c.exec("CREATE TRIGGER docs_au AFTER UPDATE OF text ON docs BEGIN " +
                    "INSERT INTO docs_fts(docs_fts, rowid, text) VALUES ('delete', old.id, old.text); INSERT INTO docs_fts(rowid, text) VALUES (new.id, new.text); END")
                c.exec("CREATE TABLE vecs(key TEXT PRIMARY KEY, model_version TEXT NOT NULL, dim INTEGER NOT NULL, vec BLOB NOT NULL)")
                c.exec("PRAGMA user_version = $SCHEMA_VERSION")
            }
        } catch (e: Exception) {
            c.close()
            throw e
        }
        return c
    }

    private fun deleteFiles() {
        listOf("", "-journal", "-wal", "-shm").forEach { File(file.path + it).delete() }
    }

    /** key -> (day, text) of every doc. */
    @Synchronized
    fun docs(): Map<String, Pair<Long, String>> {
        val out = HashMap<String, Pair<Long, String>>()
        db().prepare("SELECT key, day, text FROM docs").use { st -> while (st.step()) out[st.getText(0)] = st.getLong(1) to st.getText(2) }
        return out
    }

    /** Insert or replace [upserts] and remove [deletes], in one transaction. A changed text drops that doc's vector. */
    @Synchronized
    fun apply(upserts: List<Doc>, deletes: Collection<String>) {
        if (upserts.isEmpty() && deletes.isEmpty()) return
        val c = db()
        transaction(c) {
            c.prepare("SELECT text FROM docs WHERE key = ?").use { old ->
                c.prepare("DELETE FROM vecs WHERE key = ?").use { dropVec ->
                    c.prepare("INSERT INTO docs(key, kind, ref_id, day, text) VALUES (?, ?, ?, ?, ?) ON CONFLICT(key) DO UPDATE SET " +
                        "kind = excluded.kind, ref_id = excluded.ref_id, day = excluded.day, text = excluded.text").use { put ->
                        for (d in upserts) {
                            old.reset(); old.bindText(1, d.key)
                            if (old.step() && old.getText(0) != d.text) { dropVec.reset(); dropVec.bindText(1, d.key); dropVec.step() }
                            put.reset()
                            put.bindText(1, d.key); put.bindText(2, d.kind); put.bindText(3, d.refId); put.bindLong(4, d.day); put.bindText(5, d.text)
                            put.step()
                        }
                    }
                    c.prepare("DELETE FROM docs WHERE key = ?").use { del ->
                        for (k in deletes) {
                            del.reset(); del.bindText(1, k); del.step()
                            dropVec.reset(); dropVec.bindText(1, k); dropVec.step()
                        }
                    }
                }
            }
        }
        cache = null
    }

    /** BM25 top [limit] keys for an FTS5 MATCH expression (see [Router.ftsMatch]), best first. */
    @Synchronized
    fun bm25(match: String, limit: Int = Router.TOP_N): List<String> {
        val out = ArrayList<String>()
        db().prepare("SELECT d.key FROM docs_fts JOIN docs d ON d.id = docs_fts.rowid WHERE docs_fts MATCH ? ORDER BY bm25(docs_fts) LIMIT ?").use { st ->
            st.bindText(1, match); st.bindLong(2, limit.toLong())
            while (st.step()) out.add(st.getText(0))
        }
        return out
    }

    /**
     * Docs that have no vector for [version]: shortest first (less padding in a batch), or newest first (by recorded
     * day, then insertion; a first bulk sync inserts newest-first, so insertion order alone would pick old notes).
     */
    @Synchronized
    fun missingVectors(version: String, limit: Int, newestFirst: Boolean = false): List<Doc> {
        val out = ArrayList<Doc>()
        val order = if (newestFirst) "d.day DESC, d.id DESC" else "length(d.text), d.id"
        db().prepare("SELECT d.key, d.kind, d.ref_id, d.day, d.text FROM docs d LEFT JOIN vecs v ON v.key = d.key AND v.model_version = ? " +
            "WHERE v.key IS NULL ORDER BY $order LIMIT ?").use { st ->
            st.bindText(1, version); st.bindLong(2, limit.toLong())
            while (st.step()) out.add(Doc(st.getText(0), st.getText(1), st.getText(2), st.getLong(3), st.getText(4)))
        }
        return out
    }

    @Synchronized
    fun countMissingVectors(version: String): Int = db().prepare(
        "SELECT count(*) FROM docs d LEFT JOIN vecs v ON v.key = d.key AND v.model_version = ? WHERE v.key IS NULL",
    ).use { st -> st.bindText(1, version); st.step(); st.getLong(0).toInt() }

    @Synchronized
    fun countDocs(): Int = db().longOf("SELECT count(*) FROM docs").toInt()

    @Synchronized
    fun countVectors(version: String): Int =
        db().prepare("SELECT count(*) FROM vecs WHERE model_version = ?").use { st -> st.bindText(1, version); st.step(); st.getLong(0).toInt() }

    /**
     * Store vectors for docs, but only where the doc still has the text that was embedded: a doc edited while its
     * batch was in the model keeps no stale vector and is picked up again.
     */
    @Synchronized
    fun putVectors(version: String, rows: List<Pair<Doc, FloatArray>>): Int {
        if (rows.isEmpty()) return 0
        val c = db()
        var n = 0
        transaction(c) {
            c.prepare("INSERT OR REPLACE INTO vecs(key, model_version, dim, vec) SELECT ?, ?, ?, ? WHERE EXISTS (SELECT 1 FROM docs WHERE key = ? AND text = ?)").use { st ->
                for ((d, v) in rows) {
                    st.reset()
                    st.bindText(1, d.key); st.bindText(2, version); st.bindLong(3, v.size.toLong()); st.bindBlob(4, pack(v))
                    st.bindText(5, d.key); st.bindText(6, d.text)
                    st.step()
                    n += c.longOf("SELECT changes()").toInt()
                }
            }
        }
        cache = null
        return n
    }

    /** The recorded day of every doc, for the date rule. */
    @Synchronized
    fun lib(version: String): Router.Lib = load(version).lib

    /** Embedding top [k] keys for a unit query vector, best first. */
    @Synchronized
    fun dense(q: FloatArray, version: String, k: Int = Router.TOP_N): List<String> {
        val c = load(version)
        if (c.keys.isEmpty()) return emptyList()
        return Router.topK(c.vecs, c.dim, c.keys.size, q, k).map { c.keys[it] }
    }

    private fun load(version: String): Cache {
        cache?.takeIf { it.version == version }?.let { return it }
        val day = HashMap<String, Long>()
        db().prepare("SELECT key, day FROM docs").use { st -> while (st.step()) day[st.getText(0)] = st.getLong(1) }
        val keys = ArrayList<String>()
        var dim = 0
        var vecs = FloatArray(0)
        db().prepare("SELECT v.key, v.dim, v.vec FROM vecs v JOIN docs d ON d.key = v.key WHERE v.model_version = ?").use { st ->
            st.bindText(1, version)
            var n = 0
            while (st.step()) {
                dim = st.getLong(1).toInt()
                if (vecs.size < (n + 1) * dim) vecs = vecs.copyOf(maxOf((n + 1) * dim, vecs.size * 2))
                ByteBuffer.wrap(st.getBlob(2)).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer().get(vecs, n * dim, dim)
                keys.add(st.getText(0))
                n++
            }
        }
        return Cache(version, Router.Lib(day), keys.toTypedArray(), vecs, dim).also { cache = it }
    }

    /** Empty every table (the next sync refills docs from Room; vectors are re-embedded by the backlog). */
    @Synchronized
    fun clear() {
        val c = db()
        transaction(c) {
            c.exec("DELETE FROM docs")
            c.exec("DELETE FROM vecs")
            c.exec("INSERT INTO docs_fts(docs_fts) VALUES ('delete-all')")
        }
        cache = null
    }

    private inline fun transaction(c: SQLiteConnection, block: () -> Unit) {
        c.exec("BEGIN IMMEDIATE")
        try {
            block()
            c.exec("COMMIT")
        } catch (e: Throwable) {
            runCatching { c.exec("ROLLBACK") }
            throw e
        }
    }

    private fun SQLiteConnection.exec(sql: String) {
        prepare(sql).use { it.step() }
    }

    private fun SQLiteConnection.longOf(sql: String): Long = prepare(sql).use { st -> st.step(); st.getLong(0) }

    private fun pack(v: FloatArray): ByteArray {
        val bb = ByteBuffer.allocate(v.size * 4).order(ByteOrder.LITTLE_ENDIAN)
        bb.asFloatBuffer().put(v)
        return bb.array()
    }

    companion object {
        const val FILE_NAME = "search_index.db"
        const val KIND_TASK = "task"
        const val KIND_NOTE = "note"
        private const val SCHEMA_VERSION = 1L

        /** What [SearchIndex.apply] needs to make the index hold [want]: docs that are new or changed, and keys to remove. */
        fun diff(want: List<Doc>, have: Map<String, Pair<Long, String>>): Pair<List<Doc>, Set<String>> {
            val upserts = want.filter { have[it.key] != (it.day to it.text) }
            val wantKeys = want.mapTo(HashSet()) { it.key }
            return upserts to (have.keys - wantKeys)
        }
    }
}
