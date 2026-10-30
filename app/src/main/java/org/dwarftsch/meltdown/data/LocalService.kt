package org.dwarftsch.meltdown.data

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteConstraintException
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.dwarftsch.meltdown.Child
import org.dwarftsch.meltdown.Entry
import org.dwarftsch.meltdown.EntryInput
import org.dwarftsch.meltdown.EntryPage
import org.dwarftsch.meltdown.Grenzen
import org.dwarftsch.meltdown.parseIsoZeit
import java.time.Instant
import java.time.temporal.ChronoUnit

/** Roh-Zeilen für Backup-Export/-Import. */
data class KindZeile(val id: Long, val name: String)
data class EintragZeile(
    val id: Long,
    val childId: Long,
    val createTime: Instant,
    val intensity: Int,
    val trigger: String?,
)

/**
 * Lokaler Modus: Kinder und Einträge in `meltdown.db` (app-privat, keine
 * Synchronisation). Schema und Regeln spiegeln die Server-Migration
 * `007_meltdown_tracker.sql`: Name eindeutig (ohne Gross-/Kleinschreibung),
 * Intensität 1–10, Auslöser optional, Kind mit Einträgen nicht löschbar.
 *
 * Zeitpunkte liegen als ISO 8601 in UTC mit Sekunden (`…T18:05:00Z`) vor –
 * gleich lang und damit lexikalisch chronologisch sortierbar.
 */
class LocalService(context: Context) : MeltdownService {

    private val helper = Helper(context.applicationContext)

    private class Helper(context: Context) : SQLiteOpenHelper(context, DB_NAME, null, DB_VERSION) {

        override fun onConfigure(db: SQLiteDatabase) {
            db.setForeignKeyConstraintsEnabled(true)
        }

        override fun onCreate(db: SQLiteDatabase) {
            db.execSQL(
                """
                CREATE TABLE children(
                  id INTEGER PRIMARY KEY AUTOINCREMENT,
                  name TEXT NOT NULL COLLATE NOCASE UNIQUE
                    CHECK (length(trim(name)) BETWEEN 1 AND ${Grenzen.NAME_MAX})
                )
                """.trimIndent(),
            )
            db.execSQL(
                """
                CREATE TABLE entries(
                  id INTEGER PRIMARY KEY AUTOINCREMENT,
                  child_id INTEGER NOT NULL REFERENCES children(id) ON DELETE RESTRICT ON UPDATE CASCADE,
                  create_time TEXT NOT NULL,
                  intensity INTEGER NOT NULL CHECK (intensity BETWEEN 1 AND 10),
                  trigger_text TEXT
                    CHECK (trigger_text IS NULL OR length(trim(trigger_text)) BETWEEN 1 AND ${Grenzen.TRIGGER_MAX})
                )
                """.trimIndent(),
            )
            db.execSQL("CREATE INDEX idx_entries_time ON entries(create_time, id)")
            db.execSQL("CREATE INDEX idx_entries_child_time ON entries(child_id, create_time)")
        }

        override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
    }

    override fun dispose() = helper.close()

    // ── Kinder ──────────────────────────────────────────────────────────────

    override suspend fun getChildren(): List<Child> = io {
        helper.readableDatabase.rawQuery(
            """
            SELECT c.id, c.name, COUNT(e.id) FROM children c
            LEFT JOIN entries e ON e.child_id = c.id
            GROUP BY c.id, c.name ORDER BY c.name COLLATE NOCASE, c.id
            """.trimIndent(),
            null,
        ).use { c -> buildList { while (c.moveToNext()) add(Child(c.getLong(0), c.getString(1), c.getInt(2))) } }
    }

    private fun kind(db: SQLiteDatabase, id: Long): Child =
        db.rawQuery(
            "SELECT id, name, (SELECT COUNT(*) FROM entries WHERE child_id = c.id) FROM children c WHERE id = ?",
            arrayOf(id.toString()),
        ).use { c ->
            if (!c.moveToFirst()) throw ApiException("Kind nicht gefunden", 404)
            Child(c.getLong(0), c.getString(1), c.getInt(2))
        }

    override suspend fun createChild(name: String): Child = io {
        val sauber = pruefe(Grenzen.pruefeName(name))
        val db = helper.writableDatabase
        val id = try {
            db.insertOrThrow("children", null, ContentValues().apply { put("name", sauber) })
        } catch (_: SQLiteConstraintException) {
            throw ApiException("Diesen Namen gibt es bereits.", 409)
        }
        kind(db, id)
    }

    override suspend fun renameChild(id: Long, name: String): Child = io {
        val sauber = pruefe(Grenzen.pruefeName(name))
        val db = helper.writableDatabase
        kind(db, id)
        try {
            db.update("children", ContentValues().apply { put("name", sauber) }, "id = ?", arrayOf(id.toString()))
        } catch (_: SQLiteConstraintException) {
            throw ApiException("Diesen Namen gibt es bereits.", 409)
        }
        kind(db, id)
    }

    override suspend fun deleteChild(id: Long) = io {
        val db = helper.writableDatabase
        if (kind(db, id).entries > 0) {
            throw ApiException("Kind hat noch Einträge im Verlauf und wird nicht gelöscht.", 409)
        }
        try {
            db.delete("children", "id = ?", arrayOf(id.toString()))
        } catch (_: SQLiteConstraintException) {
            throw ApiException("Kind hat noch Einträge im Verlauf und wird nicht gelöscht.", 409)
        }
        Unit
    }

