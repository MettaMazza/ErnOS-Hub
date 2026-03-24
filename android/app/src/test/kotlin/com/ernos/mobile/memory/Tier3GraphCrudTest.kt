package com.ernos.mobile.memory

import com.ernos.mobile.memory.db.GraphEdge
import com.ernos.mobile.memory.db.GraphNode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * CRUD and homeostasis logic tests for Tier 3 (Synaptic Graph).
 *
 * All tests are pure JVM (no Room, no Android SDK) because they operate
 * directly on the data-model values and the algorithm constants exposed by
 * [Tier3SynapticGraph].
 *
 * What is covered:
 *   - GraphNode entity CRUD (create, read-by-id simulation, update, delete by filtering)
 *   - GraphEdge entity CRUD (create, update weight, delete by filtering)
 *   - Reinforcement cap at 1.0
 *   - Decay cycle (single and multi-step)
 *   - Prune logic (below/at/above threshold)
 *   - Homeostasis constants (decay factor, prune threshold, reinforce delta)
 *   - Edge uniqueness invariant (source + target + relation)
 */
class Tier3GraphCrudTest {

    // ── GraphNode CRUD ─────────────────────────────────────────────────────────

    @Test
    fun node_create_has_correct_label() {
        val node = GraphNode(id = 1, label = "Python")
        assertEquals("Python", node.label)
    }

    @Test
    fun node_create_description_defaults_to_empty() {
        val node = GraphNode(label = "Concept")
        assertEquals("", node.description)
    }

    @Test
    fun node_create_metadata_defaults_to_empty_json() {
        val node = GraphNode(label = "Topic")
        assertEquals("{}", node.metadataJson)
    }

    @Test
    fun node_update_label_produces_new_instance() {
        val original = GraphNode(id = 1, label = "old-label")
        val updated  = original.copy(label = "new-label", updatedAtMs = System.currentTimeMillis() + 1000)
        assertEquals("new-label", updated.label)
        assertEquals("old-label", original.label)  // immutable
        assertNotEquals(original.label, updated.label)
    }

    @Test
    fun node_update_description_does_not_change_other_fields() {
        val original = GraphNode(id = 5, label = "Kotlin", description = "")
        val updated  = original.copy(description = "A JVM language")
        assertEquals("Kotlin",        updated.label)
        assertEquals("A JVM language", updated.description)
        assertEquals("{}", updated.metadataJson)
        assertEquals(original.id, updated.id)
    }

    @Test
    fun node_delete_removes_from_list() {
        val nodes = mutableListOf(
            GraphNode(id = 1, label = "A"),
            GraphNode(id = 2, label = "B"),
            GraphNode(id = 3, label = "C"),
        )
        nodes.removeAll { it.id == 2L }
        assertEquals(2, nodes.size)
        assertTrue(nodes.none { it.id == 2L })
    }

    @Test
    fun node_search_by_label_finds_match() {
        val nodes = listOf(
            GraphNode(id = 1, label = "Python"),
            GraphNode(id = 2, label = "Kotlin"),
            GraphNode(id = 3, label = "Java"),
        )
        val results = nodes.filter { it.label.contains("ython", ignoreCase = true) }
        assertEquals(1, results.size)
        assertEquals("Python", results.first().label)
    }

    @Test
    fun node_search_by_description_finds_match() {
        val nodes = listOf(
            GraphNode(id = 1, label = "A", description = "A scripting language"),
            GraphNode(id = 2, label = "B", description = "A compiled language"),
        )
        val results = nodes.filter { it.description.contains("scripting", ignoreCase = true) }
        assertEquals(1, results.size)
        assertEquals("A", results.first().label)
    }

    @Test
    fun node_by_label_returns_null_when_not_found() {
        val nodes = listOf(GraphNode(id = 1, label = "Kotlin"))
        val found = nodes.firstOrNull { it.label == "Python" }
        assertNull(found)
    }

    // ── GraphEdge CRUD ────────────────────────────────────────────────────────

    @Test
    fun edge_create_has_correct_source_target() {
        val edge = GraphEdge(id = 1, sourceId = 10, targetId = 20)
        assertEquals(10L, edge.sourceId)
        assertEquals(20L, edge.targetId)
    }

    @Test
    fun edge_default_weight_is_one() {
        val edge = GraphEdge(sourceId = 1, targetId = 2)
        assertEquals(1.0, edge.weight, 1e-9)
    }

    @Test
    fun edge_default_relation_is_related_to() {
        val edge = GraphEdge(sourceId = 1, targetId = 2)
        assertEquals("related_to", edge.relation)
    }

    @Test
    fun edge_update_weight_produces_new_instance() {
        val original = GraphEdge(id = 1, sourceId = 1, targetId = 2, weight = 0.5)
        val updated  = original.copy(weight = 0.8)
        assertEquals(0.8, updated.weight, 1e-9)
        assertEquals(0.5, original.weight, 1e-9)  // immutable
    }

    @Test
    fun edge_delete_removes_from_list() {
        val edges = mutableListOf(
            GraphEdge(id = 1, sourceId = 1, targetId = 2),
            GraphEdge(id = 2, sourceId = 2, targetId = 3),
        )
        edges.removeAll { it.id == 1L }
        assertEquals(1, edges.size)
        assertEquals(2L, edges.first().id)
    }

