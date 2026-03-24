package com.ernos.mobile.memory.db

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

// ── Tier 1: Working memory messages ──────────────────────────────────────────

/**
 * One persisted chat message for Tier 1 (working memory).
 *
 * The table acts as a rolling window: old rows are pruned once [MAX_WORKING_MESSAGES]
 * is exceeded, but all messages remain available to Tier 4 (timeline) and may be
 * chunked into Tier 2 (autosave) before pruning.
 */
@Entity(tableName = "messages")
data class MessageEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,

    /** "user" or "assistant". */
    @ColumnInfo(name = "role")
    val role: String,

    /** Full message text. */
    @ColumnInfo(name = "content")
    val content: String,

    /** Unix epoch milliseconds when the message was recorded. */
    @ColumnInfo(name = "timestamp_ms")
    val timestampMs: Long = System.currentTimeMillis(),

    /**
     * Approximate token count (calculated by the caller with a simple
     * word-count heuristic; exact count is a Milestone 6 refinement).
     */
    @ColumnInfo(name = "token_count")
    val tokenCount: Int = 0,

    /**
     * True once this message's content has been chunked and embedded into Tier 2.
     * Prevents double-embedding on the next session start.
     */
    @ColumnInfo(name = "embedded")
    val embedded: Boolean = false,
)

// ── Tier 2: Autosave embeddings ───────────────────────────────────────────────

/**
 * One text chunk with its embedding vector (stored as a comma-separated float string).
 *
 * Storing embeddings as a compact string avoids a custom Room TypeConverter while
 * keeping the schema simple. The actual array is (de)serialised in [Tier2AutoSave].
 */
@Entity(tableName = "chunks")
data class ChunkEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,

    /** The text fragment that was embedded. */
    @ColumnInfo(name = "text")
    val text: String,

    /**
     * Comma-separated float values of the all-MiniLM-L6-v2 output (384 dimensions).
     * Example: "0.123,−0.456,…"
     */
    @ColumnInfo(name = "embedding")
    val embedding: String,

    @ColumnInfo(name = "timestamp_ms")
    val timestampMs: Long = System.currentTimeMillis(),

    /**
     * Optional source reference: message ID or "timeline" to help with provenance.
     */
    @ColumnInfo(name = "source_ref")
    val sourceRef: String = "",
)

// ── Tier 3: Synaptic graph ────────────────────────────────────────────────────

/**
 * A node in the synaptic knowledge graph.
 * Each node represents a concept, entity, or memory anchor.
 */
@Entity(tableName = "graph_nodes")
data class GraphNode(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,

    /** Short label for this concept (e.g., "Python", "Alice", "project-x"). */
    @ColumnInfo(name = "label")
    val label: String,

    /** Optional free-text description. */
    @ColumnInfo(name = "description")
    val description: String = "",

    /** JSON blob for arbitrary extra metadata. */
    @ColumnInfo(name = "metadata_json")
    val metadataJson: String = "{}",

    @ColumnInfo(name = "created_at_ms")
    val createdAtMs: Long = System.currentTimeMillis(),

    @ColumnInfo(name = "updated_at_ms")
    val updatedAtMs: Long = System.currentTimeMillis(),
)

/**
 * A directed, weighted edge between two [GraphNode]s.
 *
 * Weight starts at 1.0 and changes over time via the homeostasis cycle:
 *   - Reinforcement (+0.1 per observed co-occurrence)
 *   - Decay (×0.95 per session without observation)
 *   - Prune when weight < 0.05
 */
@Entity(
    tableName = "graph_edges",
    foreignKeys = [
        ForeignKey(
            entity          = GraphNode::class,
            parentColumns   = ["id"],
            childColumns    = ["source_id"],
            onDelete        = ForeignKey.CASCADE,
        ),
        ForeignKey(
            entity          = GraphNode::class,
            parentColumns   = ["id"],
            childColumns    = ["target_id"],
            onDelete        = ForeignKey.CASCADE,
        ),
    ],
    indices = [
        Index("source_id"),
        Index("target_id"),
        Index(value = ["source_id", "target_id", "relation"], unique = true),
    ],
)
data class GraphEdge(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,

    @ColumnInfo(name = "source_id")
    val sourceId: Long,

    @ColumnInfo(name = "target_id")
    val targetId: Long,

    /** Semantic relation label (e.g., "related_to", "causes", "is_a"). */
    @ColumnInfo(name = "relation")
    val relation: String = "related_to",

    /** Current edge strength in [0.0, 1.0]. */
    @ColumnInfo(name = "weight")
    val weight: Double = 1.0,

    @ColumnInfo(name = "created_at_ms")
    val createdAtMs: Long = System.currentTimeMillis(),

    @ColumnInfo(name = "last_reinforced_ms")
    val lastReinforcedMs: Long = System.currentTimeMillis(),
)
