package com.nie.pustaka

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

/**
 * Pengganti langsung dari init_db()/get_db() di Flask (_Library.py).
 * Skema tabel dibuat SAMA PERSIS agar kamu bisa mengimpor database lama
 * (manga_library.db) apa adanya kalau perlu (copy file .db ke
 * getDatabasePath(DB_NAME) sebelum app pertama kali dibuka).
 */
class DatabaseHelper(context: Context) :
    SQLiteOpenHelper(context, DB_NAME, null, DB_VERSION) {

    companion object {
        const val DB_NAME = "manga_library.db"
        const val DB_VERSION = 1
    }

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """CREATE TABLE IF NOT EXISTS collection (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                title TEXT NOT NULL,
                type TEXT,
                genre TEXT,
                status TEXT,
                chapters TEXT,
                episodes TEXT,
                description TEXT,
                image_path TEXT,
                is_favorite INTEGER DEFAULT 0
            )"""
        )
        db.execSQL(
            """CREATE TABLE IF NOT EXISTS categories (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                name TEXT NOT NULL UNIQUE
            )"""
        )
        db.execSQL(
            """CREATE TABLE IF NOT EXISTS collection_categories (
                collection_id INTEGER,
                category_id INTEGER,
                FOREIGN KEY(collection_id) REFERENCES collection(id) ON DELETE CASCADE,
                FOREIGN KEY(category_id) REFERENCES categories(id) ON DELETE CASCADE
            )"""
        )
        db.execSQL(
            """CREATE TABLE IF NOT EXISTS chapter_list (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                collection_id INTEGER,
                chapter_name TEXT NOT NULL,
                file_name TEXT,
                order_index INTEGER DEFAULT 0,
                upload_date DATE DEFAULT CURRENT_TIMESTAMP,
                FOREIGN KEY(collection_id) REFERENCES collection(id) ON DELETE CASCADE
            )"""
        )
        db.execSQL(
            """CREATE TABLE IF NOT EXISTS bookmarks (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                collection_id INTEGER,
                chapter_id INTEGER,
                page_index INTEGER,
                created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
                FOREIGN KEY(collection_id) REFERENCES collection(id) ON DELETE CASCADE,
                FOREIGN KEY(chapter_id) REFERENCES chapter_list(id) ON DELETE CASCADE
            )"""
        )
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        // Tambahkan migrasi ALTER TABLE di sini kalau skema berubah nanti.
    }

    override fun onOpen(db: SQLiteDatabase) {
        super.onOpen(db)
        db.execSQL("PRAGMA foreign_keys = ON")
    }
}
