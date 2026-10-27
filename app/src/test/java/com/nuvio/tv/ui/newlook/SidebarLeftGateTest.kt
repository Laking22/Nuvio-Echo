package com.nuvio.tv.ui.newlook

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SidebarLeftGateTest {

    @Test
    fun `quick presses at the edge only move focus`() {
        val gate = SidebarLeftGate()
        // Presses 120 ms apart: the one that reaches the edge and the ones after it don't open the sidebar.
        var t = 1_000L
        repeat(5) {
            gate.onLeftDown(t, 0)
            gate.onMoved()
            t += 120
        }
        repeat(4) {
            gate.onLeftDown(t, 0)
            assertFalse(gate.mayOpen())
            t += 120
        }
    }

    @Test
    fun `a fresh press after a pause at the edge opens it`() {
        val gate = SidebarLeftGate()
        gate.onLeftDown(1_000, 0)
        gate.onMoved()
        gate.onLeftDown(1_000 + SidebarLeftGate.REST_MS, 0)
        assertTrue(gate.mayOpen())
    }

    @Test
    fun `the first press of the session at the edge opens it`() {
        val gate = SidebarLeftGate()
        gate.onLeftDown(5, 0)
        assertTrue(gate.mayOpen())
    }

    @Test
    fun `one press is decided once, however many times it's asked`() {
        val gate = SidebarLeftGate()
        var t = 1_000L
        gate.onLeftDown(t, 0)
        gate.onMoved()
        // Held at the edge: the sidebar's onEnter and the scaffold both ask about the first repeat.
        t += 50
        gate.onLeftDown(t, 1)
        assertFalse(gate.mayOpen())
        assertFalse(gate.mayOpen())
        t += 50
        gate.onLeftDown(t, 2)
        assertTrue(gate.mayOpen())
        assertTrue(gate.mayOpen())
    }

    @Test
    fun `a held Left opens it once it has stopped moving`() {
        val gate = SidebarLeftGate()
        var t = 1_000L
        gate.onLeftDown(t, 0)
        gate.onMoved()
        // Key repeats every 50 ms, moving along the row, with one miss while the row scrolls.
        var repeat = 0
        repeat(6) {
            t += 50
            gate.onLeftDown(t, ++repeat)
            gate.onMoved()
        }
        t += 50
        gate.onLeftDown(t, ++repeat)
        assertFalse(gate.mayOpen())
        t += 50
        gate.onLeftDown(t, ++repeat)
        gate.onMoved()
        // At the edge: the second miss in a row opens it.
        t += 50
        gate.onLeftDown(t, ++repeat)
        assertFalse(gate.mayOpen())
        t += 50
        gate.onLeftDown(t, ++repeat)
        assertTrue(gate.mayOpen())
    }
}
