package com.ernos.mobile.memory.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

/**
 * ErnOS unified Room database.
 *
 * Hosts all three persistence-tier tables:
 *   messages    — Tier 1 working memory
 *   chunks      — Tier 2 autosave embeddings
 *   graph_nodes — Tier 3 synaptic graph nodes
 *   graph_edges — Tier 3 synaptic graph edges
 *
 * Singleton via double-checked locking; safe to access from multiple coroutines.
 */
@Database(
    entities  = [MessageEntity::class, ChunkEntity::class, GraphNode::class, GraphEdge::class],
    version   = 1,
    exportSchema = true,
)
abstract class AppDatabase : RoomDatabase() {

    abstract fun messageDao(): MessageDao
    abstract fun chunkDao(): ChunkDao
    abstract fun graphNodeDao(): GraphNodeDao
    abstract fun graphEdgeDao(): GraphEdgeDao

    companion object {
        private const val DB_NAME = "ernos.db"

        @Volatile
        private var INSTANCE: AppDatabase? = null

        fun getInstance(context: Context): AppDatabase =
            INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    DB_NAME,
                )
                    .fallbackToDestructiveMigration()
                    .build()
                    .also { INSTANCE = it }
            }
    }
}
