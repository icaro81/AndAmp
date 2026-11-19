package com.example.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [
        TrackEntity::class,
        PlaylistEntity::class,
        PlaylistTrackCrossRef::class,
        EqPresetEntity::class,
        SmbServerEntity::class,
        SavedYouTubePlaylistEntity::class
    ],
    version = 2,
    exportSchema = false
)
abstract class SonoraDatabase : RoomDatabase() {
    abstract fun trackDao(): TrackDao
    abstract fun playlistDao(): PlaylistDao
    abstract fun eqPresetDao(): EqPresetDao
    abstract fun smbServerDao(): SmbServerDao
    abstract fun youTubePlaylistDao(): YouTubePlaylistDao

    companion object {
        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `smb_servers` (
                        `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        `alias` TEXT NOT NULL,
                        `host` TEXT NOT NULL,
                        `port` INTEGER NOT NULL,
                        `defaultShare` TEXT NOT NULL,
                        `username` TEXT NOT NULL,
                        `password` TEXT NOT NULL,
                        `isGuest` INTEGER NOT NULL,
                        `createdAt` INTEGER NOT NULL
                    )
                    """.trimIndent()
                )
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `youtube_playlists` (
                        `playlistId` TEXT NOT NULL,
                        `title` TEXT NOT NULL,
                        `channelTitle` TEXT NOT NULL,
                        `videoCountText` TEXT NOT NULL,
                        `thumbnailUrl` TEXT NOT NULL,
                        `savedAt` INTEGER NOT NULL,
                        PRIMARY KEY(`playlistId`)
                    )
                    """.trimIndent()
                )
            }
        }

        fun build(context: Context): SonoraDatabase =
            Room.databaseBuilder(
                context.applicationContext,
                SonoraDatabase::class.java,
                "sonora_hifi.db"
            )
                .addMigrations(MIGRATION_1_2)
                .build()
    }
}
