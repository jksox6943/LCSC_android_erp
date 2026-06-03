package com.example.lcsc_android_erp.core.database

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.example.lcsc_android_erp.domain.model.StorageLocationSortMode

object DatabaseMigrations {
    val MIGRATION_1_2 = object : Migration(1, 2) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE storage_location ADD COLUMN displayName TEXT")
            db.execSQL("ALTER TABLE storage_location ADD COLUMN colorHex TEXT")
        }
    }

    val MIGRATION_2_3 = object : Migration(2, 3) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE component_master ADD COLUMN image_local_path TEXT")
        }
    }

    val MIGRATION_3_4 = object : Migration(3, 4) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL(
                "ALTER TABLE storage_location ADD COLUMN sortMode TEXT NOT NULL " +
                    "DEFAULT '${StorageLocationSortMode.NONE}'"
            )
        }
    }

    val MIGRATION_4_5 = object : Migration(4, 5) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS storage_location_new (
                    id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                    code TEXT NOT NULL,
                    displayName TEXT,
                    colorHex TEXT,
                    sortMode TEXT NOT NULL DEFAULT '',
                    inbound_category TEXT,
                    inbound_package_name TEXT,
                    inbound_profile_updated_at INTEGER NOT NULL DEFAULT 0,
                    remark TEXT,
                    createdAt INTEGER NOT NULL
                )
                """.trimIndent()
            )
            db.execSQL(
                """
                INSERT INTO storage_location_new (
                    id,
                    code,
                    displayName,
                    colorHex,
                    sortMode,
                    remark,
                    createdAt
                )
                SELECT
                    id,
                    code,
                    displayName,
                    colorHex,
                    sortMode,
                    remark,
                    createdAt
                FROM storage_location
                """.trimIndent()
            )
            db.execSQL("DROP TABLE storage_location")
            db.execSQL("ALTER TABLE storage_location_new RENAME TO storage_location")
            db.execSQL(
                "CREATE UNIQUE INDEX IF NOT EXISTS index_storage_location_code " +
                    "ON storage_location (code)"
            )
        }
    }

    val ALL = arrayOf(
        MIGRATION_1_2,
        MIGRATION_2_3,
        MIGRATION_3_4,
        MIGRATION_4_5
    )
}