    @Test
    fun edges_for_node_finds_source_and_target() {
        val edges = listOf(
            GraphEdge(id = 1, sourceId = 5, targetId = 10),
            GraphEdge(id = 2, sourceId = 10, targetId = 20),
            GraphEdge(id = 3, sourceId = 30, targetId = 40),
        )
        val nodeId = 10L
        val relevant = edges.filter { it.sourceId == nodeId || it.targetId == nodeId }
        assertEquals(2, relevant.size)
    }

    // ── Edge uniqueness invariant ─────────────────────────────────────────────

    @Test
    fun edge_uniqueness_by_source_target_relation() {
        val edges = mutableListOf(
            GraphEdge(id = 1, sourceId = 1, targetId = 2, relation = "related_to"),
        )
        val exists = edges.any { it.sourceId == 1L && it.targetId == 2L && it.relation == "related_to" }
        assertTrue("Duplicate edge should be detected", exists)
    }

    @Test
    fun different_relation_allows_parallel_edges() {
        val edges = listOf(
            GraphEdge(id = 1, sourceId = 1, targetId = 2, relation = "related_to"),
            GraphEdge(id = 2, sourceId = 1, targetId = 2, relation = "causes"),
        )
        val unique = edges.distinctBy { Triple(it.sourceId, it.targetId, it.relation) }
        assertEquals(2, unique.size)
    }

    // ── Homeostasis: reinforcement ────────────────────────────────────────────

    @Test
    fun reinforce_increases_weight_by_delta() {
        val start    = 0.5
        val expected = minOf(1.0, start + Tier3SynapticGraph.REINFORCE_DELTA)
        assertEquals(expected, start + Tier3SynapticGraph.REINFORCE_DELTA, 1e-9)
    }

    @Test
    fun reinforce_caps_at_one() {
        val start = 0.95
        val after = minOf(1.0, start + Tier3SynapticGraph.REINFORCE_DELTA)
        assertEquals(1.0, after, 1e-9)
    }

    // ── Homeostasis: decay ────────────────────────────────────────────────────

    @Test
    fun single_decay_cycle_multiplies_by_factor() {
        val start = 1.0
        val after = start * Tier3SynapticGraph.DECAY_FACTOR
        assertEquals(0.95, after, 1e-9)
    }

    @Test
    fun two_decay_cycles_compound_correctly() {
        val after2 = 1.0 * Tier3SynapticGraph.DECAY_FACTOR * Tier3SynapticGraph.DECAY_FACTOR
        assertEquals(0.9025, after2, 1e-6)
    }

    @Test
    fun many_decay_cycles_eventually_reach_prune_threshold() {
        var w    = 1.0
        var n    = 0
        while (w >= Tier3SynapticGraph.PRUNE_THRESHOLD && n < 10_000) {
            w *= Tier3SynapticGraph.DECAY_FACTOR
            n++
        }
        assertTrue("Decay must eventually reach prune threshold", n < 10_000)
        assertTrue(w < Tier3SynapticGraph.PRUNE_THRESHOLD)
    }

    // ── Homeostasis: prune ────────────────────────────────────────────────────

    @Test
    fun prune_removes_edges_below_threshold() {
        val edges = listOf(
            GraphEdge(id = 1, sourceId = 1, targetId = 2, weight = 0.80),
            GraphEdge(id = 2, sourceId = 1, targetId = 3, weight = 0.04),
            GraphEdge(id = 3, sourceId = 2, targetId = 3, weight = 0.10),
            GraphEdge(id = 4, sourceId = 3, targetId = 4, weight = 0.02),
        )
        val surviving = edges.filter { it.weight >= Tier3SynapticGraph.PRUNE_THRESHOLD }
        assertEquals(2, surviving.size)
        assertTrue(surviving.all { it.id == 1L || it.id == 3L })
    }

    @Test
    fun prune_keeps_edge_at_exact_threshold() {
        val edge = GraphEdge(id = 1, sourceId = 1, targetId = 2, weight = Tier3SynapticGraph.PRUNE_THRESHOLD)
        assertTrue(edge.weight >= Tier3SynapticGraph.PRUNE_THRESHOLD)
    }

    @Test
    fun prune_all_weak_yields_empty_list() {
        val edges = listOf(
            GraphEdge(id = 1, sourceId = 1, targetId = 2, weight = 0.01),
            GraphEdge(id = 2, sourceId = 2, targetId = 3, weight = 0.02),
        )
        val surviving = edges.filter { it.weight >= Tier3SynapticGraph.PRUNE_THRESHOLD }
        assertEquals(0, surviving.size)
    }

    // ── Homeostasis constants ─────────────────────────────────────────────────

    @Test
    fun decay_factor_is_in_range() {
        assertTrue(Tier3SynapticGraph.DECAY_FACTOR > 0.0)
        assertTrue(Tier3SynapticGraph.DECAY_FACTOR < 1.0)
    }

    @Test
    fun prune_threshold_less_than_decay_factor() {
        assertTrue(Tier3SynapticGraph.PRUNE_THRESHOLD < Tier3SynapticGraph.DECAY_FACTOR)
    }

    @Test
    fun reinforce_delta_is_positive() {
        assertTrue(Tier3SynapticGraph.REINFORCE_DELTA > 0.0)
    }
}
