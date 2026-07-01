package com.dex.browser

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

data class HistoryEntry(
    val id: Long,
    val title: String,
    val url: String,
    val visitTime: Long
)

class HistoryManager private constructor(context: Context) :
    SQLiteOpenHelper(context, DB_NAME, null, DB_VERSION) {

    companion object {
        private const val DB_NAME = "browser_history.db"
        private const val DB_VERSION = 1
        private const val TABLE = "history"

        @Volatile
        private var instance: HistoryManager? = null

        fun getInstance(context: Context): HistoryManager =
            instance ?: synchronized(this) {
                instance ?: HistoryManager(context.applicationContext).also { instance = it }
            }
    }

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("""
            CREATE TABLE $TABLE (
                _id INTEGER PRIMARY KEY AUTOINCREMENT,
                title TEXT,
                url TEXT,
                visit_time INTEGER
            )
        """.trimIndent())
        db.execSQL("CREATE INDEX idx_visit_time ON $TABLE(visit_time DESC)")
    }

    override fun onUpgrade(db: SQLiteDatabase, old: Int, new: Int) {
        db.execSQL("DROP TABLE IF EXISTS $TABLE")
        onCreate(db)
    }

    fun addEntry(title: String, url: String) {
        if (url.isBlank() || url == "about:blank") return
        val db = writableDatabase
        val values = ContentValues().apply {
            put("title", title.ifBlank { url })
            put("url", url)
            put("visit_time", System.currentTimeMillis())
        }
        db.insert(TABLE, null, values)
    }

    fun getAll(limit: Int = 200): List<HistoryEntry> {
        val list = mutableListOf<HistoryEntry>()
        val db = readableDatabase
        val cursor = db.rawQuery(
            "SELECT _id, title, url, visit_time FROM $TABLE ORDER BY visit_time DESC LIMIT ?",
            arrayOf(limit.toString())
        )
        cursor.use {
            while (it.moveToNext()) {
                list.add(
                    HistoryEntry(
                        id = it.getLong(0),
                        title = it.getString(1) ?: "",
                        url = it.getString(2) ?: "",
                        visitTime = it.getLong(3)
                    )
                )
            }
        }
        return list
    }

    fun deleteById(id: Long) {
        writableDatabase.delete(TABLE, "_id=?", arrayOf(id.toString()))
    }

    /** 一键清空所有历史 */
    fun clearAll() {
        writableDatabase.delete(TABLE, null, null)
    }

    fun search(query: String, limit: Int = 50): List<HistoryEntry> {
        val list = mutableListOf<HistoryEntry>()
        val db = readableDatabase
        val cursor = db.rawQuery(
            "SELECT _id, title, url, visit_time FROM $TABLE WHERE title LIKE ? OR url LIKE ? ORDER BY visit_time DESC LIMIT ?",
            arrayOf("%$query%", "%$query%", limit.toString())
        )
        cursor.use {
            while (it.moveToNext()) {
                list.add(
                    HistoryEntry(
                        id = it.getLong(0),
                        title = it.getString(1) ?: "",
                        url = it.getString(2) ?: "",
                        visitTime = it.getLong(3)
                    )
                )
            }
        }
        return list
    }
}
