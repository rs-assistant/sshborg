package com.sshborg.data

import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.*
import org.junit.Test

class ConnectionAttemptGrantTest {
    @Test fun `a current grant can start only one transport`() {
        val grant = ConnectionAttemptGrant { true }
        assertTrue(grant.consume())
        assertFalse(grant.consume())
    }

    @Test fun `leaving and returning to the foreground cannot revive an old attempt`() {
        var generation = 1
        val authorizedGeneration = generation
        val grant = ConnectionAttemptGrant { generation == authorizedGeneration }
        generation++
        assertFalse(grant.consume())
        generation = authorizedGeneration
        assertFalse(grant.consume())
    }

    @Test fun `activity replacement or foreground rejection refuses a grant`() {
        val grant = ConnectionAttemptGrant { false }
        assertFalse(grant.consume())
        assertFalse(grant.consume())
    }

    @Test fun `foreground validation errors fail closed and consume the attempt`() {
        var fail = true
        val grant = ConnectionAttemptGrant { if (fail) error("test") else true }
        assertFalse(grant.consume())
        fail = false
        assertFalse(grant.consume())
    }

    @Test fun `racing consumers cannot share a grant`() {
        val grant = ConnectionAttemptGrant { true }
        val ready = CountDownLatch(8)
        val start = CountDownLatch(1)
        val accepted = AtomicInteger()
        val threads = List(8) {
            Thread {
                ready.countDown()
                start.await()
                if (grant.consume()) accepted.incrementAndGet()
            }.apply { start() }
        }
        ready.await()
        start.countDown()
        threads.forEach { it.join(5000); assertFalse(it.isAlive) }
        assertEquals(1, accepted.get())
    }
}
