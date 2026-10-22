package com.sshborg.ui.hosts

import org.junit.Assert.*
import org.junit.Test

class JumpHostSelectionTest {
    @Test fun `no configured chain stays empty`() {
        for (raw in listOf(null, "", " \n")) {
            assertEquals(emptyList<Long>(), parseJumpHostSelection(raw))
        }
        assertNull(encodeJumpHostSelection(emptyList()))
    }

    @Test fun `valid selections preserve hop order`() {
        val ids = parseJumpHostSelection(" 7 ,2, 19 ")
        assertEquals(listOf(7L, 2L, 19L), ids)
        assertEquals("7,2,19", encodeJumpHostSelection(ids))
    }

    @Test fun `damaged tokens remain as unselected hops`() {
        for (token in listOf("bad-id", "9223372036854775808", "")) {
            assertEquals(listOf(7L, 0L, 19L), parseJumpHostSelection("7,$token,19"))
        }
        assertEquals(listOf(0L, 7L, 0L), parseJumpHostSelection(",7,"))
    }

    @Test fun `nonpositive selections cannot be silently omitted on save`() {
        for (ids in listOf(listOf(0L), listOf(-1L), listOf(7L, 0L, 19L), listOf(7L, -2L))) {
            assertThrows(IllegalArgumentException::class.java) { encodeJumpHostSelection(ids) }
        }
    }

    @Test fun `editing metadata cannot convert a damaged chain to a shorter route`() {
        for (raw in listOf("7,bad,19", "7,", "7,-2,19", "0", ",")) {
            val displayed = parseJumpHostSelection(raw)
            assertThrows(IllegalArgumentException::class.java) { encodeJumpHostSelection(displayed) }
        }
    }

    @Test fun `an explicitly corrected or removed hop can be saved`() {
        val displayed = parseJumpHostSelection("7,bad,19")
        assertEquals("7,2,19", encodeJumpHostSelection(displayed.map { if (it == 0L) 2L else it }))
        assertEquals("7,19", encodeJumpHostSelection(displayed.filter { it != 0L }))
    }
}
