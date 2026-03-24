package com.ernos.mobile.memory

import android.content.Context
import android.util.Log
import com.ernos.mobile.memory.db.AppDatabase
import com.ernos.mobile.memory.db.GraphEdge
import com.ernos.mobile.memory.db.GraphNode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Tier 3 — Synaptic Knowledge Graph
 *
 * Manages directed, weighted edges between concept nodes stored in Room.
 * Implements the HIVE synaptic homeostasis cycle:
 *   - Reinforce: +[REINFORCE_DELTA] per observed co-occurrence (cap 1.0)
 *   - Decay: multiply all weights by [DECAY_FACTOR] per session (representing forgetting)
 *   - Prune: delete edges whose weight has fallen below [PRUNE_THRESHOLD]
 *
 * Neo4j Bolt connection (optional, for high-end setups):
 *   Set [neo4jUri], [neo4jUser], [neo4jPassword] before calling [useNeo4j].
 *   When configured, reads still prefer the local Room database for latency;
 *   writes are mirrored to Neo4j asynchronously.
 *
 * All public functions run on [Dispatchers.IO].
 */
class Tier3SynapticGraph(context: Context) {

    companion object {
        private const val TAG = "Tier3SynapticGraph"

        /** Homeostasis parameters. */
        const val REINFORCE_DELTA  = 0.10
        const val DECAY_FACTOR     = 0.95
        const val PRUNE_THRESHOLD  = 0.05
    }

    private val db          = AppDatabase.getInstance(context)
    private val nodeDao     = db.graphNodeDao()
    private val edgeDao     = db.graphEdgeDao()

    // ── Neo4j Bolt (optional) ─────────────────────────────────────────────────
    // Bolt client integration omitted from the Room-backed build; the scaffold
    // is here so Milestone 6 settings can toggle it on at runtime.

    private var neo4jEnabled  = false
    private var neo4jUri      = ""
    private var neo4jUser     = ""
    private var neo4jPassword = ""

    /**
     * Configure the optional Neo4j Bolt mirror.
     * Call this before the first write if Neo4j is desired.
     */
    fun configureNeo4j(uri: String, user: String, password: String) {
        neo4jUri      = uri
        neo4jUser     = user
        neo4jPassword = password
        neo4jEnabled  = uri.isNotBlank()
        Log.i(TAG, "Neo4j configured: $uri (enabled=$neo4jEnabled)")
    }

    // ── Node CRUD ─────────────────────────────────────────────────────────────

    /**
     * Create or return an existing node with [label].
     * If a node with the same label exists, it is returned as-is.
     */
    suspend fun upsertNode(label: String, description: String = "", metadataJson: String = "{}"): GraphNode =
        withContext(Dispatchers.IO) {
            nodeDao.byLabel(label) ?: run {
                val id = nodeDao.insert(
                    GraphNode(
                        label         = label,
                        description   = description,
                        metadataJson  = metadataJson,
                    )
                )
                GraphNode(
                    id            = id,
                    label         = label,
                    description   = description,
                    metadataJson  = metadataJson,
                )
            }
        }

    suspend fun updateNode(node: GraphNode) = withContext(Dispatchers.IO) {
        nodeDao.update(node.copy(updatedAtMs = System.currentTimeMillis()))
        mirrorNodeToNeo4j(node)
    }

    suspend fun deleteNode(id: Long) = withContext(Dispatchers.IO) {
        nodeDao.deleteById(id)
        Log.d(TAG, "Deleted node id=$id (cascades to edges)")
    }

    suspend fun findNodeByLabel(label: String): GraphNode? = withContext(Dispatchers.IO) {
        nodeDao.byLabel(label)
    }

    suspend fun searchNodes(query: String): List<GraphNode> = withContext(Dispatchers.IO) {
        nodeDao.search(query)
    }

    suspend fun allNodes(): List<GraphNode> = withContext(Dispatchers.IO) {
        nodeDao.allNodes()
    }

