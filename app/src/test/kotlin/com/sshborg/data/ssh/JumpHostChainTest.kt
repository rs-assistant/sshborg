package com.sshborg.data.ssh

import com.sshborg.data.db.HostEntity
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Test

class JumpHostChainTest {
    private val hosts = (1L..3L).associateWith { id ->
        HostEntity(id = id, label = "hop $id", hostname = "hop$id.invalid", username = "test")
    }

    @Test fun `an unconfigured chain does not look up any hosts`() {
        for (raw in listOf(null, "", " ")) {
            assertEquals(emptyList<HostEntity>(), runBlocking {
                resolveJumpHostEntities(raw) { error("No hops configured") }
            })
        }
    }

    @Test fun `all configured hops retain their order and host key pins`() {
        val pinnedHosts = hosts.mapValues { (id, host) -> host.copy(knownHostsEntry = "pin $id") }
        assertEquals(listOf(pinnedHosts[3], pinnedHosts[1], pinnedHosts[2]), runBlocking {
            resolveJumpHostEntities(" 3,1, 2 ") { pinnedHosts[it] }
        })
    }

    @Test fun `malformed hop selections never become a shorter route`() {
        for (raw in listOf("1,bad,3", "1,,3", "1,0,3", "1,-2,3", "1,9223372036854775808,3", "1,", ",1")) {
            var lookups = 0
            assertThrows(IllegalStateException::class.java) {
                runBlocking {
                    resolveJumpHostEntities(raw) { lookups++; hosts[it] }
                }
            }
            assertEquals("Validate all IDs before querying hosts: $raw", 0, lookups)
        }
    }

    @Test fun `a deleted first middle or last hop fails the entire chain`() {
        for (missingId in hosts.keys) {
            val visited = mutableListOf<Long>()
            assertThrows(IllegalStateException::class.java) {
                runBlocking {
                    resolveJumpHostEntities("1,2,3") { id ->
                        visited += id
                        hosts[id].takeUnless { id == missingId }
                    }
                }
            }
            assertEquals((1L..missingId).toList(), visited)
        }
    }

    @Test fun `database failure cannot become a direct connection`() {
        val failure = IllegalStateException("test database unavailable")
        val thrown = assertThrows(IllegalStateException::class.java) {
            runBlocking { resolveJumpHostEntities("1") { throw failure } }
        }
        assertSame(failure, thrown)
    }

    @Test fun `cancelled hop lookup cannot become a direct connection`() {
        val failure = CancellationException("test cancelled lookup")
        val thrown = assertThrows(CancellationException::class.java) {
            runBlocking { resolveJumpHostEntities("1") { throw failure } }
        }
        assertSame(failure, thrown)
    }
}
