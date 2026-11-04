package com.bol.app.data.backup

import kotlinx.serialization.json.Json
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.security.MessageDigest
import java.time.ZoneId
import java.util.zip.ZipEntry
import java.util.zip.ZipException
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * Reads and writes Heed `.heedbackup` files (a ZIP with `manifest.json`,
 * `data.json`, and `prefs.json`).
 *
 * Invariants:
 *  - Manifest is always the first entry written so streaming readers can
 *    validate version before parsing the rest.
 *  - `dataChecksum` in the manifest is the SHA-256 of the bytes of
 *    `data.json` exactly as written; readers recompute and compare.
 *  - Encoder is fully self-contained: any IOException from the caller's
 *    OutputStream propagates and we don't leave half-written state.
 *  - Decoder buffers entries up-front (small budget — v1 backups without
 *    audio are a few MB at most). Streaming decode comes when we add
 *    audio in v2.
 *
 * NOT thread-safe: use one instance per encode/decode call.
 */
object BackupCodec {

    private const val MANIFEST_FILE = "manifest.json"
    private const val DATA_FILE = "data.json"
    private const val PREFS_FILE = "prefs.json"

    /**
     * Stable JSON config for backup files. `prettyPrint` keeps the file
     * inspectable by users; `ignoreUnknownKeys` lets older Heed builds
     * read newer files (any unknown top-level field is simply skipped);
     * `encodeDefaults` keeps defaulted fields visible in the file so a
     * future reader can't accidentally fall back to a different default.
     */
    private val json = Json {
        prettyPrint = true
        ignoreUnknownKeys = true
        encodeDefaults = true
        explicitNulls = true
    }

    /**
     * Encode [bundle] into [out] as a ZIP. Returns the manifest that was
     * written (counts populated, checksum computed).
     *
     * The OutputStream is NOT closed — the caller owns it, since it
     * typically came from `ContentResolver.openOutputStream()` and has
     * its own `.use { … }` lifecycle.
     */
    fun encode(
        out: OutputStream,
        bundle: BackupBundle,
        appVersionName: String,
        appVersionCode: Int,
        exportedAt: Long = System.currentTimeMillis(),
        exportedFromTimezone: String = ZoneId.systemDefault().id,
    ): BackupManifest {
        val dataBytes = json.encodeToString(BackupData.serializer(), bundle.data)
            .toByteArray(Charsets.UTF_8)
        val prefsBytes = json.encodeToString(BackupPrefs.serializer(), bundle.prefs)
            .toByteArray(Charsets.UTF_8)
        val checksum = sha256Hex(dataBytes)

        val manifest = BackupManifest(
            schemaVersion = BackupSchemaVersion.CURRENT,
            appVersionName = appVersionName,
            appVersionCode = appVersionCode,
            exportedAt = exportedAt,
            exportedFromTimezone = exportedFromTimezone,
            counts = BackupCounts(
                categories = bundle.data.categories.size,
                items = bundle.data.items.size,
                recordings = bundle.data.recordings.size,
                itemTags = bundle.data.itemTags.size,
                recordingTags = bundle.data.recordingTags.size,
                itemEnrichments = bundle.data.itemEnrichments.size,
                searchQueries = bundle.data.searchQueries.size,
                userContext = bundle.data.userContext.size,
            ),
            dataChecksum = "sha256:$checksum",
        )
        val manifestBytes = json.encodeToString(BackupManifest.serializer(), manifest)
            .toByteArray(Charsets.UTF_8)

        // Wrap the caller's stream so closing the ZipOutputStream
        // releases its native Deflater but does NOT propagate close()
        // to the caller's OutputStream — which they own and will close
        // via their own `.use { }` block.
        ZipOutputStream(CloseShield(out)).use { zip ->
            writeEntry(zip, MANIFEST_FILE, manifestBytes, exportedAt)
            writeEntry(zip, DATA_FILE, dataBytes, exportedAt)
            writeEntry(zip, PREFS_FILE, prefsBytes, exportedAt)
        }

        return manifest
    }