    // ── Edge CRUD ─────────────────────────────────────────────────────────────

    /**
     * Create a directed edge from [sourceId] to [targetId] with [relation].
     * If the edge already exists (unique index on source/target/relation),
     * it is reinforced instead of inserted again.
     */
    suspend fun connectNodes(
        sourceId: Long,
        targetId: Long,
        relation: String = "related_to",
    ): GraphEdge = withContext(Dispatchers.IO) {
        val existing = edgeDao.findEdge(sourceId, targetId, relation)
        if (existing != null) {
            edgeDao.strengthen(existing.id, REINFORCE_DELTA)
            Log.d(TAG, "Reinforced edge ${existing.id} (${existing.weight} → min(1.0, ${existing.weight + REINFORCE_DELTA}))")
            existing.copy(weight = minOf(1.0, existing.weight + REINFORCE_DELTA))
        } else {
            val id = edgeDao.insert(
                GraphEdge(
                    sourceId = sourceId,
                    targetId = targetId,
                    relation = relation,
                )
            )
            Log.d(TAG, "Created edge id=$id $sourceId --[$relation]--> $targetId")
            GraphEdge(id = id, sourceId = sourceId, targetId = targetId, relation = relation)
        }
    }

    suspend fun edgesForNode(nodeId: Long): List<GraphEdge> = withContext(Dispatchers.IO) {
        edgeDao.edgesForNode(nodeId)
    }

    suspend fun allEdges(): List<GraphEdge> = withContext(Dispatchers.IO) {
        edgeDao.allEdges()
    }

    suspend fun deleteEdge(edgeId: Long) = withContext(Dispatchers.IO) {
        edgeDao.deleteById(edgeId)
    }

    // ── Synaptic homeostasis ──────────────────────────────────────────────────

    /**
     * Run one homeostasis cycle: decay all edges, then prune the weakest ones.
     *
     * Call once per session start (or periodically in the background).
     * The [MemoryManager] triggers this during app initialisation.
     */
    suspend fun runHomeostasis() = withContext(Dispatchers.IO) {
        edgeDao.decayAll(DECAY_FACTOR)
        val countBefore = edgeDao.allEdges().size
        edgeDao.pruneWeak(PRUNE_THRESHOLD)
        val countAfter = edgeDao.allEdges().size
        val pruned = countBefore - countAfter
        Log.i(TAG, "Homeostasis: decay×${DECAY_FACTOR}, pruned $pruned edge(s), $countAfter remaining")
    }

    // ── Context building ──────────────────────────────────────────────────────

    /**
     * Build a concise text summary of graph knowledge related to a free-text [query].
     * Searches for nodes matching the query, then expands one hop.
     */
    suspend fun buildContextForQuery(query: String, maxNodes: Int = 5): String = withContext(Dispatchers.IO) {
        val nodes = nodeDao.search(query).take(maxNodes)
        if (nodes.isEmpty()) return@withContext ""

        val sb = StringBuilder()
        for (node in nodes) {
            sb.appendLine("• ${node.label}${if (node.description.isNotEmpty()) ": ${node.description}" else ""}")
            val edges = edgeDao.edgesForNode(node.id).take(3)
            for (edge in edges) {
                val other = nodeDao.byId(if (edge.sourceId == node.id) edge.targetId else edge.sourceId)
                if (other != null) {
                    sb.appendLine("  → ${edge.relation} → ${other.label} (strength: ${"%.2f".format(edge.weight)})")
                }
            }
        }
        sb.toString().trim()
    }

    // ── Neo4j mirror (stub) ───────────────────────────────────────────────────

    private fun mirrorNodeToNeo4j(node: GraphNode) {
        if (!neo4jEnabled) return
        // Milestone 6: wire neo4j-java-driver here when settings enable it.
        Log.d(TAG, "Neo4j mirror (not yet wired) for node ${node.id}")
    }
}
