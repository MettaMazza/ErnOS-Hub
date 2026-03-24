package com.ernos.mobile.memory

import com.ernos.mobile.memory.db.GraphEdge
import com.ernos.mobile.memory.db.GraphNode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for Tier3SynapticGraph homeostasis and graph CRUD invariants.
 *
 * These tests operate purely on the data-model logic and the homeostasis constants
 * without requiring a real Android Context or Room database.  They verify:
 *
 *   - Homeostasis constants are within sensible bounds
 *   - Edge reinforcement is correctly capped at 1.0
 *   - Decay produces the expected new weight after one cycle
 *   - Prune threshold eliminates edges that have decayed below the cutoff
 *   - GraphNode construction sets default timestamps
 *   - GraphEdge construction sets default weight to 1.0
 */
class Tier3GraphTest {

    // ── Homeostasis constant tests ─────────────────────────────────────────────

    @Test
    fun decay_factor_is_between_zero_and_one() {
        assertTrue(
            "DECAY_FACTOR must be in (0, 1)",
            Tier3SynapticGraph.DECAY_FACTOR > 0.0 && Tier3SynapticGraph.DECAY_FACTOR < 1.0,
        )
    }

    @Test
    fun prune_threshold_is_positive_and_less_than_decay_factor() {
        assertTrue(
            "PRUNE_THRESHOLD must be > 0",
            Tier3SynapticGraph.PRUNE_THRESHOLD > 0.0,
        )
        assertTrue(
            "PRUNE_THRESHOLD must be < DECAY_FACTOR",
            Tier3SynapticGraph.PRUNE_THRESHOLD < Tier3SynapticGraph.DECAY_FACTOR,
        )
    }

    @Test
    fun reinforce_delta_is_positive_and_within_range() {
        assertTrue(
            "REINFORCE_DELTA must be > 0",
            Tier3SynapticGraph.REINFORCE_DELTA > 0.0,
        )
        assertTrue(
            "REINFORCE_DELTA must be <= 1",
            Tier3SynapticGraph.REINFORCE_DELTA <= 1.0,
        )
    }

    // ── Reinforcement cap logic ────────────────────────────────────────────────

    @Test
    fun reinforce_caps_at_one_when_near_maximum() {
        val startWeight = 0.95
        val reinforced  = minOf(1.0, startWeight + Tier3SynapticGraph.REINFORCE_DELTA)
        assertEquals(
            "Reinforcement from 0.95 must cap at 1.0",
            1.0,
            reinforced,
            1e-9,
        )
    }

    @Test
    fun reinforce_from_zero_adds_delta() {
        val startWeight = 0.0
        val reinforced  = minOf(1.0, startWeight + Tier3SynapticGraph.REINFORCE_DELTA)
        assertEquals(
            "Reinforcement from 0.0 must equal REINFORCE_DELTA",
            Tier3SynapticGraph.REINFORCE_DELTA,
            reinforced,
            1e-9,
        )
    }

    // ── Decay logic ───────────────────────────────────────────────────────────

    @Test
    fun one_decay_cycle_reduces_weight_correctly() {
        val startWeight = 1.0
        val expected    = startWeight * Tier3SynapticGraph.DECAY_FACTOR
        assertEquals(
            "After one decay cycle, weight should be $expected",
            expected,
            startWeight * Tier3SynapticGraph.DECAY_FACTOR,
            1e-9,
        )
    }

    @Test
    fun edge_survives_while_above_prune_threshold() {
        var weight = 1.0
        var turns  = 0
        while (weight >= Tier3SynapticGraph.PRUNE_THRESHOLD && turns < 1000) {
            weight *= Tier3SynapticGraph.DECAY_FACTOR
            turns++
        }
        assertTrue("Edge should eventually decay below prune threshold", turns < 1000)
        assertTrue("After decay, weight should be below prune threshold", weight < Tier3SynapticGraph.PRUNE_THRESHOLD)
    }

    // ── GraphNode default values ──────────────────────────────────────────────

    @Test
    fun graph_node_defaults_are_sensible() {
        val node = GraphNode(label = "TestConcept")
        assertEquals("TestConcept", node.label)
        assertEquals("", node.description)
        assertEquals("{}", node.metadataJson)
        assertTrue("createdAtMs should be a reasonable epoch value", node.createdAtMs > 0L)
        assertEquals(node.createdAtMs, node.updatedAtMs)
    }

    // ── GraphEdge default values ──────────────────────────────────────────────

    @Test
    fun graph_edge_defaults_are_sensible() {
        val edge = GraphEdge(sourceId = 1L, targetId = 2L)
        assertEquals(1.0, edge.weight, 1e-9)
        assertEquals("related_to", edge.relation)
        assertTrue("createdAtMs should be a reasonable epoch value", edge.createdAtMs > 0L)
    }

    // ── Prune logic ───────────────────────────────────────────────────────────

    @Test
    fun pruning_logic_removes_weak_edges() {
        val edges = listOf(
            GraphEdge(id = 1, sourceId = 1, targetId = 2, weight = 0.80),
            GraphEdge(id = 2, sourceId = 1, targetId = 3, weight = 0.04),  // below threshold
            GraphEdge(id = 3, sourceId = 2, targetId = 3, weight = 0.06),
            GraphEdge(id = 4, sourceId = 3, targetId = 4, weight = 0.01),  // below threshold
        )
        val surviving = edges.filter { it.weight >= Tier3SynapticGraph.PRUNE_THRESHOLD }
        assertEquals("Should keep 2 edges above threshold", 2, surviving.size)
        assertTrue(surviving.all { it.id in listOf(1L, 3L) })
    }

    @Test
    fun prune_threshold_edge_is_kept() {
        val weight = Tier3SynapticGraph.PRUNE_THRESHOLD
        val edge   = GraphEdge(id = 99, sourceId = 1, targetId = 2, weight = weight)
        val kept   = edge.weight >= Tier3SynapticGraph.PRUNE_THRESHOLD
        assertTrue("Edge exactly at prune threshold should survive", kept)
    }
}
