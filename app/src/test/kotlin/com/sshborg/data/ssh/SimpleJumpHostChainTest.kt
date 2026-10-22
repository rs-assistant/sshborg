package com.sshborg.data.ssh

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class SimpleJumpHostChainTest {
    @Test fun `only an unconfigured chain permits a direct route`() {
        for (raw in listOf(null, "", " \t\n")) {
            assertEquals(emptyList<JumpHost>(), parseJumpHosts(raw, null))
        }
    }

    @Test fun `empty first middle or last hop cannot silently shorten the route`() {
        for (raw in listOf(",", ",,", " , ", ",first", "first,", "first,,last", "first, ,last")) {
            assertThrows("Must reject the entire chain: $raw", IllegalArgumentException::class.java) {
                parseJumpHosts(raw, null)
            }
        }
    }

    @Test fun `explicit invalid ports cannot become port 22 or part of a hostname`() {
        for (port in listOf("", "0", "65536", "-1", "+22", "abc", "2147483648")) {
            assertThrows("Invalid port: $port", IllegalArgumentException::class.java) {
                parseJumpHosts("first,target:$port,last", null)
            }
        }
    }

    @Test fun `a missing host or malformed username cannot select another endpoint`() {
        for (raw in listOf("user@", ":22", "user@:22", "@host", "user@@host", "user name@host", "ho st")) {
            assertThrows("Invalid hop: $raw", IllegalArgumentException::class.java) {
                parseJumpHosts(raw, null)
            }
        }
    }

    @Test fun `valid hops preserve their order usernames ports and pins`() {
        val firstPin = "first ssh-ed25519 test-first-key"
        val secondPin = "[second]:2222 ssh-ed25519 test-second-key"
        assertEquals(
            listOf(JumpHost("first", 22, null, firstPin), JumpHost("second", 2222, "test", secondPin)),
            parseJumpHosts(" first, test@second:2222 ", "$firstPin\n$secondPin"),
        )
    }

    @Test fun `bracketed IPv6 is unwrapped without confusing its address with a port`() {
        val pin = "[2001:db8::1]:2222 ssh-ed25519 test-key"
        assertEquals(
            listOf(JumpHost("2001:db8::1", 2222, "test", pin), JumpHost("::1", 22, null, null)),
            parseJumpHosts("test@[2001:db8::1]:2222,[::1]", pin),
        )
    }

    @Test fun `whitespace separated known host fields do not silently discard a saved pin`() {
        val pin = "first\tssh-ed25519\ttest-key"
        assertEquals(listOf(JumpHost("first", 22, null, pin)), parseJumpHosts("first", pin))
    }

    @Test fun `ambiguous or malformed IPv6 endpoints are rejected`() {
        for (raw in listOf("2001:db8::1", "[::1", "[]", "[host]", "[::1]junk", "[::1]:0", "[::1]:bad")) {
            assertThrows("Invalid endpoint: $raw", IllegalArgumentException::class.java) {
                parseJumpHosts(raw, null)
            }
        }
    }
}