    /**
     * Decode a backup ZIP from [input]. Validates schema version and the
     * `data.json` SHA-256 checksum before returning. Throws:
     *  - [BackupCorruptException] on malformed ZIP, missing entries,
     *    JSON parse failure, or checksum mismatch.
     *  - [BackupVersionException] when the file's schema version is
     *    outside our supported range.
     */
    fun decode(input: InputStream): DecodedBackup {
        val entries = try {
            readAllEntries(input)
        } catch (e: ZipException) {
            throw BackupCorruptException("Backup file is not a valid ZIP", e)
        } catch (e: IOException) {
            throw BackupCorruptException("Couldn't read backup file: ${e.message}", e)
        }

        val manifestBytes = entries[MANIFEST_FILE]
            ?: throw BackupCorruptException("manifest.json missing — not a Heed backup")
        val manifest = try {
            json.decodeFromString(BackupManifest.serializer(), manifestBytes.toString(Charsets.UTF_8))
        } catch (e: Exception) {
            throw BackupCorruptException("manifest.json is malformed: ${e.message}", e)
        }

        // Refuse files we definitely can't read. Tolerant otherwise:
        // unknown JSON keys inside data/prefs are skipped at decode.
        if (manifest.schemaVersion > BackupSchemaVersion.CURRENT) {
            throw BackupVersionException(
                fileVersion = manifest.schemaVersion,
                supportedVersion = BackupSchemaVersion.CURRENT,
                tooOld = false,
            )
        }
        if (manifest.schemaVersion < BackupSchemaVersion.MIN_SUPPORTED) {
            throw BackupVersionException(
                fileVersion = manifest.schemaVersion,
                supportedVersion = BackupSchemaVersion.MIN_SUPPORTED,
                tooOld = true,
            )
        }

        // v1 plaintext only; encryption hooks land in v2.
        if (manifest.encrypted) {
            throw BackupCorruptException(
                "This backup is encrypted — Heed v1 doesn't support encrypted backups yet",
            )
        }

        val dataBytes = entries[DATA_FILE]
            ?: throw BackupCorruptException("data.json missing")

        val expected = manifest.dataChecksum.removePrefix("sha256:")
        val actual = sha256Hex(dataBytes)
        if (!actual.equals(expected, ignoreCase = true)) {
            throw BackupCorruptException(
                "data.json checksum mismatch (file may be corrupted)",
            )
        }

        val data = try {
            json.decodeFromString(BackupData.serializer(), dataBytes.toString(Charsets.UTF_8))
        } catch (e: Exception) {
            throw BackupCorruptException("data.json is malformed: ${e.message}", e)
        }

        // Prefs are non-fatal — if the file was hand-edited and prefs.json
        // got mangled, we still want the user's data back. Fall through
        // with empty prefs and let restore use current local defaults.
        val prefsBytes = entries[PREFS_FILE]
        val prefs = if (prefsBytes != null) {
            try {
                json.decodeFromString(BackupPrefs.serializer(), prefsBytes.toString(Charsets.UTF_8))
            } catch (_: Exception) {
                BackupPrefs()
            }
        } else {
            BackupPrefs()
        }

        return DecodedBackup(manifest = manifest, bundle = BackupBundle(data, prefs))
    }

    /**
     * Read just the manifest from a backup file. Used by the preview
     * step in the Restore UI so we can show counts + version without
     * parsing the whole archive.
     */
    fun decodeManifestOnly(input: InputStream): BackupManifest {
        val entries = try {
            readAllEntries(input)
        } catch (e: ZipException) {
            throw BackupCorruptException("Backup file is not a valid ZIP", e)
        } catch (e: IOException) {
            throw BackupCorruptException("Couldn't read backup file: ${e.message}", e)
        }
        val manifestBytes = entries[MANIFEST_FILE]
            ?: throw BackupCorruptException("manifest.json missing — not a Heed backup")
        return try {
            json.decodeFromString(BackupManifest.serializer(), manifestBytes.toString(Charsets.UTF_8))
        } catch (e: Exception) {
            throw BackupCorruptException("manifest.json is malformed: ${e.message}", e)
        }
    }

    private fun writeEntry(zip: ZipOutputStream, name: String, bytes: ByteArray, time: Long) {
        zip.putNextEntry(ZipEntry(name).apply { this.time = time })
        zip.write(bytes)
        zip.closeEntry()
    }

    private fun readAllEntries(input: InputStream): Map<String, ByteArray> {
        val result = mutableMapOf<String, ByteArray>()
        ZipInputStream(input).use { zip ->
            var entry: ZipEntry? = zip.nextEntry
            while (entry != null) {
                if (!entry.isDirectory) {
                    result[entry.name] = zip.readBytes()
                }
                zip.closeEntry()
                entry = zip.nextEntry
            }
        }
        return result
    }

    private fun sha256Hex(bytes: ByteArray): String {
        val md = MessageDigest.getInstance("SHA-256")
        val digest = md.digest(bytes)
        // `Byte` is signed in Kotlin/JVM (-128..127); `String.format("%x", b)`
        // widens via Byte.intValue() which sign-extends, so byte 0xff would
        // print "ffffffff" instead of "ff". Mask to unsigned int first.
        return digest.joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }
    }
}

/**
 * Filters [target] so that callers inside the chain can close their
 * stream without it cascading down to [target] itself. The caller owns
 * [target]'s lifecycle (it typically came from
 * `ContentResolver.openOutputStream` or `openInputStream` and will be
 * closed by the caller's own `.use { }` block).
 */
private class CloseShield(private val target: OutputStream) : OutputStream() {
    override fun write(b: Int) = target.write(b)
    override fun write(b: ByteArray) = target.write(b)
    override fun write(b: ByteArray, off: Int, len: Int) = target.write(b, off, len)
    override fun flush() = target.flush()
    override fun close() {
        // Flush any buffered data, but DO NOT close `target`.
        target.flush()
    }
}

data class DecodedBackup(
    val manifest: BackupManifest,
    val bundle: BackupBundle,
)

/** Backup file is structurally invalid (not a ZIP, missing required entries, malformed JSON, checksum mismatch). */
class BackupCorruptException(message: String, cause: Throwable? = null) : Exception(message, cause)

/**
 * Backup file's schema version is outside our supported range. UI uses
 * [tooOld] to choose between "please update Heed" and "this backup is
 * older than we still support".
 */
class BackupVersionException(
    val fileVersion: Int,
    val supportedVersion: Int,
    val tooOld: Boolean,
) : Exception(
    if (tooOld) "Backup schema v$fileVersion is older than the minimum supported v$supportedVersion"
    else "Backup schema v$fileVersion is newer than this Heed build supports (v$supportedVersion)"
)