    // ── Einträge ────────────────────────────────────────────────────────────

    override suspend fun getEntries(childId: Long?, offset: Int, limit: Int): EntryPage = io {
        val args = buildList {
            if (childId != null) add(childId.toString())
            add(limit.toString())
            add(offset.toString())
        }.toTypedArray()
        val liste = helper.readableDatabase.rawQuery(
            SELECT_EINTRAG + (if (childId != null) " WHERE e.child_id = ?" else "") +
                " ORDER BY e.create_time DESC, e.id DESC LIMIT ? OFFSET ?",
            args,
        ).use { c -> buildList { while (c.moveToNext()) add(eintrag(c)) } }
        EntryPage(liste, offset + liste.size, hatMehr = liste.size >= limit)
    }

    private fun eintrag(db: SQLiteDatabase, id: Long): Entry =
        db.rawQuery("$SELECT_EINTRAG WHERE e.id = ?", arrayOf(id.toString())).use { c ->
            if (!c.moveToFirst()) throw ApiException("Eintrag nicht gefunden", 404)
            eintrag(c)
        }

    private fun eintrag(c: Cursor) = Entry(
        id = c.getLong(0),
        childId = c.getLong(1),
        childName = c.getString(2),
        createTime = parseIsoZeit(c.getString(3)),
        intensity = c.getInt(4),
        trigger = if (c.isNull(5)) null else c.getString(5),
    )

    private fun werte(neu: EntryInput, zeit: Instant) = ContentValues().apply {
        put("child_id", neu.childId)
        put("create_time", zuDb(pruefe(Grenzen.pruefeZeit(zeit))))
        put("intensity", pruefe(Grenzen.pruefeIntensitaet(neu.intensity)))
        val ausloeser = pruefe(Grenzen.pruefeAusloeser(neu.trigger))
        if (ausloeser == null) putNull("trigger_text") else put("trigger_text", ausloeser)
    }

    override suspend fun createEntry(neu: EntryInput): Entry = io {
        val db = helper.writableDatabase
        kind(db, neu.childId)
        val id = db.insertOrThrow("entries", null, werte(neu, neu.createTime ?: Instant.now()))
        eintrag(db, id)
    }

    override suspend fun updateEntry(id: Long, neu: EntryInput): Entry = io {
        val db = helper.writableDatabase
        eintrag(db, id)
        kind(db, neu.childId)
        val zeit = neu.createTime ?: throw ApiException("Zeitpunkt fehlt.", 400)
        db.update("entries", werte(neu, zeit), "id = ?", arrayOf(id.toString()))
        eintrag(db, id)
    }

    override suspend fun deleteEntry(id: Long) = io {
        val geloescht = helper.writableDatabase.delete("entries", "id = ?", arrayOf(id.toString()))
        if (geloescht == 0) throw ApiException("Eintrag nicht gefunden", 404)
    }

    // ── Backup ──────────────────────────────────────────────────────────────

    suspend fun exportiere(): Pair<List<KindZeile>, List<EintragZeile>> = io {
        val db = helper.readableDatabase
        val kinder = db.rawQuery("SELECT id, name FROM children ORDER BY id", null).use { c ->
            buildList { while (c.moveToNext()) add(KindZeile(c.getLong(0), c.getString(1))) }
        }
        val eintraege = db.rawQuery(
            "SELECT id, child_id, create_time, intensity, trigger_text FROM entries ORDER BY id",
            null,
        ).use { c ->
            buildList {
                while (c.moveToNext()) {
                    add(
                        EintragZeile(
                            c.getLong(0), c.getLong(1), parseIsoZeit(c.getString(2)), c.getInt(3),
                            if (c.isNull(4)) null else c.getString(4),
                        ),
                    )
                }
            }
        }
        kinder to eintraege
    }

    /** Ersetzt den gesamten Bestand in einer Transaktion (alles oder nichts). */
    suspend fun ersetzeAlles(kinder: List<KindZeile>, eintraege: List<EintragZeile>) = io {
        val db = helper.writableDatabase
        db.beginTransaction()
        try {
            db.delete("entries", null, null)
            db.delete("children", null, null)
            db.delete("sqlite_sequence", "name IN ('entries', 'children')", null)
            for (k in kinder) {
                db.insertOrThrow("children", null, ContentValues().apply {
                    put("id", k.id)
                    put("name", k.name)
                })
            }
            for (e in eintraege) {
                db.insertOrThrow("entries", null, ContentValues().apply {
                    put("id", e.id)
                    put("child_id", e.childId)
                    put("create_time", zuDb(e.createTime))
                    put("intensity", e.intensity)
                    if (e.trigger == null) putNull("trigger_text") else put("trigger_text", e.trigger)
                })
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    private suspend fun <T> io(block: () -> T): T = withContext(Dispatchers.IO) { block() }

    private fun <T> pruefe(ergebnis: Result<T>): T =
        ergebnis.getOrElse { throw ApiException(it.message.orEmpty(), 400) }

    companion object {
        const val DB_NAME = "meltdown.db"
        private const val DB_VERSION = 1

        private const val SELECT_EINTRAG =
            "SELECT e.id, e.child_id, c.name, e.create_time, e.intensity, e.trigger_text " +
                "FROM entries e JOIN children c ON c.id = e.child_id"

        /** UTC mit Sekunden; ISO_INSTANT gibt Sekunden immer aus. */
        fun zuDb(zeit: Instant): String = zeit.truncatedTo(ChronoUnit.SECONDS).toString()
    }
}
