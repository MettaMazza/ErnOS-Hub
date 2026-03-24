package com.ernos.mobile.memory.db

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

// ── Tier 1 DAO ────────────────────────────────────────────────────────────────

@Dao
interface MessageDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(message: MessageEntity): Long

    @Query("SELECT * FROM messages ORDER BY timestamp_ms ASC")
    suspend fun allMessages(): List<MessageEntity>

    @Query("SELECT * FROM messages ORDER BY timestamp_ms DESC LIMIT :limit")
    suspend fun recentMessages(limit: Int): List<MessageEntity>

    @Query("SELECT * FROM messages ORDER BY timestamp_ms ASC")
    fun observeMessages(): Flow<List<MessageEntity>>

    /** Rolling window prune: delete the oldest rows beyond [keepCount]. */
    @Query("""
        DELETE FROM messages
        WHERE id NOT IN (
            SELECT id FROM messages ORDER BY timestamp_ms DESC LIMIT :keepCount
        )
    """)
    suspend fun pruneOldest(keepCount: Int)

    /** Total approximate token count across the entire working-memory window. */
    @Query("SELECT COALESCE(SUM(token_count), 0) FROM messages")
    suspend fun totalTokenCount(): Int

    @Query("SELECT * FROM messages WHERE embedded = 0 ORDER BY timestamp_ms ASC")
    suspend fun unembeddedMessages(): List<MessageEntity>

    @Query("UPDATE messages SET embedded = 1 WHERE id IN (:ids)")
    suspend fun markEmbedded(ids: List<Long>)

    @Query("DELETE FROM messages")
    suspend fun clearAll()
}

// ── Tier 2 DAO ────────────────────────────────────────────────────────────────

@Dao
interface ChunkDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(chunk: ChunkEntity): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(chunks: List<ChunkEntity>)

    @Query("SELECT * FROM chunks ORDER BY timestamp_ms DESC")
    suspend fun allChunks(): List<ChunkEntity>

    @Query("SELECT COUNT(*) FROM chunks")
    suspend fun count(): Int

    @Query("DELETE FROM chunks")
    suspend fun clearAll()
}

// ── Tier 3 Node DAO ───────────────────────────────────────────────────────────

@Dao
interface GraphNodeDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(node: GraphNode): Long

    @Update
    suspend fun update(node: GraphNode)

    @Delete
    suspend fun delete(node: GraphNode)

    @Query("SELECT * FROM graph_nodes WHERE id = :id")
    suspend fun byId(id: Long): GraphNode?

    @Query("SELECT * FROM graph_nodes WHERE label = :label LIMIT 1")
    suspend fun byLabel(label: String): GraphNode?

    @Query("SELECT * FROM graph_nodes WHERE label LIKE '%' || :query || '%' OR description LIKE '%' || :query || '%'")
    suspend fun search(query: String): List<GraphNode>

    @Query("SELECT * FROM graph_nodes ORDER BY updated_at_ms DESC")
    suspend fun allNodes(): List<GraphNode>

    @Query("DELETE FROM graph_nodes WHERE id = :id")
    suspend fun deleteById(id: Long)
}

// ── Tier 3 Edge DAO ───────────────────────────────────────────────────────────

@Dao
interface GraphEdgeDao {

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(edge: GraphEdge): Long

    @Update
    suspend fun update(edge: GraphEdge)

    @Delete
    suspend fun delete(edge: GraphEdge)

    @Query("SELECT * FROM graph_edges WHERE source_id = :nodeId OR target_id = :nodeId")
    suspend fun edgesForNode(nodeId: Long): List<GraphEdge>

    @Query("SELECT * FROM graph_edges WHERE source_id = :sourceId AND target_id = :targetId AND relation = :relation LIMIT 1")
    suspend fun findEdge(sourceId: Long, targetId: Long, relation: String): GraphEdge?

    /** Strengthen an existing edge by adding [delta] to its weight (capped at 1.0). */
    @Query("UPDATE graph_edges SET weight = MIN(1.0, weight + :delta), last_reinforced_ms = :nowMs WHERE id = :edgeId")
    suspend fun strengthen(edgeId: Long, delta: Double, nowMs: Long = System.currentTimeMillis())

    /** Decay all edges by multiplying their weight by [factor]. */
    @Query("UPDATE graph_edges SET weight = weight * :factor")
    suspend fun decayAll(factor: Double)

    /** Prune all edges whose weight has dropped below [threshold]. */
    @Query("DELETE FROM graph_edges WHERE weight < :threshold")
    suspend fun pruneWeak(threshold: Double)

    @Query("SELECT * FROM graph_edges ORDER BY weight DESC")
    suspend fun allEdges(): List<GraphEdge>

    @Query("DELETE FROM graph_edges WHERE id = :id")
    suspend fun deleteById(id: Long)
}
